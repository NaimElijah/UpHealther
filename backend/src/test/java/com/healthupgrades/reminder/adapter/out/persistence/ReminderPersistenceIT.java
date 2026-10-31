package com.healthupgrades.reminder.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.reminder.domain.model.Reminder;
import com.healthupgrades.reminder.domain.model.ReminderDays;
import com.healthupgrades.reminder.domain.port.out.ReminderRepositoryPort;
import com.healthupgrades.support.AUser;
import com.healthupgrades.support.AnUpgrade;
import com.healthupgrades.support.PostgresIT;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

/**
 * FR-31's reminders as the dispatcher finds them, proved against the table rather than a stubbed port.
 *
 * <p>{@code NotificationSchedulerTest} hands the scheduler a list it built itself, and
 * {@code ReminderServiceTest} mocks the port, so neither can see which rows the minute's query returns or
 * whether a day filter survives the trip through its column. A reminder whose flag was never set cannot
 * be stored: the column is {@code NOT NULL} since V12, which {@code ValueDomainConstraintIT} checks.
 *
 * <p>The minute's query reads every user's reminders, and other integration tests commit rows to this
 * same database, so the assertions look only at the upgrade this test created. Its edges are written in
 * SQL, so a time keeps the microseconds a {@code TIME} column holds, whatever the driver would send.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ReminderRepositoryAdapter.class)
class ReminderPersistenceIT extends PostgresIT {

    private static final LocalTime NINE_AM = LocalTime.of(9, 0);

    @Autowired
    ReminderRepositoryPort repository;

    @Autowired
    TestEntityManager entityManager;

    private UUID upgradeId;

    @BeforeEach
    void anUpgradeToRemindAbout() {
        UUID userId = entityManager
                .persistAndFlush(AUser.aUser()
                        .id(null)
                        .email("reminded-" + UUID.randomUUID() + "@example.com")
                        .build())
                .getId();
        upgradeId = entityManager
                .persistAndFlush(AnUpgrade.ownedBy(userId)
                        .id(null)
                        .status(UpgradeStatus.ACTIVE)
                        .build())
                .getId();
    }

    @ParameterizedTest(name = "stored {0}, asked at {1}")
    @CsvSource({
        "00:00,           00:00",
        "00:00:59.999999, 00:00",
        "23:59,           23:59",
        "23:59:59.999999, 23:59",
        "09:00:30,        09:00",
        "09:00,           09:00:42"
    })
    void GivenAnEnabledReminderSetWithinTheMinute_WhenTheMinutesRemindersAreRead_ThenItComesBack(
            String stored, String askedAt) {
        // The first instant of the minute and its last microsecond both count, and 23:59 does not wrap
        // into 00:00. A reminder stored with seconds, and a question asked part way through a minute,
        // are read by the minute alone.
        UUID id = storedReminder(stored, true);

        assertThat(remindersOfThisUpgradeWithin(askedAt)).containsExactly(id);
    }

    @ParameterizedTest(name = "stored {0}, asked at {1}")
    @CsvSource({"23:59:59.999999, 00:00", "00:00,           23:59", "08:59:59.999999, 09:00", "09:01,           09:00"})
    void GivenAnEnabledReminderSetOutsideTheMinute_WhenTheMinutesRemindersAreRead_ThenItDoesNotComeBack(
            String stored, String askedAt) {
        storedReminder(stored, true);

        assertThat(remindersOfThisUpgradeWithin(askedAt)).isEmpty();
    }

    @Test
    void GivenADisabledReminderSetWithinTheMinute_WhenTheMinutesRemindersAreRead_ThenItDoesNotComeBack() {
        storedReminder("09:00", false);

        assertThat(remindersOfThisUpgradeWithin("09:00")).isEmpty();
    }

    @Test
    void GivenADayFilter_WhenTheReminderIsReadBack_ThenItFiresOnTheSameDaysAndOnNoOthers() {
        UUID id = repository
                .save(Reminder.create(upgradeId, NINE_AM, ReminderDays.of(List.of("FRI", "MON")), true))
                .getId();
        entityManager.flush();
        entityManager.clear();

        Reminder reloaded = repository.findById(id).orElseThrow();

        assertThat(reloaded.days().toTokens()).containsExactly("MON", "FRI");
        assertThat(reloaded.isDueAt(DayOfWeek.FRIDAY, NINE_AM)).isTrue();
        assertThat(reloaded.isDueAt(DayOfWeek.TUESDAY, NINE_AM)).isFalse();
    }

    @Test
    void GivenNoDayFilter_WhenTheReminderIsReadBack_ThenItFiresEveryDay() {
        UUID id = repository
                .save(Reminder.create(upgradeId, NINE_AM, ReminderDays.of(null), true))
                .getId();
        entityManager.flush();
        entityManager.clear();

        ReminderDays days = repository.findById(id).orElseThrow().days();

        assertThat(DayOfWeek.values()).allMatch(days::includes);
    }

    /** Writes a reminder for this test's upgrade with exactly the given time, every day. */
    private UUID storedReminder(String time, boolean enabled) {
        UUID id = UUID.randomUUID();
        entityManager
                .getEntityManager()
                .createNativeQuery("INSERT INTO reminders (id, upgrade_id, reminder_time, enabled) "
                        + "VALUES (CAST(?1 AS uuid), CAST(?2 AS uuid), CAST(?3 AS time), ?4)")
                .setParameter(1, id.toString())
                .setParameter(2, upgradeId.toString())
                .setParameter(3, time)
                .setParameter(4, enabled)
                .executeUpdate();
        return id;
    }

    /** The ids of this test's reminders that the dispatch read returns for the minute {@code time} is in. */
    private List<UUID> remindersOfThisUpgradeWithin(String time) {
        entityManager.flush();
        entityManager.clear();
        return repository.findEnabledWithinMinute(LocalTime.parse(time)).stream()
                .filter(reminder -> reminder.getUpgradeId().equals(upgradeId))
                .map(Reminder::getId)
                .toList();
    }
}
