package com.banking.user.service.impl;

import com.banking.user.config.RegistrationProperties;
import com.banking.user.dto.AuthResponse;
import com.banking.user.dto.LoginRequest;
import com.banking.user.dto.MessageResponse;
import com.banking.user.dto.RegisterRequest;
import com.banking.user.dto.VerifyEmailRequest;
import com.banking.user.email.EmailDispatcher;
import com.banking.user.email.RegistrationMessages;
import com.banking.user.entity.EmailVerificationToken;
import com.banking.user.entity.Role;
import com.banking.user.entity.User;
import com.banking.user.entity.UserStatus;
import com.banking.user.exception.EmailNotVerifiedException;
import com.banking.user.exception.InvalidCredentialsException;
import com.banking.user.exception.InvalidVerificationException;
import com.banking.user.repository.EmailVerificationTokenRepository;
import com.banking.user.repository.UserRepository;
import com.banking.user.security.JwtService;
import com.banking.user.security.VerificationTokens;
import com.banking.user.service.AuthService;
import com.banking.user.throttle.EmailRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    private static final String REGISTER_MESSAGE =
            "If this email can be registered, we have sent you a message. Please check your inbox.";
    private static final String INVALID_LINK = "Invalid or expired verification link";

    private enum Action { VERIFY_LINK, ALREADY_REGISTERED, ACCOUNT_READY }

    private record Outcome(Action action, String rawToken) {}

    private final UserRepository userRepository;
    private final EmailVerificationTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final VerificationTokens verificationTokens;
    private final EmailDispatcher emailDispatcher;
    private final RegistrationMessages messages;
    private final EmailRateLimiter emailRateLimiter;
    private final RegistrationProperties properties;
    private final TransactionTemplate transactionTemplate;

    // A hash that matches nothing. Login compares against it for unknown emails, so an unknown
    // email takes as long as a wrong password.
    private final String dummyHash;

    public AuthServiceImpl(UserRepository userRepository,
                           EmailVerificationTokenRepository tokenRepository,
                           PasswordEncoder passwordEncoder,
                           JwtService jwtService,
                           VerificationTokens verificationTokens,
                           EmailDispatcher emailDispatcher,
                           RegistrationMessages messages,
                           EmailRateLimiter emailRateLimiter,
                           RegistrationProperties properties,
                           TransactionTemplate transactionTemplate) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.verificationTokens = verificationTokens;
        this.emailDispatcher = emailDispatcher;
        this.messages = messages;
        this.emailRateLimiter = emailRateLimiter;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.dummyHash = passwordEncoder.encode("not-a-real-password-used-for-timing");
    }

    // ------------------------------------------------------------------ register

    @Override
    public MessageResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase();
        String name = request.fullName().trim();

        // Always hash, so the time does not depend on whether the email exists.
        String passwordHash = passwordEncoder.encode(request.password());

        Outcome outcome;
        try {
            outcome = transactionTemplate.execute(status -> process(email, name, passwordHash));
        } catch (DataIntegrityViolationException ex) {
            // Two identical registrations at the same moment. The other one won, answer the same way.
            log.debug("Concurrent registration detected");
            return new MessageResponse(REGISTER_MESSAGE);
        }

        // Emails go out after the database commit, so a verification link always exists when it arrives.
        if (outcome != null && emailRateLimiter.tryAcquire(email)) {
            deliver(email, outcome);
        }
        return new MessageResponse(REGISTER_MESSAGE);
    }

    private Outcome process(String email, String name, String passwordHash) {
        boolean verify = properties.verificationRequired();
        Optional<User> existing = userRepository.findByEmail(email);

        if (existing.isPresent()) {
            User user = existing.get();
            if (verify && user.getStatus() == UserStatus.PENDING_VERIFICATION) {
                // Not verified yet: the newest registration wins and gets a fresh link.
                user.setFullName(name);
                user.setPasswordHash(passwordHash);
                userRepository.save(user);
                return new Outcome(Action.VERIFY_LINK, issueToken(user.getId()));
            }
            return new Outcome(Action.ALREADY_REGISTERED, null);
        }

        User user = new User();
        user.setFullName(name);
        user.setEmail(email);
        user.setPasswordHash(passwordHash);
        user.setRole(Role.CUSTOMER);
        user.setStatus(verify ? UserStatus.PENDING_VERIFICATION : UserStatus.ACTIVE);
        // Flush now, so a duplicate-email race surfaces inside this transaction.
        User saved = userRepository.saveAndFlush(user);

        return verify
                ? new Outcome(Action.VERIFY_LINK, issueToken(saved.getId()))
                : new Outcome(Action.ACCOUNT_READY, null);
    }

    private String issueToken(Long userId) {
        tokenRepository.invalidateActive(userId);

        String raw = verificationTokens.newToken();
        EmailVerificationToken token = new EmailVerificationToken();
        token.setUserId(userId);
        token.setTokenHash(verificationTokens.hash(raw));
        token.setExpiresAt(LocalDateTime.now().plusHours(properties.tokenTtlHours()));
        tokenRepository.save(token);
        return raw;
    }

    private void deliver(String email, Outcome outcome) {
        RegistrationMessages.Message message = switch (outcome.action()) {
            case VERIFY_LINK -> messages.verification(
                    properties.verifyUrl() + "?token=" + outcome.rawToken(), properties.tokenTtlHours());
            case ALREADY_REGISTERED -> messages.alreadyRegistered();
            case ACCOUNT_READY -> messages.accountReady();
        };
        emailDispatcher.send(email, message.subject(), message.body());
    }

    // --------------------------------------------------------------------- login

    @Override
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase();

        Optional<User> found = userRepository.findByEmail(email);
        boolean passwordOk = passwordEncoder.matches(
                request.password(),
                found.map(User::getPasswordHash).orElse(dummyHash));

        if (found.isEmpty() || !passwordOk) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        User user = found.get();
        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            // Only someone who knows the password can see this, so it reveals nothing to a stranger.
            throw new EmailNotVerifiedException("Please verify your email address before logging in");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        String token = jwtService.generateToken(user);

        return new AuthResponse(
                token,
                "Bearer",
                jwtService.getExpirationMs() / 1000,
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole().name()
        );
    }

    // -------------------------------------------------------------------- verify

    @Override
    @Transactional
    public MessageResponse verifyEmail(VerifyEmailRequest request) {
        String hash = verificationTokens.hash(request.token().trim());

        EmailVerificationToken token = tokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new InvalidVerificationException(INVALID_LINK));

        // Atomic single use: only one request can consume a token, and expired tokens give 0.
        if (tokenRepository.consume(hash, LocalDateTime.now()) == 0) {
            throw new InvalidVerificationException(INVALID_LINK);
        }

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new InvalidVerificationException(INVALID_LINK));
        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            user.setStatus(UserStatus.ACTIVE);
            userRepository.save(user);
        }
        return new MessageResponse("Email verified. You can now log in.");
    }
}