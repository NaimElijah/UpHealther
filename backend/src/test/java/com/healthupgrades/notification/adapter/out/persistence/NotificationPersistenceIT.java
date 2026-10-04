package com.healthupgrades.notification.adapter.out.persistence;

import com.healthupgrades.notification.domain.model.Notification;
import com.healthupgrades.notification.domain.model.NotificationCategory;
import com.healthupgrades.notification.domain.model.NotificationType;
import com.healthupgrades.notification.domain.port.out.NotificationRepositoryPort;
import com.healthupgrades.support.AUser;
import com.healthupgrades.support.PostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The check-in sweep's once-a-day guard (FR-30), proved against the query that implements it.
 *
 * <p>{@code NotificationSchedulerTest} stubs this port, so it would pass just as happily against a query
 * that ignored the notification's type or its time. Here the JPQL meets the real table.
 *
 * <p>The query reads every user's notifications, and other integration tests commit rows to this same
 * database, so every assertion is about the users this test created — never about the whole result.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(NotificationRepositoryAdapter.class)
class NotificationPersistenceIT extends PostgresIT {

    private static final LocalDateTime MIDNIGHT = LocalDate.of(2026, 3, 15).atStartOfDay();

    @Autowired NotificationRepositoryPort repository;
    @Autowired TestEntityManager entityManager;

    @Test
    void GivenACheckinNudgeSentThisEvening_WhenTheUsersNudgedSinceMidnightAreRead_ThenThatUserIsNamed() {
        UUID nudged = persistedUser();
        repository.save(notification(nudged, NotificationType.CHECKIN_REMINDER, MIDNIGHT.plusHours(18)));
        entityManager.flush();

        assertThat(repository.findUserIdsNotifiedAfter(NotificationType.CHECKIN_REMINDER, MIDNIGHT))
                .contains(nudged);
    }

    @Test
    void GivenACheckinNudgeSentYesterday_WhenTheUsersNudgedSinceMidnightAreRead_ThenThatUserIsNot() {
        // Yesterday's nudge is the one that must not count: today's has not been sent yet.
        UUID nudgedYesterday = persistedUser();
        repository.save(notification(nudgedYesterday, NotificationType.CHECKIN_REMINDER, MIDNIGHT.minusHours(6)));
        entityManager.flush();

        assertThat(repository.findUserIdsNotifiedAfter(NotificationType.CHECKIN_REMINDER, MIDNIGHT))
                .doesNotContain(nudgedYesterday);
    }

    @Test
    void GivenANotificationOfAnotherType_WhenTheUsersNudgedSinceMidnightAreRead_ThenItDoesNotCount() {
        // A reminder that fired this morning is not a check-in nudge, and counting it would silence the
        // nudge for exactly the users who use reminders.
        UUID remindedOnly = persistedUser();
        repository.save(notification(remindedOnly, NotificationType.REMINDER, MIDNIGHT.plusHours(9)));
        entityManager.flush();

        assertThat(repository.findUserIdsNotifiedAfter(NotificationType.CHECKIN_REMINDER, MIDNIGHT))
                .doesNotContain(remindedOnly);
    }

    /** A user row for a notification to belong to, with an email no other test's row can collide with. */
    private UUID persistedUser() {
        return entityManager.persistAndFlush(
                AUser.aUser().id(null).email("notified-" + UUID.randomUUID() + "@example.com").build()).getId();
    }

    private static Notification notification(UUID userId, NotificationType type, LocalDateTime createdAt) {
        return Notification.builder()
                .userId(userId).type(type).category(NotificationCategory.REMINDER)
                .title("Daily check-in").createdAt(createdAt).build();
    }
}
