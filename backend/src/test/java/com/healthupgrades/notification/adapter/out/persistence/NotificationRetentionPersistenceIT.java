package com.healthupgrades.notification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.notification.domain.port.out.NotificationRepositoryPort;
import com.healthupgrades.support.PostgresIT;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * NFR-60's delete against the real table: which rows one batch removes, how many, and that each batch
 * commits on its own (ADR-075).
 *
 * <p>Each batch runs in a transaction of its own, which cannot see rows another transaction has not
 * committed. So the rows here are committed, and the class opts out of {@code @DataJpaTest}'s rolled-back
 * transaction, as {@code UserConcurrentWritesIT} does. It deletes the account it creates, and its
 * notifications with it.
 *
 * <p>The delete reaches every user's rows, and other integration tests commit rows to this same database,
 * so every row here is created before {@link #LONG_AGO}, long before anything another test writes, and every
 * assertion is about this test's own rows.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(NotificationRepositoryAdapter.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationRetentionPersistenceIT extends PostgresIT {

    /** A retention cutoff long before any notification another integration test writes. */
    private static final LocalDateTime LONG_AGO = LocalDate.of(2001, 1, 1).atStartOfDay();

    @Autowired
    NotificationRepositoryPort repository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbc;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void anAccount() {
        jdbc.update(
                "INSERT INTO users (id, name, email, password_hash) VALUES (?, 'Someone', ?, 'x')",
                userId,
                "retention-" + userId + "@example.com");
    }

    @AfterEach
    void deleteTheAccount() {
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void GivenNotificationsEitherSideOfTheCutoff_WhenTheOldOnesAreDeleted_ThenOnlyThoseCreatedBeforeItGo() {
        // NFR-60. Strictly before: one created at the cutoff itself is exactly the retention period old, and
        // stays for one more night.
        UUID before = committed(LONG_AGO.minusSeconds(1));
        UUID at = committed(LONG_AGO);
        UUID after = committed(LONG_AGO.plusSeconds(1));

        int deleted = repository.deleteCreatedBefore(LONG_AGO, 10);

        assertThat(deleted).isEqualTo(1);
        assertThat(remaining()).containsExactlyInAnyOrder(at, after).doesNotContain(before);
    }

    @Test
    void
            GivenMoreOldNotificationsThanTheLimit_WhenTheOldOnesAreDeleted_ThenOneStatementDeletesNoMoreThanTheLimitOldestFirst() {
        // The batch bound is what keeps one night's statement short, whatever backlog it finds, and the
        // oldest go first, so a run cut short leaves the newest. The newest is written first, so a delete
        // that took rows in the order they were stored, rather than by age, would take it.
        UUID newest = committed(LONG_AGO.minusDays(1));
        committed(LONG_AGO.minusDays(3));
        committed(LONG_AGO.minusDays(2));

        assertThat(repository.deleteCreatedBefore(LONG_AGO, 2)).isEqualTo(2);
        assertThat(remaining()).as("the newest of the three").containsExactly(newest);
        assertThat(repository.deleteCreatedBefore(LONG_AGO, 2)).isEqualTo(1);
        assertThat(repository.deleteCreatedBefore(LONG_AGO, 2)).isZero();
    }

    @Test
    void GivenTheCallersTransactionRollsBack_WhenABatchWasDeletedInsideIt_ThenTheBatchStaysDeleted() {
        // The port promises a transaction of its own, so a caller that wrapped the run in one, by habit or
        // by mistake, cannot turn the whole backlog back into one transaction, the design ADR-075 rejects.
        UUID old = committed(LONG_AGO.minusDays(1));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(repository.deleteCreatedBefore(LONG_AGO, 10)).isEqualTo(1);
            status.setRollbackOnly();
        });

        assertThat(remaining()).doesNotContain(old);
    }

    /** Commits a notification of this test's account, created at the given time, and returns its id. */
    private UUID committed(LocalDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO notifications (id, user_id, type, category, title, created_at) "
                        + "VALUES (?, ?, 'CHECKIN_REMINDER', 'REMINDER', 'Time to check in', ?)",
                id,
                userId,
                createdAt);
        return id;
    }

    private List<UUID> remaining() {
        return jdbc.queryForList("SELECT id FROM notifications WHERE user_id = ?", UUID.class, userId);
    }
}
