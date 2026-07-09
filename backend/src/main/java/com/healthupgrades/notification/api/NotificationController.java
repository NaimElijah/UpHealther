package com.healthupgrades.notification.api;

import com.healthupgrades.notification.application.NotificationService;
import com.healthupgrades.common.security.SecurityUser;
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
    public ResponseEntity<List<NotificationDto>> list(@AuthenticationPrincipal SecurityUser principal) {
        return ResponseEntity.ok(service.listRecent(principal.getId()));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> unreadCount(@AuthenticationPrincipal SecurityUser principal) {
        return ResponseEntity.ok(Map.of("count", service.unreadCount(principal.getId())));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<NotificationDto> markRead(@AuthenticationPrincipal SecurityUser principal, @PathVariable UUID id) {
        return ResponseEntity.ok(service.markRead(principal.getId(), id));
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllRead(@AuthenticationPrincipal SecurityUser principal) {
        service.markAllRead(principal.getId());
        return ResponseEntity.noContent().build();
    }
}
