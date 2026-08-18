package com.healthupgrades.notification.adapter.in.web;

import com.healthupgrades.notification.application.NotificationService;
import com.healthupgrades.common.security.SecurityUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST adapter for the notification inbox.
 *
 * <p>Read and acknowledge only — notifications are never created through the API. They are produced by
 * {@code NotificationEventListener} reacting to domain events and by {@code NotificationScheduler}, so
 * there is no endpoint a client could post one to.
 *
 * <p>This is also the fallback transport: the same payloads are pushed over STOMP as they happen, and
 * a client that was offline sees them here on its next load.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService service;
    private final NotificationWebMapper mapper;

    /**
     * Lists the caller's most recent notifications, newest first.
     *
     * <p>Capped at fifty and not paginated: the inbox is a recent-activity feed, not an archive.
     *
     * @return 200 with up to fifty notifications, read and unread alike
     */
    @GetMapping
    public ResponseEntity<List<NotificationDto>> list(@AuthenticationPrincipal SecurityUser principal) {
        return ResponseEntity.ok(mapper.toDtos(service.listRecent(principal.getId())));
    }

    /**
     * Returns how many of the caller's notifications are unread.
     *
     * <p>Wrapped in an object rather than returned as a bare number so the response stays extensible
     * without breaking clients that parse it.
     *
     * @return 200 with {@code {"count": n}}, counting every unread notification, not only the listed fifty
     */
    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> unreadCount(@AuthenticationPrincipal SecurityUser principal) {
        return ResponseEntity.ok(Map.of("count", service.unreadCount(principal.getId())));
    }

    /**
     * Marks one of the caller's notifications as read.
     *
     * @param id the notification to acknowledge
     * @return 200 with the updated notification
     * @throws com.healthupgrades.common.domain.exception.ResourceNotFoundException if no such
     *         notification is owned by the caller (404)
     */
    @PostMapping("/{id}/read")
    public ResponseEntity<NotificationDto> markRead(@AuthenticationPrincipal SecurityUser principal, @PathVariable UUID id) {
        return ResponseEntity.ok(mapper.toDto(service.markRead(principal.getId(), id)));
    }

    /**
     * Marks every one of the caller's unread notifications as read in a single update.
     *
     * @return 204 with no body
     */
    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllRead(@AuthenticationPrincipal SecurityUser principal) {
        service.markAllRead(principal.getId());
        return ResponseEntity.noContent().build();
    }
}
