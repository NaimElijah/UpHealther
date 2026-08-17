package com.healthupgrades.reminder.adapter.in.web;

import com.healthupgrades.reminder.application.ReminderService;
import com.healthupgrades.reminder.domain.model.Reminder;
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
    private final ReminderWebMapper mapper;

    @GetMapping("/api/upgrades/{upgradeId}/reminders")
    public ResponseEntity<List<ReminderDto>> list(@AuthenticationPrincipal SecurityUser principal,
                                                   @PathVariable UUID upgradeId) {
        return ResponseEntity.ok(mapper.toDtos(service.getForUpgrade(principal.getId(), upgradeId)));
    }

    @PostMapping("/api/upgrades/{upgradeId}/reminders")
    public ResponseEntity<ReminderDto> create(@AuthenticationPrincipal SecurityUser principal,
                                              @PathVariable UUID upgradeId,
                                              @Valid @RequestBody ReminderRequest req) {
        Reminder created = service.create(principal.getId(), upgradeId, mapper.toSchedule(req));
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toDto(created));
    }

    @PutMapping("/api/reminders/{id}")
    public ResponseEntity<ReminderDto> update(@AuthenticationPrincipal SecurityUser principal,
                                              @PathVariable UUID id,
                                              @Valid @RequestBody ReminderRequest req) {
        Reminder updated = service.update(principal.getId(), id, mapper.toSchedule(req));
        return ResponseEntity.ok(mapper.toDto(updated));
    }

    @DeleteMapping("/api/reminders/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal SecurityUser principal, @PathVariable UUID id) {
        service.delete(principal.getId(), id);
        return ResponseEntity.noContent().build();
    }
}
