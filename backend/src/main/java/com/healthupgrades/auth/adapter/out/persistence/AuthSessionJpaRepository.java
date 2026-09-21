package com.healthupgrades.auth.adapter.out.persistence;

import com.healthupgrades.auth.domain.model.AuthSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data access to {@code auth_sessions}. Package-private: the port is the only way in.
 */
interface AuthSessionJpaRepository extends JpaRepository<AuthSession, UUID> {

    /**
     * Reads a session under a row-level write lock, so two refreshes of one session serialise.
     *
     * <p>Declared with an explicit query rather than a derived {@code findById}: {@code @Lock} on an
     * inherited method is not applied, so the lock would be silently absent.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AuthSession s where s.id = :id")
    Optional<AuthSession> findForUpdate(@Param("id") UUID id);

    /** Ends every live session an account holds, in one statement rather than a read-modify-write loop. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AuthSession s set s.revoked = true where s.userId = :userId and s.revoked = false")
    int revokeAllForUser(@Param("userId") UUID userId);

    /**
     * Removes sessions nothing can use again: revoked, idled out, or past their absolute cap.
     *
     * <p>A revoked row is deleted too. Keeping it would only preserve the fact that a session once
     * existed, which the audit trail already records and does so without holding a token digest.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from AuthSession s where s.revoked = true or s.idleExpiresAt < :now "
            + "or s.absoluteExpiresAt < :now")
    int deleteUnusableAsOf(@Param("now") Instant now);
}
