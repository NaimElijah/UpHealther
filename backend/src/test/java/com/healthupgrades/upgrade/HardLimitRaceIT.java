package com.healthupgrades.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.healthupgrades.common.domain.exception.BusinessRuleException;
import com.healthupgrades.common.domain.exception.RetryableConflictException;
import com.healthupgrades.support.AUser;
import com.healthupgrades.support.AnUpgrade;
import com.healthupgrades.support.PostgresIT;
import com.healthupgrades.upgrade.application.UpgradeService;
import com.healthupgrades.upgrade.domain.model.Difficulty;
import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import com.healthupgrades.upgrade.domain.port.out.UpgradeRepositoryPort;
import com.healthupgrades.user.application.port.in.UserCommand;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D6.4 end to end: two activations of a HARD upgrade for one user, overlapping, through
 * {@link UpgradeService} against PostgreSQL, leave at most three HARD upgrades ACTIVE (BR-5, ADR-074).
 *
 * <p>{@code UpgradeServiceTest} shows what the service does with the port's answer, and
 * {@code HardLimitLockIT} shows the claim itself. Neither shows the composition: that the second
 * request is stopped while the first is uncommitted, and that its resend counts the first's commit. That
 * holds only under READ COMMITTED, and a move to REPEATABLE READ would break it silently everywhere else.
 *
 * <p>Nothing is raced. The first activation runs on its own thread inside an outer transaction and
 * waits on a latch before committing, so the second is sent at exactly the moment the first has counted
 * and activated but not committed, which is the window D6.4 lived in.
 *
 * <p>The service is called directly, as {@code AuditCommitIT} does: it takes the owner's id as a
 * parameter and reads no security context. The class commits what it writes, as every
 * {@code @SpringBootTest} IT does, so it asserts only on its own account's rows.
 */
@SpringBootTest
class HardLimitRaceIT extends PostgresIT {

    /** Long enough for a slow CI machine, short enough that a lost latch fails the run instead of hanging it. */
    private static final long WAIT_SECONDS = 30;

    @Autowired
    UpgradeService upgradeService;

    @Autowired
    UpgradeRepositoryPort upgradeRepository;

    @Autowired
    UserCommand userCommand;

    @Autowired
    TransactionTemplate transactionTemplate;

    private ExecutorService firstRequest;
    private UUID userId;

    @BeforeEach
    void aUserWithTwoActiveHardUpgrades() {
        firstRequest = Executors.newSingleThreadExecutor();
        userId = userCommand
                .save(AUser.aUser()
                        .id(null)
                        .email("hard-limit-race-" + UUID.randomUUID() + "@example.com")
                        .build())
                .getId();
        hardUpgradeIn(UpgradeStatus.ACTIVE);
        hardUpgradeIn(UpgradeStatus.ACTIVE);
    }

    @AfterEach
    void stopTheFirstRequest() {
        firstRequest.shutdownNow();
    }

    @Test
    void GivenTwoActiveHardUpgrades_WhenTwoMoreAreActivatedAtOnce_ThenOnlyOneIsAndTheOtherIsRefusedThenLimited()
            throws Exception {
        UUID third = hardUpgradeIn(UpgradeStatus.PLANNED);
        UUID fourth = hardUpgradeIn(UpgradeStatus.PLANNED);
        CountDownLatch activated = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);

        Future<?> first = firstRequest.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            upgradeService.activate(userId, third, null);
            activated.countDown();
            awaitOrFail(commit, "the second request to be answered");
        }));
        Throwable concurrent;
        try {
            awaitTheActivation(activated, first);
            concurrent = catchThrowable(() -> upgradeService.activate(userId, fourth, null));
        } finally {
            commit.countDown();
        }
        first.get(WAIT_SECONDS, TimeUnit.SECONDS);
        Throwable resent = catchThrowable(() -> upgradeService.activate(userId, fourth, null));

        assertThat(activeHardUpgrades())
                .as("BR-5: at most three HARD upgrades ACTIVE")
                .isEqualTo(3);
        assertThat(concurrent)
                .as("sent while the first was uncommitted")
                .isInstanceOf(RetryableConflictException.class);
        assertThat(resent)
                .as("resent after the first committed, it counts three")
                .isInstanceOf(BusinessRuleException.class);
    }

    private UUID hardUpgradeIn(UpgradeStatus status) {
        HealthUpgrade upgrade = AnUpgrade.ownedBy(userId)
                .id(null)
                .status(status)
                .difficulty(Difficulty.HARD)
                .build();
        return upgradeRepository.save(upgrade).getId();
    }

    private long activeHardUpgrades() {
        return upgradeRepository.findByUserIdAndStatus(userId, UpgradeStatus.ACTIVE).stream()
                .filter(upgrade -> upgrade.getDifficulty() == Difficulty.HARD)
                .count();
    }

    /**
     * Waits for the first request to activate. A first request that fails before that never counts the
     * latch down, and its failure stays inside its {@code Future}; reporting only the timeout would hide it.
     */
    private static void awaitTheActivation(CountDownLatch activated, Future<?> first) {
        try {
            if (activated.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                return;
            }
            if (first.isDone()) {
                first.get();
            }
        } catch (ExecutionException failed) {
            throw new AssertionError(
                    "the first request failed before it activated: " + failed.getCause(), failed.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for the first request to activate", e);
        }
        throw new AssertionError("timed out waiting for the first request to activate");
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
