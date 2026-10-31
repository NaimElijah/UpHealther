package com.healthupgrades.tracking.adapter.in.web;

import com.healthupgrades.common.security.SecurityUser;
import com.healthupgrades.tracking.application.TrackingService;
import com.healthupgrades.tracking.application.port.in.SavedTrackingConfig;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * REST adapter for an upgrade's tracking configuration — how its progress is measured and what counts
 * as success.
 *
 * <p>A sub-resource of an upgrade, and one per upgrade at most, which is why it is written with
 * {@code PUT} (create-or-replace) rather than {@code POST}.
 */
@RestController
@RequestMapping("/api/upgrades/{upgradeId}/tracking-config")
@RequiredArgsConstructor
public class TrackingConfigController {

    private final TrackingService trackingService;
    private final TrackingWebMapper mapper;

    /**
     * Creates or replaces an upgrade's tracking configuration.
     *
     * <p>Idempotent: repeating the call with the same body leaves the same single configuration.
     * Changing the type later does not rescore entries already logged.
     *
     * <p>The status says which happened, as RFC 9110 section 9.3.4 asks of a PUT: 201 when this save
     * created the upgrade's first configuration, 200 when it replaced one. The created resource is the
     * request's own URI, so no {@code Location} is sent.
     *
     * @param upgradeId the upgrade to configure
     * @param req       the configuration; tracking type is required
     * @return 201 with the configuration it created, or 200 with the one it replaced it with
     * @throws com.healthupgrades.common.domain.exception.ResourceNotFoundException if no such
     *         upgrade is owned by the caller (404)
     * @throws com.healthupgrades.common.domain.exception.RetryableConflictException if a concurrent
     *         request configured the upgrade first (409, which the caller can resend unchanged)
     */
    @PutMapping
    public ResponseEntity<TrackingConfigDto> saveConfig(
            @AuthenticationPrincipal SecurityUser principal,
            @PathVariable UUID upgradeId,
            @Valid @RequestBody TrackingConfigRequest req) {
        SavedTrackingConfig saved = trackingService.saveConfig(principal.getId(), upgradeId, mapper.toDetails(req));
        HttpStatus status = saved.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(mapper.toDto(saved.config()));
    }

    /**
     * Reads an upgrade's tracking configuration.
     *
     * @param upgradeId the upgrade to read
     * @return 200 with the configuration
     * @throws com.healthupgrades.common.domain.exception.ResourceNotFoundException if no such
     *         upgrade is owned by the caller (404)
     * @throws com.healthupgrades.common.domain.exception.ResourceNotFoundException if the upgrade has
     *         no tracking configuration (404)
     */
    @GetMapping
    public ResponseEntity<TrackingConfigDto> getConfig(
            @AuthenticationPrincipal SecurityUser principal, @PathVariable UUID upgradeId) {
        return ResponseEntity.ok(mapper.toDto(trackingService.getConfig(principal.getId(), upgradeId)));
    }
}
