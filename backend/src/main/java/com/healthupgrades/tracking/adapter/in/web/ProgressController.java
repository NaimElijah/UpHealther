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

@RestController
@RequiredArgsConstructor
public class ProgressController {

    private final TrackingService trackingService;
    private final TrackingWebMapper mapper;

    @PostMapping("/api/upgrades/{upgradeId}/progress")
    public ResponseEntity<ProgressDto> recordProgress(
            @AuthenticationPrincipal SecurityUser principal,
            @PathVariable UUID upgradeId,
            @Valid @RequestBody ProgressRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toDto(
                trackingService.recordProgress(principal.getId(), upgradeId, mapper.toDetails(req))));
    }

    @GetMapping("/api/upgrades/{upgradeId}/progress")
    public ResponseEntity<List<ProgressDto>> getProgress(
            @AuthenticationPrincipal SecurityUser principal,
            @PathVariable UUID upgradeId) {
        return ResponseEntity.ok(mapper.toDtos(trackingService.getProgress(principal.getId(), upgradeId)));
    }

    @GetMapping("/api/upgrades/{upgradeId}/streak")
    public ResponseEntity<StreakDto> getStreak(
            @AuthenticationPrincipal SecurityUser principal,
            @PathVariable UUID upgradeId) {
        return ResponseEntity.ok(mapper.toDto(trackingService.getStreakSummary(principal.getId(), upgradeId)));
    }

    @GetMapping("/api/progress/today")
    public ResponseEntity<List<ProgressDto>> getToday(@AuthenticationPrincipal SecurityUser principal) {
        return ResponseEntity.ok(mapper.toDtos(trackingService.getTodayProgress(principal.getId())));
    }

    @GetMapping("/api/progress/week")
    public ResponseEntity<List<ProgressDto>> getWeek(@AuthenticationPrincipal SecurityUser principal) {
        return ResponseEntity.ok(mapper.toDtos(trackingService.getWeekProgress(principal.getId())));
    }
}
