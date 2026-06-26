package com.healthupgrades.notification.api;

import com.healthupgrades.notification.application.NotificationService;
import com.healthupgrades.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService service;

    @GetMapping
    public ResponseEntity<List<NotificationDto>> list(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(service.listRecent(user.getId()));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> unreadCount(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(Map.of("count", service.unreadCount(user.getId())));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<NotificationDto> markRead(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        return ResponseEntity.ok(service.markRead(user.getId(), id));
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllRead(@AuthenticationPrincipal User user) {
        service.markAllRead(user.getId());
        return ResponseEntity.noContent().build();
    }
}
