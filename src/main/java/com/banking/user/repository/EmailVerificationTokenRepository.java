package com.banking.user.repository;

import com.banking.user.entity.EmailVerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {

    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    /** Makes every older link for this user useless. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update EmailVerificationToken t set t.used = true where t.userId = :userId and t.used = false")
    int invalidateActive(@Param("userId") Long userId);

    /** Atomic single use: returns 1 for exactly one caller, 0 for everyone else or an expired token. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update EmailVerificationToken t set t.used = true "
            + "where t.tokenHash = :hash and t.used = false and t.expiresAt > :now")
    int consume(@Param("hash") String hash, @Param("now") LocalDateTime now);
}