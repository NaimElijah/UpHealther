package com.healthupgrades.notification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.notification.domain.model.Notification;
import com.healthupgrades.notification.domain.model.NotificationCategory;
import com.healthupgrades.notification.domain.model.NotificationType;
import com.healthupgrades.notification.domain.port.out.NotificationRepositoryPort;
import com.healthupgrades.support.AUser;
import com.healthupgrades.support.AnUpgrade;
import com.healthupgrades.support.PostgresIT;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

/**
 * The check-in sweep's once-a-day guard (FR-30), the overdue notice's once-per-target-date guard
 * (BR-11), and the inbox's reads and mark-all (FR-33, BR-15), each proved against the query that
 * implements it.
 *
 * <p>{@code NotificationSchedulerTest} and {@code NotificationServiceTest} stub this port, so they would
 * pass just as happily against a query that ignored the notification's type or its time. Here the queries
 * meet the real table.
 *
 * <p>The query reads every user's notifications, and other integration tests commit rows to this same
 * database, so every assertion is about the users this test created — never about the whole result.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(NotificationRepositoryAdapter.class)
class NotificationPersistenceIT extends PostgresIT {

    private static final LocalDateTime MIDNIGHT = LocalDate.of(2026, 3, 15).atStartOfDay();

    /** How many notifications the inbox lists (FR-33). */
    private static final int INBOX_SIZE = 50;

    /** The start of the day after a target date of 1 October: the earliest an overdue notice for it exists. */
    private static final LocalDateTime OVERDUE_SINCE = LocalDate.of(2026, 10, 2).atStartOfDay();

    @Autowired
    NotificationRepositoryPort repository;

    @Autowired
    TestEntityManager entityManager;

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

    @Test
    void GivenAnOverdueNoticeFromBeforeTheTargetDatePassed_WhenAskedWhetherOneExistsSince_ThenItDoesNotCount() {
        // A notice about an earlier target date, which has since been moved later. The new date has not
        // been announced, so this one must not silence it.
        UUID userId = persistedUser();
        UUID upgradeId = persistedUpgrade(userId);
        repository.save(overdueNotice(userId, upgradeId, OVERDUE_SINCE.minusSeconds(1)));
        entityManager.flush();

        assertThat(repository.existsForUpgradeSince(userId, upgradeId, NotificationType.UPGRADE_OVERDUE, OVERDUE_SINCE))
                .isFalse();
    }

    @Test
    void GivenAnOverdueNoticeFromTheFirstInstantAfterTheTargetDate_WhenAskedWhetherOneExistsSince_ThenItCounts() {
        // The bound is inclusive: the first instant of the next day is already past the target date, and
        // the sweep's cron is configurable, so a run at midnight can stamp its notice exactly there.
        UUID userId = persistedUser();
        UUID upgradeId = persistedUpgrade(userId);
        repository.save(overdueNotice(userId, upgradeId, OVERDUE_SINCE));
        entityManager.flush();

        assertThat(repository.existsForUpgradeSince(userId, upgradeId, NotificationType.UPGRADE_OVERDUE, OVERDUE_SINCE))
                .isTrue();
    }

    @Test
    void GivenAnOverdueNoticeFromAfterTheTargetDatePassed_WhenAskedWhetherOneExistsSince_ThenItCounts() {
        UUID userId = persistedUser();
        UUID upgradeId = persistedUpgrade(userId);
        repository.save(overdueNotice(userId, upgradeId, OVERDUE_SINCE.plusHours(8)));
        entityManager.flush();

        assertThat(repository.existsForUpgradeSince(userId, upgradeId, NotificationType.UPGRADE_OVERDUE, OVERDUE_SINCE))
                .isTrue();
    }

    @Test
    void GivenAnOverdueNoticeAboutAnotherUpgrade_WhenAskedWhetherOneExistsSince_ThenItDoesNotCount() {
        UUID userId = persistedUser();
        UUID announced = persistedUpgrade(userId);
        UUID notYetAnnounced = persistedUpgrade(userId);
        repository.save(overdueNotice(userId, announced, OVERDUE_SINCE.plusHours(8)));
        entityManager.flush();

        assertThat(repository.existsForUpgradeSince(
                        userId, notYetAnnounced, NotificationType.UPGRADE_OVERDUE, OVERDUE_SINCE))
                .isFalse();
    }

    @Test
    void GivenANoticeOfAnotherTypeAboutTheSameUpgrade_WhenAskedWhetherAnOverdueOneExistsSince_ThenItDoesNotCount() {
        // A reminder about the upgrade is not an overdue notice, and counting it would silence the overdue
        // notice for exactly the upgrades somebody set a reminder on.
        UUID userId = persistedUser();
        UUID upgradeId = persistedUpgrade(userId);
        repository.save(Notification.builder()
                .userId(userId)
                .relatedUpgradeId(upgradeId)
                .type(NotificationType.REMINDER)
                .category(NotificationCategory.REMINDER)
                .title("Reminder")
                .createdAt(OVERDUE_SINCE.plusHours(8))
                .build());
        entityManager.flush();

        assertThat(repository.existsForUpgradeSince(userId, upgradeId, NotificationType.UPGRADE_OVERDUE, OVERDUE_SINCE))
                .isFalse();
    }

    @Test
    void
            GivenFiftyOneNotificationsAndAnotherUsersNewerOnes_WhenTheRecentOnesAreRead_ThenTheirFiftyNewestComeBackNewestFirst() {
        UUID userId = persistedUser();
        UUID other = persistedUser();
        for (int minute = 0; minute <= INBOX_SIZE; minute++) {
            repository.save(inboxEntry(userId, MIDNIGHT.plusMinutes(minute), false));
        }
        // Newer than every one of the user's, so an inbox that lost its user scope would lead with them.
        repository.save(inboxEntry(other, MIDNIGHT.plusMinutes(INBOX_SIZE).plusSeconds(30), false));
        repository.save(inboxEntry(other, MIDNIGHT.plusMinutes(INBOX_SIZE / 2).plusSeconds(30), false));
        entityManager.flush();

        List<LocalDateTime> listed = repository.findTop50ByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(Notification::getCreatedAt)
                .toList();

        List<LocalDateTime> newestFirst = IntStream.iterate(INBOX_SIZE, minute -> minute >= 1, minute -> minute - 1)
                .mapToObj(MIDNIGHT::plusMinutes)
                .toList();
        assertThat(listed).containsExactlyElementsOf(newestFirst);
    }

    @Test
    void
            GivenMoreUnreadNotificationsThanTheInboxLists_WhenTheUnreadAreCounted_ThenEveryUnreadOneOfTheirsCountsAndNoOtherOne() {
        // FR-33: the badge counts every unread notification, not only the fifty the inbox lists. BR-15:
        // and only the user's own, so another user's unread one must not count.
        UUID userId = persistedUser();
        UUID other = persistedUser();
        int unread = INBOX_SIZE + 2;
        for (int minute = 0; minute < unread; minute++) {
            repository.save(inboxEntry(userId, MIDNIGHT.plusMinutes(minute), false));
        }
        repository.save(inboxEntry(userId, MIDNIGHT.minusHours(1), true));
        repository.save(inboxEntry(other, MIDNIGHT, false));
        entityManager.flush();

        assertThat(repository.countByUserIdAndReadFalse(userId)).isEqualTo(unread);
    }

    @Test
    void GivenTwoUsersWithUnreadNotifications_WhenOneMarksAllRead_ThenOnlyTheirsAreRead() {
        UUID marking = persistedUser();
        UUID other = persistedUser();
        UUID markingsOwn = repository.save(inboxEntry(marking, MIDNIGHT, false)).getId();
        UUID othersOwn = repository.save(inboxEntry(other, MIDNIGHT, false)).getId();
        entityManager.flush();

        repository.markAllReadForUser(marking);
        // The bulk update bypasses the persistence context, so the rows must be read back from the table.
        entityManager.clear();

        assertThat(entityManager.find(Notification.class, markingsOwn).isRead())
                .as("the marking user's own notification")
                .isTrue();
        assertThat(entityManager.find(Notification.class, othersOwn).isRead())
                .as("another user's notification")
                .isFalse();
    }

    @Test
    void GivenAnotherUsersNotification_WhenItIsLookedUpById_ThenItIsReportedAsAbsent() {
        // BR-15: the lookup is scoped to the owner, so another user's id finds nothing rather than the row.
        UUID owner = persistedUser();
        UUID stranger = persistedUser();
        UUID notificationId =
                repository.save(inboxEntry(owner, MIDNIGHT, false)).getId();
        entityManager.flush();

        assertThat(repository.findByIdAndUserId(notificationId, stranger)).isEmpty();
        assertThat(repository.findByIdAndUserId(notificationId, owner)).isPresent();
    }

    /** A user row for a notification to belong to, with an email no other test's row can collide with. */
    private UUID persistedUser() {
        return entityManager
                .persistAndFlush(AUser.aUser()
                        .id(null)
                        .email("notified-" + UUID.randomUUID() + "@example.com")
                        .build())
                .getId();
    }

    /** An upgrade row for a notification to refer to; {@code related_upgrade_id} is a foreign key. */
    private UUID persistedUpgrade(UUID userId) {
        return entityManager
                .persistAndFlush(AnUpgrade.ownedBy(userId)
                        .id(null)
                        .status(UpgradeStatus.ACTIVE)
                        .build())
                .getId();
    }

    private static Notification overdueNotice(UUID userId, UUID upgradeId, LocalDateTime createdAt) {
        return Notification.builder()
                .userId(userId)
                .relatedUpgradeId(upgradeId)
                .type(NotificationType.UPGRADE_OVERDUE)
                .category(NotificationCategory.WARNING)
                .title("Upgrade overdue")
                .createdAt(createdAt)
                .build();
    }

    private static Notification inboxEntry(UUID userId, LocalDateTime createdAt, boolean read) {
        return Notification.builder()
                .userId(userId)
                .type(NotificationType.REMINDER)
                .category(NotificationCategory.REMINDER)
                .title("Reminder")
                .read(read)
                .createdAt(createdAt)
                .build();
    }

    private static Notification notification(UUID userId, NotificationType type, LocalDateTime createdAt) {
        return Notification.builder()
                .userId(userId)
                .type(type)
                .category(NotificationCategory.REMINDER)
                .title("Daily check-in")
                .createdAt(createdAt)
                .build();
    }
}
