package com.healthupgrades.notification.adapter.out.messaging;

import com.healthupgrades.notification.adapter.in.web.NotificationDto;
import com.healthupgrades.notification.adapter.in.web.NotificationWebMapper;
import com.healthupgrades.notification.domain.model.Notification;
import com.healthupgrades.notification.domain.model.NotificationCategory;
import com.healthupgrades.notification.domain.model.NotificationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Covers the real-time half of FR-32.
 *
 * <p>Two things here are easy to break and impossible to see from the application layer, which only
 * knows it called a port.
 *
 * <p><strong>The destination.</strong> A frame is routed to a session by the STOMP principal name, and
 * {@code JwtChannelInterceptor} sets that name to the user's id. Sending to anything else — the email,
 * say — delivers to nobody and fails silently, because a user-destination with no matching session is
 * simply dropped.
 *
 * <p><strong>The payload.</strong> The pushed frame carries the same DTO the REST endpoints return, via
 * the same mapper. That is what lets the frontend render whichever arrives first with one component;
 * pushing the entity instead would work in a test and produce a differently-shaped frame at runtime.
 *
 * <p>The real {@link NotificationWebMapper} is used rather than a mock: it is a pure mapping with no
 * collaborators, and "the same rendering as REST" is precisely the claim being made.
 */
@ExtendWith(MockitoExtension.class)
class StompNotificationPushAdapterTest {

    @Mock SimpMessagingTemplate messagingTemplate;

    private final NotificationWebMapper mapper = new NotificationWebMapper();

    private final UUID userId = UUID.randomUUID();
    private final UUID upgradeId = UUID.randomUUID();

    @Test
    void GivenANotification_WhenItIsPushed_ThenItGoesToItsOwnersUserDestination() {
        new StompNotificationPushAdapter(messagingTemplate, mapper).push(userId, aNotification());

        verify(messagingTemplate).convertAndSendToUser(
                eq(userId.toString()), eq(StompNotificationPushAdapter.USER_QUEUE), any(Object.class));
    }

    @Test
    void GivenANotification_WhenItIsPushed_ThenTheFrameCarriesTheSameShapeTheRestEndpointsReturn() {
        Notification notification = aNotification();

        new StompNotificationPushAdapter(messagingTemplate, mapper).push(userId, notification);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSendToUser(anyString(), anyString(), payload.capture());

        assertThat(payload.getValue())
                .as("the entity must not be pushed raw — the frontend reads the DTO")
                .isInstanceOf(NotificationDto.class)
                .isEqualTo(mapper.toDto(notification));
    }

    @Test
    void GivenNoReachableSession_WhenANotificationIsPushed_ThenTheCallerIsNotFailed() {
        // This runs from an afterCommit callback, so the notification is already durable and FR-32's
        // "readable afterwards regardless" is already satisfied. Letting the exception out failed the
        // caller after its transaction had committed — and in dispatchReminders that meant one
        // unreachable session cancelled everybody else's reminders for that minute.
        doThrow(new MessageDeliveryException("no session"))
                .when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any(Object.class));

        assertThatCode(() -> new StompNotificationPushAdapter(messagingTemplate, mapper)
                .push(userId, aNotification()))
                .doesNotThrowAnyException();
    }

    private Notification aNotification() {
        return Notification.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .type(NotificationType.UPGRADE_COMPLETED)
                .category(NotificationCategory.SUCCESS)
                .title("Upgrade completed 🎉")
                .message("Congrats!")
                .relatedUpgradeId(upgradeId)
                .read(false)
                .build();
    }
}
