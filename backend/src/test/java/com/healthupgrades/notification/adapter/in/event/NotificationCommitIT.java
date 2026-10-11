package com.healthupgrades.notification.adapter.in.event;

import com.healthupgrades.common.domain.event.DomainEvent;
import com.healthupgrades.common.domain.port.out.DomainEventPublisher;
import com.healthupgrades.notification.domain.model.Notification;
import com.healthupgrades.notification.domain.model.NotificationType;
import com.healthupgrades.notification.domain.port.out.NotificationPushPort;
import com.healthupgrades.notification.domain.port.out.NotificationRepositoryPort;
import com.healthupgrades.reflection.domain.event.ReflectionAdded;
import com.healthupgrades.support.AUser;
import com.healthupgrades.support.AnUpgrade;
import com.healthupgrades.support.PostgresIT;
import com.healthupgrades.tracking.domain.event.StreakAchieved;
import com.healthupgrades.upgrade.domain.event.HealthUpgradeAbandoned;
import com.healthupgrades.upgrade.domain.event.HealthUpgradeActivated;
import com.healthupgrades.upgrade.domain.event.HealthUpgradeCompleted;
import com.healthupgrades.upgrade.domain.event.HealthUpgradeCreated;
import com.healthupgrades.upgrade.domain.event.HealthUpgradePaused;
import com.healthupgrades.upgrade.domain.event.HealthUpgradePlanned;
import com.healthupgrades.upgrade.domain.port.out.UpgradeRepositoryPort;
import com.healthupgrades.user.application.port.in.UserCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * NFR-49 and FR-32 against a real transaction manager: a notification raised by a write is stored and
 * pushed once that write commits, and a write that rolls back raises none.
 *
 * <p>Every lifecycle listener waits for the commit, and the work it does from there has to run in a
 * transaction of its own. Joining the one that just committed stores nothing, while the push still goes
 * out, so a connected browser saw a notification the inbox never had (#169). No unit test can see that:
 * {@code NotificationEventListenerTest} mocks the service and {@code NotificationServiceTest} mocks the
 * repository, so nothing there commits. This class commits for real and reads PostgreSQL back.
 *
 * <p>The events are published through the real {@link DomainEventPublisher}, the way the services
 * publish them, so the transactional binding is exercised rather than called around. The rollback is
 * forced with {@code setRollbackOnly()} rather than provoked, so it is deterministic. Rows are counted by
 * a user created for each test, because the integration tests share one database.
 *
 * <p>A listener method added to {@link NotificationEventListener} for a new event has to be added to
 * {@link #lifecycleEvents()} as well; nothing else would notice it storing nothing.
 */
@SpringBootTest
class NotificationCommitIT extends PostgresIT {

    @Autowired DomainEventPublisher eventPublisher;
    @Autowired NotificationRepositoryPort notificationRepository;
    @Autowired UpgradeRepositoryPort upgradeRepository;
    @Autowired UserCommand userCommand;
    @Autowired TransactionTemplate transactionTemplate;

    @MockBean NotificationPushPort pushPort;

    private UUID userId;
    private UUID upgradeId;

    @BeforeEach
    void anUpgradeOwnedByAFreshUser() {
        userId = userCommand.save(
                AUser.aUser().id(null).email(UUID.randomUUID() + "@example.com").build()).getId();
        // Saved through the port rather than the service, so creating it raises no event of its own.
        upgradeId = upgradeRepository.save(AnUpgrade.ownedBy(userId).id(null).build()).getId();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lifecycleEvents")
    void GivenAWriteThatCommits_WhenItsEventIsPublished_ThenItsNotificationIsStored(
            String event, NotificationType expected, BiFunction<UUID, UUID, DomainEvent> raised) {
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publish(raised.apply(upgradeId, userId)));

        List<Notification> notifications = storedForUser();
        assertThat(notifications).hasSize(1);
        Notification stored = notifications.get(0);
        assertThat(stored.getType()).isEqualTo(expected);
        assertThat(stored.getRelatedUpgradeId()).isEqualTo(upgradeId);

        ArgumentCaptor<Notification> pushed = ArgumentCaptor.forClass(Notification.class);
        verify(pushPort).push(eq(userId), pushed.capture());
        assertThat(pushed.getValue().getId())
                .as("the push carries the row that was stored, not one that never reached the table")
                .isEqualTo(stored.getId());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lifecycleEvents")
    void GivenAWriteThatRollsBack_WhenItsEventIsPublished_ThenNoNotificationIsStoredOrPushed(
            String event, NotificationType expected, BiFunction<UUID, UUID, DomainEvent> raised) {
        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publish(raised.apply(upgradeId, userId));
            status.setRollbackOnly();
        });

        assertThat(storedForUser()).isEmpty();
        verify(pushPort, never()).push(eq(userId), any());
    }

    /** Every event a lifecycle listener waits for the commit on, with the notification it must leave. */
    static Stream<Arguments> lifecycleEvents() {
        LocalDateTime at = LocalDateTime.of(2026, 10, 11, 9, 0);
        LocalDate day = at.toLocalDate();
        return Stream.of(
                raises("created", NotificationType.UPGRADE_CREATED,
                        (upgrade, user) -> new HealthUpgradeCreated(upgrade, user, AnUpgrade.TITLE, at)),
                raises("planned", NotificationType.UPGRADE_PLANNED,
                        (upgrade, user) -> new HealthUpgradePlanned(upgrade, user, day, at)),
                raises("activated", NotificationType.UPGRADE_ACTIVATED,
                        (upgrade, user) -> new HealthUpgradeActivated(upgrade, user, day, at)),
                raises("paused", NotificationType.UPGRADE_PAUSED,
                        (upgrade, user) -> new HealthUpgradePaused(upgrade, user, at)),
                raises("completed", NotificationType.UPGRADE_COMPLETED,
                        (upgrade, user) -> new HealthUpgradeCompleted(upgrade, user, at)),
                raises("abandoned", NotificationType.UPGRADE_ABANDONED,
                        (upgrade, user) -> new HealthUpgradeAbandoned(upgrade, user, at)),
                raises("streak", NotificationType.STREAK_ACHIEVED,
                        (upgrade, user) -> new StreakAchieved(upgrade, user, 7, at)),
                raises("reflection added", NotificationType.REFLECTION_ADDED,
                        (upgrade, user) -> new ReflectionAdded(UUID.randomUUID(), upgrade, user, at)));
    }

    private static Arguments raises(String event, NotificationType expected,
                                    BiFunction<UUID, UUID, DomainEvent> raised) {
        return Arguments.of(event, expected, raised);
    }

    private List<Notification> storedForUser() {
        return notificationRepository.findTop50ByUserIdOrderByCreatedAtDesc(userId);
    }
}
