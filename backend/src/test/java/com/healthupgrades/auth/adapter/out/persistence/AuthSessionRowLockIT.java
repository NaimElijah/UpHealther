package com.healthupgrades.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.healthupgrades.auth.domain.model.AuthSession;
import com.healthupgrades.auth.domain.model.RefreshToken;
import com.healthupgrades.auth.domain.port.out.AuthSessionRepositoryPort;
import com.healthupgrades.support.AUser;
import com.healthupgrades.support.PostgresIT;
import com.healthupgrades.user.domain.model.User;
import jakarta.persistence.EntityManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-015's row lock, which only two real transactions can observe: while one refresh holds a session
 * through {@code findForUpdate}, no other transaction can take that row for update.
 *
 * <p>Without the lock, two tabs refreshing at once both read the same state, both rotate from it, and
 * the second write signs the loser out (NFR-39). The in-memory fake in {@code AuthSessionServiceTest}
 * locks nothing, so this is the only place the lock is asserted.
 *
 * <p>The test does not race two refreshes and hope they collide. The holder takes the lock on its own
 * thread and waits on a latch; the prober asks for a share lock on the same row with {@code NOWAIT},
 * which PostgreSQL refuses at once rather than blocking, so the outcome is decided by the lock alone and
 * never by timing. The probe is a share lock because only a lock taken for update refuses one: were
 * {@code findForUpdate} to take a share lock, two refreshes would both read the pre-rotation state and
 * deadlock on their writes, and a {@code FOR UPDATE} probe, which a share lock refuses too, would pass.
 *
 * <p>Both transactions are real and commit, so the class opts out of {@code @DataJpaTest}'s rolled-back
 * transaction. It creates its own account and deletes it afterwards, which takes the session with it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AuthSessionRepositoryAdapter.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuthSessionRowLockIT extends PostgresIT {

    private static final Instant NOW = Instant.parse("2026-09-20T10:00:00Z");
    private static final Duration IDLE = Duration.ofDays(7);
    private static final Duration ABSOLUTE = Duration.ofDays(30);

    /** PostgreSQL's SQLSTATE for a {@code NOWAIT} lock it could not take. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    /** Long enough for a slow CI machine, short enough that a lost latch fails the run instead of hanging it. */
    private static final long WAIT_SECONDS = 30;

    @Autowired
    AuthSessionRepositoryPort repository;

    @Autowired
    EntityManager entityManager;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    DataSource dataSource;

    private TransactionTemplate transaction;
    private ExecutorService holderThread;
    private UUID ownerId;
    private UUID sessionId;

    @BeforeEach
    void aCommittedSession() {
        transaction = new TransactionTemplate(transactionManager);
        holderThread = Executors.newSingleThreadExecutor();
        transaction.executeWithoutResult(status -> {
            User owner = AUser.aUser()
                    .id(null)
                    .email(UUID.randomUUID() + "@example.com")
                    .build();
            entityManager.persist(owner);
            entityManager.flush();
            ownerId = owner.getId();
            sessionId = UUID.randomUUID();
            repository.save(AuthSession.opened(
                    sessionId, ownerId, new RefreshToken(sessionId, "a-secret").hash(), NOW, IDLE, ABSOLUTE));
        });
    }

    @AfterEach
    void deleteTheAccount() {
        holderThread.shutdownNow();
        new JdbcTemplate(dataSource).update("DELETE FROM users WHERE id = ?", ownerId);
    }

    @Test
    void GivenARefreshHoldingASession_WhenAnotherTransactionAsksToLockIt_ThenItIsRefused() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch probed = new CountDownLatch(1);
        Future<?> holder = holderThread.submit(() -> transaction.executeWithoutResult(status -> {
            assertThat(repository.findForUpdate(sessionId)).isPresent();
            locked.countDown();
            awaitOrFail(probed, "the prober to finish");
        }));

        try {
            awaitOrFail(locked, "the holder to lock the session");

            Throwable probe =
                    catchThrowable(() -> transaction.executeWithoutResult(status -> new JdbcTemplate(dataSource)
                            .queryForList("SELECT id FROM auth_sessions WHERE id = ? FOR SHARE NOWAIT", sessionId)));

            assertThat(probe)
                    .as("a share lock on a session findForUpdate holds, which only a lock taken for update refuses")
                    .isInstanceOfSatisfying(
                            DataAccessException.class,
                            refusal -> assertThat(((SQLException) refusal.getMostSpecificCause()).getSQLState())
                                    .isEqualTo(LOCK_NOT_AVAILABLE));
        } finally {
            probed.countDown();
        }
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static void awaitOrFail(CountDownLatch latch, String what) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for " + what);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for " + what, e);
        }
    }
}
