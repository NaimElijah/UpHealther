package com.healthupgrades.upgrade.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.healthupgrades.support.PostgresIT;
import com.healthupgrades.upgrade.domain.port.out.UpgradeRepositoryPort;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BR-5 under concurrency, at the one place it can be observed: while one transaction is checking a
 * user's concurrent-HARD limit, no other transaction can start checking the same user's, and that lasts
 * exactly as long as the transaction (ADR-074).
 *
 * <p>The mocked port in {@code UpgradeServiceTest} locks nothing, so this is the only place the lock is
 * asserted. As in {@code AuthSessionRowLockIT}, nothing is raced: the holder takes the lock on its own
 * thread and waits on a latch, and the prober asks for the same advisory key with a {@code pg_try_*}
 * function, which answers at once instead of blocking. So the outcome is decided by the lock alone,
 * never by timing.
 *
 * <p><strong>The probe never runs on the holder's session.</strong> PostgreSQL grants a session any
 * advisory lock it already holds, at session or transaction level alike, and Hikari hands a thread back
 * the connection it returned last. A probe borrowed from the pool after a commit would therefore run on
 * the committed session and be granted a lock that a session-level holder still keeps. So the probe's
 * connection is checked out before any holder starts, and kept until the test ends.
 *
 * <p>The refusal is probed with a <em>shared</em> request, which only an exclusive holder refuses: an
 * exclusive probe is refused by a shared holder too, and a shared lock would let two checks run
 * together. The grants are probed with an exclusive request, which any holder of the key would refuse.
 *
 * <p>The probe derives the key the way the adapter's query does, so it also pins that derivation: a key
 * that changed would let the probe through and fail the first test.
 *
 * <p>The holder's transaction is real and commits, so the class opts out of {@code @DataJpaTest}'s
 * rolled-back transaction. It writes no rows.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(UpgradeRepositoryAdapter.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class HardLimitLockIT extends PostgresIT {

    /** The adapter's key for a user's HARD-limit lock, with the user id as the one parameter. */
    private static final String THE_USERS_KEY = "hashtextextended('hard-limit ' || CAST(? AS text), 0)";

    /** Refused by any holder of the key, shared or exclusive. */
    private static final String TRY_EXCLUSIVE = "SELECT pg_try_advisory_xact_lock(" + THE_USERS_KEY + ")";

    /** Refused only by an exclusive holder of the key. */
    private static final String TRY_SHARED = "SELECT pg_try_advisory_xact_lock_shared(" + THE_USERS_KEY + ")";

    /** Long enough for a slow CI machine, short enough that a lost latch fails the run instead of hanging it. */
    private static final long WAIT_SECONDS = 30;

    @Autowired
    UpgradeRepositoryPort repository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    DataSource dataSource;

    private final UUID holderUser = UUID.randomUUID();
    private final UUID otherUser = UUID.randomUUID();

    private TransactionTemplate transaction;
    private ExecutorService holderThread;

    /** Checked out before any holder starts, so it can never be the holder's session. */
    private Connection probe;

    @BeforeEach
    void aProbeAndAHolderOfTheirOwn() throws SQLException {
        probe = dataSource.getConnection();
        transaction = new TransactionTemplate(transactionManager);
        holderThread = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void stopTheHolderAndReturnTheProbe() throws SQLException {
        holderThread.shutdownNow();
        probe.close();
    }

    @Test
    void GivenATransactionCheckingAUsersHardLimit_WhenAnotherAsksForThatUsersLock_ThenItIsRefused() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch probed = new CountDownLatch(1);
        Future<?> holder = holdTheLockUntil(locked, probed);

        try {
            awaitTheLock(locked, holder);

            assertThat(ask(TRY_SHARED, holderUser))
                    .as("even a shared request for the same user's lock, from another session")
                    .isFalse();
        } finally {
            probed.countDown();
        }
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void GivenATransactionCheckingAUsersHardLimit_WhenAnotherUsersLockIsAskedFor_ThenItIsGranted() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch probed = new CountDownLatch(1);
        Future<?> holder = holdTheLockUntil(locked, probed);

        try {
            awaitTheLock(locked, holder);

            assertThat(ask(TRY_EXCLUSIVE, otherUser))
                    .as("one user's check must not hold up another's")
                    .isTrue();
        } finally {
            probed.countDown();
        }
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void GivenTheCheckingTransactionHasCommitted_WhenThatUsersLockIsAskedFor_ThenItIsGranted() throws Exception {
        // A session-level lock would stay with the pooled connection after the commit and refuse this,
        // and every later activation by the user would wait on it. The probe is not that connection.
        transaction.executeWithoutResult(status -> repository.serialiseHardLimitChecks(holderUser));

        assertThat(ask(TRY_EXCLUSIVE, holderUser)).isTrue();
    }

    @Test
    void GivenNoTransaction_WhenTheLockIsTaken_ThenTheCallFails() {
        // Outside a transaction the lock would be released with the statement and serialise nothing.
        assertThatThrownBy(() -> repository.serialiseHardLimitChecks(holderUser))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private Future<?> holdTheLockUntil(CountDownLatch locked, CountDownLatch probed) {
        return holderThread.submit(() -> transaction.executeWithoutResult(status -> {
            repository.serialiseHardLimitChecks(holderUser);
            locked.countDown();
            awaitOrFail(probed, "the prober to finish");
        }));
    }

    /**
     * Asks for the user's lock on the probe's session, which is never the holder's, without waiting.
     * The probe runs in autocommit, so a lock it is granted ends with the statement.
     *
     * @param tryStatement {@link #TRY_EXCLUSIVE} or {@link #TRY_SHARED}
     * @return whether PostgreSQL granted it
     */
    private boolean ask(String tryStatement, UUID userId) throws SQLException {
        try (PreparedStatement statement = probe.prepareStatement(tryStatement)) {
            statement.setObject(1, userId);
            try (ResultSet granted = statement.executeQuery()) {
                granted.next();
                return granted.getBoolean(1);
            }
        }
    }

    /**
     * Waits for the holder to take its lock. A holder that fails first never counts the latch down, and
     * its failure stays inside its {@code Future}; reporting only the timeout would hide the cause.
     */
    private static void awaitTheLock(CountDownLatch locked, Future<?> holder) {
        try {
            if (locked.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                return;
            }
            if (holder.isDone()) {
                holder.get();
            }
        } catch (ExecutionException failed) {
            throw new AssertionError(
                    "the holder failed before it took the lock: " + failed.getCause(), failed.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for the holder to take the lock", e);
        }
        throw new AssertionError("timed out waiting for the holder to take the lock");
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
