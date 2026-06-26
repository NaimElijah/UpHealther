package com.healthupgrades.reminder.api;

import com.healthupgrades.reminder.application.ReminderService;
import com.healthupgrades.user.domain.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ReminderController {

    private final ReminderService service;

    @GetMapping("/api/upgrades/{upgradeId}/reminders")
    public ResponseEntity<List<ReminderDto>> list(@AuthenticationPrincipal User user,
                                                   @PathVariable UUID upgradeId) {
        return ResponseEntity.ok(service.getForUpgrade(user.getId(), upgradeId));
    }

    @PostMapping("/api/upgrades/{upgradeId}/reminders")
    public ResponseEntity<ReminderDto> create(@AuthenticationPrincipal User user,
                                              @PathVariable UUID upgradeId,
                                              @Valid @RequestBody ReminderRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(user.getId(), upgradeId, req));
    }

    @PutMapping("/api/reminders/{id}")
    public ResponseEntity<ReminderDto> update(@AuthenticationPrincipal User user,
                                              @PathVariable UUID id,
                                              @Valid @RequestBody ReminderRequest req) {
        return ResponseEntity.ok(service.update(user.getId(), id, req));
    }

    @DeleteMapping("/api/reminders/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        service.delete(user.getId(), id);
        return ResponseEntity.noContent().build();
    }
}
