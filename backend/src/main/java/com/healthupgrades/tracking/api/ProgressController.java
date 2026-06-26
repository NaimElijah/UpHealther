package com.healthupgrades.tracking.api;

import com.healthupgrades.tracking.application.TrackingService;
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
public class ProgressController {

    private final TrackingService trackingService;

    @PostMapping("/api/upgrades/{upgradeId}/progress")
    public ResponseEntity<ProgressDto> recordProgress(
            @AuthenticationPrincipal User user,
            @PathVariable UUID upgradeId,
            @Valid @RequestBody ProgressRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(trackingService.recordProgress(user.getId(), upgradeId, req));
    }

    @GetMapping("/api/upgrades/{upgradeId}/progress")
    public ResponseEntity<List<ProgressDto>> getProgress(
            @AuthenticationPrincipal User user,
            @PathVariable UUID upgradeId) {
        return ResponseEntity.ok(trackingService.getProgress(user.getId(), upgradeId));
    }

    @GetMapping("/api/upgrades/{upgradeId}/streak")
    public ResponseEntity<StreakDto> getStreak(
            @AuthenticationPrincipal User user,
            @PathVariable UUID upgradeId) {
        return ResponseEntity.ok(trackingService.getStreakSummary(user.getId(), upgradeId));
    }

    @GetMapping("/api/progress/today")
    public ResponseEntity<List<ProgressDto>> getToday(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(trackingService.getTodayProgress(user.getId()));
    }

    @GetMapping("/api/progress/week")
    public ResponseEntity<List<ProgressDto>> getWeek(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(trackingService.getWeekProgress(user.getId()));
    }
}
