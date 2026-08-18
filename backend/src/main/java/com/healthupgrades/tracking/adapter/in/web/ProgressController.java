package com.healthupgrades.tracking.adapter.in.web;

import com.healthupgrades.tracking.application.TrackingService;
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
 * REST adapter for logging and reading progress.
 *
 * <p>The routes are split across two prefixes rather than one, and deliberately: entries filed against a
 * single upgrade live under {@code /api/upgrades/&#123;id&#125;/…}, while the cross-upgrade views the daily
 * check-in and dashboard need live under {@code /api/progress/…}. That is why this controller declares
 * no class-level {@code @RequestMapping}.
 *
 * <p>Every endpoint is scoped to the authenticated principal, and the ones addressing a single upgrade
 * confirm ownership before touching progress.
 */
@RestController
@RequiredArgsConstructor
public class ProgressController {

    private final TrackingService trackingService;
    private final TrackingWebMapper mapper;

    /**
     * Logs a day's progress against an upgrade.
     *
     * <p>The {@code completed} flag in the body is advisory: when the upgrade has a tracking
     * configuration the server recomputes it from the target, so a client cannot mark a missed target
     * as done.
     *
     * @param upgradeId the upgrade being logged against
     * @param req       the entry; date defaults to today
     * @return 201 with the stored entry, carrying the server's completion verdict
     * @throws com.healthupgrades.common.domain.exception.ResourceNotFoundException if no such
     *         upgrade is owned by the caller (404)
     * @throws com.healthupgrades.common.domain.exception.DuplicateProgressException if an entry
     *         already exists for that upgrade and date (409)
     */
    @PostMapping("/api/upgrades/{upgradeId}/progress")
    public ResponseEntity<ProgressDto> recordProgress(
            @AuthenticationPrincipal SecurityUser principal,
            @PathVariable UUID upgradeId,
            @Valid @RequestBody ProgressRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toDto(
                trackingService.recordProgress(principal.getId(), upgradeId, mapper.toDetails(req))));
    }

    /**
     * Lists an upgrade's progress history, newest first.
     *
     * @param upgradeId the upgrade to read
     * @return 200 with every entry logged against it
     * @throws com.healthupgrades.common.domain.exception.ResourceNotFoundException if no such
     *         upgrade is owned by the caller (404)
     */
    @GetMapping("/api/upgrades/{upgradeId}/progress")
    public ResponseEntity<List<ProgressDto>> getProgress(
            @AuthenticationPrincipal SecurityUser principal,
            @PathVariable UUID upgradeId) {
        return ResponseEntity.ok(mapper.toDtos(trackingService.getProgress(principal.getId(), upgradeId)));
    }

    /**
     * Returns an upgrade's current and longest streak, both computed on the fly from its entries.
     *
     * @param upgradeId the upgrade to measure
     * @return 200 with the streak figures
     * @throws com.healthupgrades.common.domain.exception.ResourceNotFoundException if no such
     *         upgrade is owned by the caller (404)
     */
    @GetMapping("/api/upgrades/{upgradeId}/streak")
    public ResponseEntity<StreakDto> getStreak(
            @AuthenticationPrincipal SecurityUser principal,
            @PathVariable UUID upgradeId) {
        return ResponseEntity.ok(mapper.toDto(trackingService.getStreakSummary(principal.getId(), upgradeId)));
    }

    /**
     * Returns everything the caller has logged today, across all upgrades — what the daily check-in
     * page uses to tell logged from unlogged.
     *
     * @return 200 with today's entries, empty when nothing has been logged
     */
    @GetMapping("/api/progress/today")
    public ResponseEntity<List<ProgressDto>> getToday(@AuthenticationPrincipal SecurityUser principal) {
        return ResponseEntity.ok(mapper.toDtos(trackingService.getTodayProgress(principal.getId())));
    }

    /**
     * Returns the caller's entries for the last seven days, today inclusive.
     *
     * @return 200 with the week's entries
     */
    @GetMapping("/api/progress/week")
    public ResponseEntity<List<ProgressDto>> getWeek(@AuthenticationPrincipal SecurityUser principal) {
        return ResponseEntity.ok(mapper.toDtos(trackingService.getWeekProgress(principal.getId())));
    }
}
