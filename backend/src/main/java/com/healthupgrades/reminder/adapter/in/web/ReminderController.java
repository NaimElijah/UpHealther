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

/**
 * REST adapter for reminders — the recurring nudges attached to an upgrade.
 *
 * <p>The routes are deliberately asymmetric: creating and listing are addressed through the parent
 * upgrade ({@code /api/upgrades/&#123;id&#125;/reminders}), while updating and deleting address the
 * reminder itself ({@code /api/reminders/&#123;id&#125;}), since a reminder has its own identity once
 * it exists. Ownership is established through the parent upgrade either way — a reminder has no owner
 * column of its own.
 */
@RestController
@RequiredArgsConstructor
public class ReminderController {

    private final ReminderService service;
    private final ReminderWebMapper mapper;

    /**
     * Lists an upgrade's reminders, enabled or not.
     *
     * @param upgradeId the upgrade to read
     * @return 200 with its reminders; empty when none are configured
     * @throws com.healthupgrades.common.domain.exception.ResourceNotFoundException if no such
     *         upgrade is owned by the caller (404)
     */
    @GetMapping("/api/upgrades/{upgradeId}/reminders")
    public ResponseEntity<List<ReminderDto>> list(@AuthenticationPrincipal SecurityUser principal,
                                                   @PathVariable UUID upgradeId) {
        return ResponseEntity.ok(mapper.toDtos(service.getForUpgrade(principal.getId(), upgradeId)));
    }

    /**
     * Adds a reminder to an upgrade. An upgrade may have several.
     *
     * @param upgradeId the upgrade to attach it to
     * @param req       the schedule; time is required, and an absent day list means every day
     * @return 201 with the created reminder, enabled unless the body says otherwise
     * @throws com.healthupgrades.common.domain.exception.ResourceNotFoundException if no such
     *         upgrade is owned by the caller (404)
     * @throws com.healthupgrades.common.domain.exception.BusinessRuleException if a day token is not
     *         a recognisable day (422)
     */
    @PostMapping("/api/upgrades/{upgradeId}/reminders")
    public ResponseEntity<ReminderDto> create(@AuthenticationPrincipal SecurityUser principal,
                                              @PathVariable UUID upgradeId,
                                              @Valid @RequestBody ReminderRequest req) {
        Reminder created = service.create(principal.getId(), upgradeId, mapper.toSchedule(req));
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toDto(created));
    }

    /**
     * Reschedules a reminder, and switches it on or off when the body says so.
     *
     * <p>Omitting {@code enabled} leaves the current state alone, so rescheduling a disabled reminder
     * does not silently switch it back on.
     *
     * @param id  the reminder to change
     * @param req the new schedule
     * @return 200 with the updated reminder
     * @throws com.healthupgrades.common.domain.exception.ResourceNotFoundException if the reminder
     *         does not exist, or its upgrade is not the caller's (404)
     * @throws com.healthupgrades.common.domain.exception.BusinessRuleException if a day token is not
     *         a recognisable day (422)
     */
    @PutMapping("/api/reminders/{id}")
    public ResponseEntity<ReminderDto> update(@AuthenticationPrincipal SecurityUser principal,
                                              @PathVariable UUID id,
                                              @Valid @RequestBody ReminderRequest req) {
        Reminder updated = service.update(principal.getId(), id, mapper.toSchedule(req));
        return ResponseEntity.ok(mapper.toDto(updated));
    }

    /**
     * Deletes a reminder.
     *
     * @param id the reminder to remove
     * @return 204 with no body
     * @throws com.healthupgrades.common.domain.exception.ResourceNotFoundException if the reminder
     *         does not exist, or its upgrade is not the caller's (404)
     */
    @DeleteMapping("/api/reminders/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal SecurityUser principal, @PathVariable UUID id) {
        service.delete(principal.getId(), id);
        return ResponseEntity.noContent().build();
    }
}
