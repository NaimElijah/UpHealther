package com.healthupgrades.notification.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.healthupgrades.common.websocket.JwtChannelInterceptor;
import com.healthupgrades.notification.adapter.in.web.NotificationDto;
import com.healthupgrades.notification.adapter.in.web.NotificationWebMapper;
import com.healthupgrades.notification.domain.model.Notification;
import com.healthupgrades.notification.domain.model.NotificationCategory;
import com.healthupgrades.notification.domain.model.NotificationType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Covers the real-time half of FR-32, and NFR-26: a push that cannot be delivered is reported, not
 * raised.
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

    @Mock
    SimpMessagingTemplate messagingTemplate;

    private final NotificationWebMapper mapper = new NotificationWebMapper();

    private final UUID userId = UUID.randomUUID();
    private final UUID upgradeId = UUID.randomUUID();

    @Test
    void GivenANotification_WhenItIsPushed_ThenItGoesToItsOwnersUserDestination() {
        new StompNotificationPushAdapter(messagingTemplate, mapper).push(userId, aNotification());

        verify(messagingTemplate)
                .convertAndSendToUser(
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
                .when(messagingTemplate)
                .convertAndSendToUser(anyString(), anyString(), any(Object.class));

        assertThatCode(() -> new StompNotificationPushAdapter(messagingTemplate, mapper).push(userId, aNotification()))
                .doesNotThrowAnyException();
    }

    @Test
    void GivenNoReachableSession_WhenANotificationIsPushed_ThenOneWarningNamesItByIds() {
        // NFR-26: degraded, not silent. The ids are what find the notification nobody received; the
        // title and message are the user's own words and stay out of the line (NFR-6).
        doThrow(new MessageDeliveryException("no session"))
                .when(messagingTemplate)
                .convertAndSendToUser(anyString(), anyString(), any(Object.class));
        Notification notification = aNotification();

        List<ILoggingEvent> logged = logsFromAdapter(
                () -> new StompNotificationPushAdapter(messagingTemplate, mapper).push(userId, notification));

        assertThat(logged).singleElement().satisfies(warning -> {
            assertThat(warning.getLevel()).isEqualTo(Level.WARN);
            assertThat(warning.getFormattedMessage())
                    .contains(
                            "event=notification.push-failed",
                            "userId=" + userId,
                            "notificationId=" + notification.getId())
                    .doesNotContain(notification.getTitle(), notification.getMessage());
        });
    }

    @Test
    void GivenAPushThatFailsWithACause_WhenItIsLogged_ThenTheLineNamesBothTypesAndAttachesNoThrowable() {
        // S5.C8: the line repeats per notification, so it names the failure by type, never by message
        // or stack trace (NFR-6). The root cause's type is what tells a closed socket from a full broker.
        doThrow(new MessagingException(
                        "no session", new IllegalStateException("socket closed for someone@example.com")))
                .when(messagingTemplate)
                .convertAndSendToUser(anyString(), anyString(), any(Object.class));

        List<ILoggingEvent> logged = logsFromAdapter(
                () -> new StompNotificationPushAdapter(messagingTemplate, mapper).push(userId, aNotification()));

        assertThat(logged).singleElement().satisfies(warning -> {
            assertThat(warning.getThrowableProxy()).isNull();
            assertThat(warning.getFormattedMessage())
                    .contains("error=MessagingException", "rootCause=IllegalStateException")
                    .doesNotContain("socket closed", "someone@example.com", "no session");
        });
    }

    /** Collects what {@link StompNotificationPushAdapter} logs while {@code push} runs. */
    private static List<ILoggingEvent> logsFromAdapter(Runnable push) {
        Logger logger = (Logger) LoggerFactory.getLogger(StompNotificationPushAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            push.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list;
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

    @Test
    void GivenTheQueueThisAdapterPushesTo_WhenASubscriberNamesIt_ThenItIsTheDestinationTheInterceptorAllows() {
        // Two literals, in two modules that may not import each other: this adapter's queue and the one
        // subscription JwtChannelInterceptor lets through. Changing either alone is silent — the push
        // still succeeds, and lands in a queue nobody is allowed to subscribe to.
        assertThat("/user" + StompNotificationPushAdapter.USER_QUEUE)
                .isEqualTo(JwtChannelInterceptor.ALLOWED_SUBSCRIPTION);
    }
}
