package com.healthupgrades.notification.application;

import com.healthupgrades.notification.api.NotificationDto;
import com.healthupgrades.notification.domain.Notification;
import com.healthupgrades.notification.domain.NotificationCategory;
import com.healthupgrades.notification.domain.NotificationType;
import com.healthupgrades.notification.infrastructure.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock NotificationRepository repository;
    @Mock SimpMessagingTemplate messagingTemplate;

    @InjectMocks NotificationService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID upgradeId = UUID.randomUUID();

    @Test
    void create_persistsAndPushesToUser() {
        when(repository.save(any(Notification.class))).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            n.setId(UUID.randomUUID());
            return n;
        });

        NotificationDto dto = service.create(userId, NotificationType.UPGRADE_COMPLETED,
                NotificationCategory.SUCCESS, "Upgrade completed 🎉", "Congrats!", upgradeId);

        assertThat(dto.type()).isEqualTo(NotificationType.UPGRADE_COMPLETED);
        assertThat(dto.read()).isFalse();
        assertThat(dto.relatedUpgradeId()).isEqualTo(upgradeId);
        verify(repository).save(any(Notification.class));
        // pushed to the userId-named STOMP user destination
        verify(messagingTemplate).convertAndSendToUser(eq(userId.toString()),
                eq(NotificationService.USER_QUEUE), any(NotificationDto.class));
    }

    @Test
    void markRead_flipsFlag() {
        Notification n = Notification.builder().id(UUID.randomUUID()).userId(userId)
                .type(NotificationType.REMINDER).category(NotificationCategory.REMINDER)
                .title("Reminder").read(false).build();
        when(repository.findByIdAndUserId(n.getId(), userId)).thenReturn(Optional.of(n));
        when(repository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        NotificationDto dto = service.markRead(userId, n.getId());

        assertThat(dto.read()).isTrue();
    }

    @Test
    void unreadCount_delegatesToRepository() {
        when(repository.countByUserIdAndReadFalse(userId)).thenReturn(4L);
        assertThat(service.unreadCount(userId)).isEqualTo(4L);
    }
}
