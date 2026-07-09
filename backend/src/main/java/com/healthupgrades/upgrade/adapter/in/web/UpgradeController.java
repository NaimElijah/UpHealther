package com.healthupgrades.upgrade.adapter.in.web;

import com.healthupgrades.upgrade.application.UpgradeService;
import com.healthupgrades.upgrade.domain.model.Difficulty;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import com.healthupgrades.upgrade.domain.model.UpgradeType;
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
 * Inbound web adapter for the upgrade lifecycle. Delegates to {@link UpgradeService} (which returns domain
 * objects) and maps the results to {@link UpgradeDto} via {@link UpgradeWebMapper}.
 */
@RestController
@RequestMapping("/api/upgrades")
@RequiredArgsConstructor
public class UpgradeController {

    private final UpgradeService service; // application service (returns domain aggregates)
    private final UpgradeWebMapper mapper; // maps domain -> UpgradeDto (with tracking config)

    /** Creates a new upgrade. */
    @PostMapping
    public ResponseEntity<UpgradeDto> create(@AuthenticationPrincipal SecurityUser principal,
                                              @Valid @RequestBody UpgradeRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toDto(service.create(principal.getId(), req)));
    }

    /** Lists the caller's upgrades, optionally filtered. */
    @GetMapping
    public ResponseEntity<List<UpgradeDto>> findAll(@AuthenticationPrincipal SecurityUser principal,
                                                     @RequestParam(required = false) UpgradeStatus status,
                                                     @RequestParam(required = false) UpgradeType type,
                                                     @RequestParam(required = false) UUID areaId,
                                                     @RequestParam(required = false) Difficulty difficulty) {
        return ResponseEntity.ok(mapper.toDtos(service.findAll(principal.getId(), status, type, areaId, difficulty)));
    }

    /** Fetches a single owned upgrade. */
    @GetMapping("/{id}")
    public ResponseEntity<UpgradeDto> findById(@AuthenticationPrincipal SecurityUser principal, @PathVariable UUID id) {
        return ResponseEntity.ok(mapper.toDto(service.getOwnedUpgrade(principal.getId(), id)));
    }

    /** Updates an owned upgrade. */
    @PutMapping("/{id}")
    public ResponseEntity<UpgradeDto> update(@AuthenticationPrincipal SecurityUser principal,
                                              @PathVariable UUID id,
                                              @Valid @RequestBody UpgradeRequest req) {
        return ResponseEntity.ok(mapper.toDto(service.update(principal.getId(), id, req)));
    }

    /** Deletes an owned upgrade. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal SecurityUser principal, @PathVariable UUID id) {
        service.delete(principal.getId(), id);
        return ResponseEntity.noContent().build();
    }

    /** Moves an owned upgrade to PLANNED. */
    @PostMapping("/{id}/plan")
    public ResponseEntity<UpgradeDto> plan(@AuthenticationPrincipal SecurityUser principal,
                                            @PathVariable UUID id,
                                            @Valid @RequestBody PlanRequest req) {
        return ResponseEntity.ok(mapper.toDto(service.plan(principal.getId(), id, req.plannedStartDate())));
    }

    /** Activates an owned upgrade. */
    @PostMapping("/{id}/activate")
    public ResponseEntity<UpgradeDto> activate(@AuthenticationPrincipal SecurityUser principal,
                                                @PathVariable UUID id,
                                                @RequestBody(required = false) ActivateRequest req) {
        return ResponseEntity.ok(mapper.toDto(service.activate(principal.getId(), id,
                req != null ? req.startDate() : null)));
    }

    /** Pauses an owned upgrade. */
    @PostMapping("/{id}/pause")
    public ResponseEntity<UpgradeDto> pause(@AuthenticationPrincipal SecurityUser principal, @PathVariable UUID id) {
        return ResponseEntity.ok(mapper.toDto(service.pause(principal.getId(), id)));
    }

    /** Completes an owned upgrade. */
    @PostMapping("/{id}/complete")
    public ResponseEntity<UpgradeDto> complete(@AuthenticationPrincipal SecurityUser principal, @PathVariable UUID id) {
        return ResponseEntity.ok(mapper.toDto(service.complete(principal.getId(), id)));
    }

    /** Abandons an owned upgrade. */
    @PostMapping("/{id}/abandon")
    public ResponseEntity<UpgradeDto> abandon(@AuthenticationPrincipal SecurityUser principal, @PathVariable UUID id) {
        return ResponseEntity.ok(mapper.toDto(service.abandon(principal.getId(), id)));
    }

    /** Reschedules an owned upgrade. */
    @PostMapping("/{id}/reschedule")
    public ResponseEntity<UpgradeDto> reschedule(@AuthenticationPrincipal SecurityUser principal,
                                                  @PathVariable UUID id,
                                                  @Valid @RequestBody RescheduleRequest req) {
        return ResponseEntity.ok(mapper.toDto(service.reschedule(principal.getId(), id, req.newDate())));
    }
}
