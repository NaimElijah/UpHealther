package com.healthupgrades.notification.adapter.in.event;

import com.healthupgrades.notification.application.NotificationService;
import com.healthupgrades.notification.domain.model.Notification;
import com.healthupgrades.notification.domain.model.NotificationCategory;
import com.healthupgrades.notification.domain.model.NotificationType;
import com.healthupgrades.notification.domain.port.out.NotificationPushPort;
import com.healthupgrades.notification.domain.port.out.NotificationRepositoryPort;
import com.healthupgrades.support.AUser;
import com.healthupgrades.support.AnUpgrade;
import com.healthupgrades.support.PostgresIT;
import com.healthupgrades.upgrade.application.port.in.UpgradeQuery;
import com.healthupgrades.upgrade.domain.event.UpgradeOverdueDetected;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BR-11 end to end: an overdue upgrade is announced once per target date it misses.
 *
 * <p>The unit tests each pin one piece against a mock — the date on the event, the bound the listener
 * derives, the service honouring the port, and {@code NotificationPersistenceIT} the query alone. This
 * drives the listener through the real service into PostgreSQL, so the rule itself is what is asserted:
 * a date moved later and missed again is announced again, and a date already announced is not.
 *
 * <p>Only the title lookup and the real-time push are mocked; neither decides whether a notice is
 * created. The clock is fixed at the morning the moved date is first found overdue.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({NotificationEventListener.class, NotificationService.class})
class OverdueAnnouncementIT extends PostgresIT {

    private static final LocalDate FIRST_TARGET = LocalDate.of(2026, 10, 1);
    private static final LocalDate MOVED_TARGET = LocalDate.of(2026, 10, 10);
    /** The first sweep after the first target date passed: when its notice was sent. */
    private static final LocalDateTime FIRST_NOTICE_SENT = LocalDateTime.of(2026, 10, 2, 8, 0);
    /** The first sweep after the moved target date passed, and the instant the clock is fixed at. */
    private static final Instant SWEEP_AFTER_MOVED_TARGET = Instant.parse("2026-10-11T08:00:00Z");

    @Autowired NotificationEventListener listener;
    @Autowired NotificationRepositoryPort repository;
    @Autowired TestEntityManager entityManager;

    @MockBean UpgradeQuery upgradeQuery;
    @MockBean NotificationPushPort pushPort;

    private UUID userId;
    private UUID upgradeId;

    @BeforeEach
    void anUpgradeAlreadyAnnouncedForItsFirstTargetDate() {
        userId = entityManager.persistAndFlush(
                AUser.aUser().id(null).email("overdue-" + UUID.randomUUID() + "@example.com").build()).getId();
        upgradeId = entityManager.persistAndFlush(
                AnUpgrade.ownedBy(userId).id(null).status(UpgradeStatus.ACTIVE).build()).getId();
        repository.save(Notification.builder()
                .userId(userId).relatedUpgradeId(upgradeId).type(NotificationType.UPGRADE_OVERDUE)
                .category(NotificationCategory.WARNING).title("Upgrade overdue ⏰")
                .createdAt(FIRST_NOTICE_SENT).build());
        entityManager.flush();
    }

    @Test
    void GivenATargetDateMovedLaterAndMissedAgain_WhenTheSweepFindsItOverdue_ThenItIsAnnouncedAgain() {
        listener.onOverdue(overdue(MOVED_TARGET));

        assertThat(overdueNotices()).isEqualTo(2);
    }

    @Test
    void GivenTheMovedDateAlreadyAnnounced_WhenTheSweepFindsItOverdueAgain_ThenNothingNewIsAnnounced() {
        listener.onOverdue(overdue(MOVED_TARGET));
        listener.onOverdue(overdue(MOVED_TARGET));

        assertThat(overdueNotices()).isEqualTo(2);
    }

    @Test
    void GivenTheTargetDateNeverMoved_WhenTheSweepFindsItOverdueAgain_ThenNothingNewIsAnnounced() {
        listener.onOverdue(overdue(FIRST_TARGET));

        assertThat(overdueNotices()).isEqualTo(1);
    }

    private UpgradeOverdueDetected overdue(LocalDate targetEndDate) {
        return new UpgradeOverdueDetected(upgradeId, userId, targetEndDate,
                LocalDateTime.ofInstant(SWEEP_AFTER_MOVED_TARGET, ZoneOffset.UTC));
    }

    private long overdueNotices() {
        entityManager.flush();
        return repository.findTop50ByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(n -> n.getType() == NotificationType.UPGRADE_OVERDUE)
                .filter(n -> upgradeId.equals(n.getRelatedUpgradeId()))
                .count();
    }

    /**
     * The clock the service stamps notices with, and the notification persistence adapter, which is
     * package-private to its own package and so is found by scanning rather than imported by name.
     */
    @TestConfiguration
    @ComponentScan(basePackages = "com.healthupgrades.notification.adapter.out.persistence")
    static class Wiring {

        /** Primary over the application's system clock, which the slice also loads. */
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(SWEEP_AFTER_MOVED_TARGET, ZoneOffset.UTC);
        }
    }
}
