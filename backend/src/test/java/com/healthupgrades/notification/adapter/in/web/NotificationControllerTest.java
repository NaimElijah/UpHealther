package com.healthupgrades.notification.adapter.in.web;

import com.healthupgrades.common.adapter.in.web.GlobalExceptionHandler;
import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.common.security.JwtAuthenticationFilter;
import com.healthupgrades.common.security.JwtTokenProvider;
import com.healthupgrades.common.security.SecurityConfig;
import com.healthupgrades.common.security.UserDetailsServiceImpl;
import com.healthupgrades.notification.application.NotificationService;
import com.healthupgrades.notification.domain.model.Notification;
import com.healthupgrades.notification.domain.model.NotificationCategory;
import com.healthupgrades.notification.domain.model.NotificationType;
import com.healthupgrades.support.WebSliceSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static com.healthupgrades.support.WebSliceSupport.bearer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract for the notification inbox: FR-33 (read the recent ones, an unread count, mark one
 * and mark all) and BR-15 (another user's notification is 404).
 *
 * <p>The unread count is returned as {@code {"count": n}} rather than a bare number. That wrapper is
 * what the badge reads, and a bare scalar body is both awkward to extend and awkward for a client to
 * parse — so the shape is pinned rather than left to whoever next touches the controller.
 */
@WebMvcTest(NotificationController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class,
        NotificationWebMapper.class, WebSliceSupport.class})
class NotificationControllerTest {

    private static final String BASE = "/api/notifications";

    @Autowired MockMvc mockMvc;

    @MockBean NotificationService service;
    @MockBean JwtTokenProvider tokenProvider;
    @MockBean UserDetailsServiceImpl userDetailsService;

    private UUID userId;
    private final UUID notificationId = UUID.randomUUID();

    @BeforeEach
    void authenticate() {
        userId = UUID.randomUUID();
        WebSliceSupport.authenticateAs(tokenProvider, userDetailsService, userId);
    }

    @Test
    void GivenAnInbox_WhenItIsListed_ThenItAnswers200ScopedToThePrincipal() throws Exception {
        when(service.listRecent(userId)).thenReturn(List.of(aNotification(false)));

        mockMvc.perform(bearer(get(BASE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Upgrade completed"))
                .andExpect(jsonPath("$[0].read").value(false));

        verify(service).listRecent(userId);
    }

    @Test
    void GivenUnreadNotifications_WhenTheCountIsRead_ThenItAnswersAnObjectRatherThanABareNumber()
            throws Exception {
        when(service.unreadCount(userId)).thenReturn(4L);

        mockMvc.perform(bearer(get(BASE + "/unread-count")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(4));
    }

    @Test
    void GivenAnOwnedNotification_WhenItIsMarkedRead_ThenItAnswers200WithTheFlagFlipped() throws Exception {
        when(service.markRead(userId, notificationId)).thenReturn(aNotification(true));

        mockMvc.perform(bearer(post(BASE + "/" + notificationId + "/read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));
    }

    @Test
    void GivenANotificationBelongingToSomebodyElse_WhenItIsMarkedRead_ThenItAnswers404AndNot403()
            throws Exception {
        when(service.markRead(userId, notificationId))
                .thenThrow(new ResourceNotFoundException("Notification not found: " + notificationId));

        mockMvc.perform(bearer(post(BASE + "/" + notificationId + "/read")))
                .andExpect(status().isNotFound());
    }

    @Test
    void GivenAnInbox_WhenItIsAllMarkedRead_ThenItAnswers204WithNoBody() throws Exception {
        mockMvc.perform(bearer(post(BASE + "/read-all")))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(service).markAllRead(userId);
    }

    private Notification aNotification(boolean read) {
        return Notification.builder().id(notificationId).userId(userId)
                .type(NotificationType.UPGRADE_COMPLETED).category(NotificationCategory.SUCCESS)
                .title("Upgrade completed").message("Congrats!").read(read).build();
    }
}
