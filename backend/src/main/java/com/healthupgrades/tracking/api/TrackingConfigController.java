package com.healthupgrades.tracking.api;

import com.healthupgrades.tracking.application.TrackingService;
import com.healthupgrades.user.domain.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/upgrades/{upgradeId}/tracking-config")
@RequiredArgsConstructor
public class TrackingConfigController {

    private final TrackingService trackingService;

    @PutMapping
    public ResponseEntity<TrackingConfigDto> saveConfig(
            @AuthenticationPrincipal User user,
            @PathVariable UUID upgradeId,
            @Valid @RequestBody TrackingConfigRequest req) {
        return ResponseEntity.ok(trackingService.saveConfig(user.getId(), upgradeId, req));
    }

    @GetMapping
    public ResponseEntity<TrackingConfigDto> getConfig(
            @AuthenticationPrincipal User user,
            @PathVariable UUID upgradeId) {
        return ResponseEntity.ok(trackingService.getConfig(user.getId(), upgradeId));
    }
}
