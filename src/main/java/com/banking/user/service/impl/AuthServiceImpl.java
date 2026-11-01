
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
import com.banking.user.messaging.EventCrypto;
import com.banking.user.messaging.EventPublisher;
import com.banking.user.messaging.TopicNames;
import com.banking.user.messaging.payload.EmailVerificationRequestedPayload;
import com.banking.user.messaging.payload.UserRegisteredPayload;
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

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log =
            LoggerFactory.getLogger(AuthServiceImpl.class);

    private static final String REGISTER_MESSAGE =
            "If this email can be registered, we have sent you a message. Please check your inbox.";

    private static final String INVALID_LINK =
            "Invalid or expired verification link";

    private enum Action {
        VERIFY_LINK,
        ALREADY_REGISTERED,
        ACCOUNT_READY
    }

    private record Outcome(Action action, String rawToken) {}

    private record VerificationIssue(
            String rawToken,
            LocalDateTime expiresAt
    ) {}

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

    // ED3 dependencies
    private final EventPublisher eventPublisher;
    private final EventCrypto eventCrypto;

    // Dummy hash for timing protection during login
    private final String dummyHash;

    public AuthServiceImpl(
            UserRepository userRepository,
            EmailVerificationTokenRepository tokenRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            VerificationTokens verificationTokens,
            EmailDispatcher emailDispatcher,
            RegistrationMessages messages,
            EmailRateLimiter emailRateLimiter,
            RegistrationProperties properties,
            TransactionTemplate transactionTemplate,
            EventPublisher eventPublisher,
            EventCrypto eventCrypto) {

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

        this.eventPublisher = eventPublisher;
        this.eventCrypto = eventCrypto;

        this.dummyHash = passwordEncoder.encode(
                "not-a-real-password-used-for-timing"
        );
    }

    // ------------------------------------------------------------
    // REGISTER
    // ------------------------------------------------------------

    @Override
    public MessageResponse register(RegisterRequest request) {

        String email = request.email().trim().toLowerCase();
        String name = request.fullName().trim();

        String passwordHash =
                passwordEncoder.encode(request.password());

        Outcome outcome;

        try {
            outcome = transactionTemplate.execute(
                    status -> process(email, name, passwordHash)
            );

        } catch (DataIntegrityViolationException ex) {

            log.debug("Concurrent registration detected");

            return new MessageResponse(REGISTER_MESSAGE);
        }

        // Verification email is now handled through Kafka.
        // Keep the existing dispatcher for other registration messages.
        if (outcome != null
                && outcome.action() != Action.VERIFY_LINK
                && emailRateLimiter.tryAcquire(email)) {

            deliver(email, outcome);
        }

        return new MessageResponse(REGISTER_MESSAGE);
    }

    // ------------------------------------------------------------
    // PROCESS REGISTRATION INSIDE DATABASE TRANSACTION
    // ------------------------------------------------------------

    private Outcome process(
            String email,
            String name,
            String passwordHash) {

        boolean verify = properties.verificationRequired();

        Optional<User> existing =
                userRepository.findByEmail(email);

        // Existing user
        if (existing.isPresent()) {

            User user = existing.get();

            if (verify
                    && user.getStatus() == UserStatus.PENDING_VERIFICATION) {

                user.setFullName(name);
                user.setPasswordHash(passwordHash);

                userRepository.save(user);

                VerificationIssue verification =
                        issueToken(user.getId());

                // Only request a new verification email.
                publishVerificationRequested(user, verification);

                return new Outcome(
                        Action.VERIFY_LINK,
                        verification.rawToken()
                );
            }

            return new Outcome(
                    Action.ALREADY_REGISTERED,
                    null
            );
        }

        // New user
        User user = new User();

        user.setFullName(name);
        user.setEmail(email);
        user.setPasswordHash(passwordHash);
        user.setRole(Role.CUSTOMER);

        user.setStatus(
                verify
                        ? UserStatus.PENDING_VERIFICATION
                        : UserStatus.ACTIVE
        );

        User saved = userRepository.saveAndFlush(user);

        // Publish registration event for every new user.
        publishUserRegistered(saved);

        if (verify) {

            VerificationIssue verification =
                    issueToken(saved.getId());

            // Publish verification email request.
            publishVerificationRequested(saved, verification);

            return new Outcome(
                    Action.VERIFY_LINK,
                    verification.rawToken()
            );
        }

        return new Outcome(
                Action.ACCOUNT_READY,
                null
        );
    }

    // ------------------------------------------------------------
    // GENERATE AND STORE VERIFICATION TOKEN
    // ------------------------------------------------------------

    private VerificationIssue issueToken(Long userId) {

        tokenRepository.invalidateActive(userId);

        String raw = verificationTokens.newToken();

        LocalDateTime expiresAt =
                LocalDateTime.now().plusHours(
                        properties.tokenTtlHours()
                );

        EmailVerificationToken token =
                new EmailVerificationToken();

        token.setUserId(userId);
        token.setTokenHash(
                verificationTokens.hash(raw)
        );
        token.setExpiresAt(expiresAt);

        tokenRepository.save(token);

        return new VerificationIssue(
                raw,
                expiresAt
        );
    }

    // ------------------------------------------------------------
    // ED3 EVENT 1: USER REGISTERED
    // ------------------------------------------------------------

    private void publishUserRegistered(User user) {

        String userId = String.valueOf(user.getId());

        eventPublisher.publish(
                TopicNames.USER_EVENTS,
                userId,
                "UserRegistered",
                userId,
                null,
                new UserRegisteredPayload(
                        user.getId(),
                        user.getEmail(),
                        user.getFullName()
                )
        );
    }

    // ------------------------------------------------------------
    // ED3 EVENT 2: EMAIL VERIFICATION REQUESTED
    // ------------------------------------------------------------

    private void publishVerificationRequested(
            User user,
            VerificationIssue verification) {

        String userId = String.valueOf(user.getId());

        Instant expiresAt = verification.expiresAt()
                .atZone(ZoneId.systemDefault())
                .toInstant();

        eventPublisher.publish(
                TopicNames.USER_EVENTS,
                userId,
                "EmailVerificationRequested",
                userId,
                null,
                new EmailVerificationRequestedPayload(
                        user.getId(),
                        user.getEmail(),
                        user.getFullName(),
                        eventCrypto.encrypt(
                                verification.rawToken(),
                                userId
                        ),
                        expiresAt
                )
        );
    }

    // ------------------------------------------------------------
    // EXISTING EMAIL DISPATCHER
    // ------------------------------------------------------------

    private void deliver(String email, Outcome outcome) {

        RegistrationMessages.Message message =
                switch (outcome.action()) {

                    case VERIFY_LINK -> messages.verification(
                            properties.verifyUrl()
                                    + "?token="
                                    + outcome.rawToken(),
                            properties.tokenTtlHours()
                    );

                    case ALREADY_REGISTERED ->
                            messages.alreadyRegistered();

                    case ACCOUNT_READY ->
                            messages.accountReady();
                };

        emailDispatcher.send(
                email,
                message.subject(),
                message.body()
        );
    }

    // ------------------------------------------------------------
    // LOGIN
    // ------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {

        String email =
                request.email().trim().toLowerCase();

        Optional<User> found =
                userRepository.findByEmail(email);

        boolean passwordOk = passwordEncoder.matches(
                request.password(),
                found.map(User::getPasswordHash).orElse(dummyHash)
        );

        if (found.isEmpty() || !passwordOk) {
            throw new InvalidCredentialsException(
                    "Invalid email or password"
            );
        }

        User user = found.get();

        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {

            throw new EmailNotVerifiedException(
                    "Please verify your email address before logging in"
            );
        }

        if (user.getStatus() != UserStatus.ACTIVE) {

            throw new InvalidCredentialsException(
                    "Invalid email or password"
            );
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

    // ------------------------------------------------------------
    // VERIFY EMAIL
    // ------------------------------------------------------------

    @Override
    @Transactional
    public MessageResponse verifyEmail(
            VerifyEmailRequest request) {

        String hash = verificationTokens.hash(
                request.token().trim()
        );

        EmailVerificationToken token =
                tokenRepository.findByTokenHash(hash)
                        .orElseThrow(
                                () -> new InvalidVerificationException(
                                        INVALID_LINK
                                )
                        );

        // Atomic single-use verification.
        if (tokenRepository.consume(
                hash,
                LocalDateTime.now()
        ) == 0) {

            throw new InvalidVerificationException(
                    INVALID_LINK
            );
        }

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(
                        () -> new InvalidVerificationException(
                                INVALID_LINK
                        )
                );

        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {

            user.setStatus(UserStatus.ACTIVE);
            userRepository.save(user);
        }

        return new MessageResponse(
                "Email verified. You can now log in."
        );
    }
}
