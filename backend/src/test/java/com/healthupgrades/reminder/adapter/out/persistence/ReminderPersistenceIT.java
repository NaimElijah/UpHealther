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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

/**
 * FR-31's reminders as the dispatcher finds them, proved against the table rather than a stubbed port.
 *
 * <p>{@code NotificationSchedulerTest} hands the scheduler a list it built itself, and
 * {@code ReminderServiceTest} mocks the port, so neither can see which rows the enabled query returns or
 * whether a day filter survives the trip through its column. A reminder whose flag was never set cannot
 * be stored: the column is {@code NOT NULL} since V12, which {@code ValueDomainConstraintIT} checks.
 *
 * <p>The enabled query reads every user's reminders, and other integration tests commit rows to this
 * same database, so the assertions look only at the upgrade this test created.
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

    @Test
    void GivenAnEnabledAndADisabledReminder_WhenTheEnabledOnesAreRead_ThenOnlyTheEnabledOneComesBack() {
        UUID enabled = repository
                .save(Reminder.create(upgradeId, NINE_AM, ReminderDays.of(null), true))
                .getId();
        repository.save(Reminder.create(upgradeId, NINE_AM, ReminderDays.of(null), false));
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findByEnabledTrue())
                .filteredOn(reminder -> reminder.getUpgradeId().equals(upgradeId))
                .extracting(Reminder::getId)
                .containsExactly(enabled);
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
}
