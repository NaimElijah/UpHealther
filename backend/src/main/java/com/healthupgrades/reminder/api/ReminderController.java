package com.healthupgrades.reminder.api;

import com.healthupgrades.reminder.application.ReminderService;
import com.healthupgrades.common.security.SecurityUser;
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
    public ResponseEntity<List<ReminderDto>> list(@AuthenticationPrincipal SecurityUser principal,
                                                   @PathVariable UUID upgradeId) {
        return ResponseEntity.ok(service.getForUpgrade(principal.getId(), upgradeId));
    }

    @PostMapping("/api/upgrades/{upgradeId}/reminders")
    public ResponseEntity<ReminderDto> create(@AuthenticationPrincipal SecurityUser principal,
                                              @PathVariable UUID upgradeId,
                                              @Valid @RequestBody ReminderRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(principal.getId(), upgradeId, req));
    }

    @PutMapping("/api/reminders/{id}")
    public ResponseEntity<ReminderDto> update(@AuthenticationPrincipal SecurityUser principal,
                                              @PathVariable UUID id,
                                              @Valid @RequestBody ReminderRequest req) {
        return ResponseEntity.ok(service.update(principal.getId(), id, req));
    }

    @DeleteMapping("/api/reminders/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal SecurityUser principal, @PathVariable UUID id) {
        service.delete(principal.getId(), id);
        return ResponseEntity.noContent().build();
    }
}
