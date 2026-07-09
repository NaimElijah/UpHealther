package com.healthupgrades.tracking.api;

import com.healthupgrades.tracking.application.TrackingService;
import com.healthupgrades.common.security.SecurityUser;
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
            @AuthenticationPrincipal SecurityUser principal,
            @PathVariable UUID upgradeId,
            @Valid @RequestBody TrackingConfigRequest req) {
        return ResponseEntity.ok(trackingService.saveConfig(principal.getId(), upgradeId, req));
    }

    @GetMapping
    public ResponseEntity<TrackingConfigDto> getConfig(
            @AuthenticationPrincipal SecurityUser principal,
            @PathVariable UUID upgradeId) {
        return ResponseEntity.ok(trackingService.getConfig(principal.getId(), upgradeId));
    }
}
