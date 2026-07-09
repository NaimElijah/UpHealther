package com.healthupgrades.upgrade.api;

import com.healthupgrades.upgrade.application.UpgradeService;
import com.healthupgrades.upgrade.domain.Difficulty;
import com.healthupgrades.upgrade.domain.UpgradeStatus;
import com.healthupgrades.upgrade.domain.UpgradeType;
import com.healthupgrades.user.domain.User;
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
    public ResponseEntity<UpgradeDto> create(@AuthenticationPrincipal User user,
                                              @Valid @RequestBody UpgradeRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toDto(service.create(user.getId(), req)));
    }

    /** Lists the caller's upgrades, optionally filtered. */
    @GetMapping
    public ResponseEntity<List<UpgradeDto>> findAll(@AuthenticationPrincipal User user,
                                                     @RequestParam(required = false) UpgradeStatus status,
                                                     @RequestParam(required = false) UpgradeType type,
                                                     @RequestParam(required = false) UUID areaId,
                                                     @RequestParam(required = false) Difficulty difficulty) {
        return ResponseEntity.ok(mapper.toDtos(service.findAll(user.getId(), status, type, areaId, difficulty)));
    }

    /** Fetches a single owned upgrade. */
    @GetMapping("/{id}")
    public ResponseEntity<UpgradeDto> findById(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        return ResponseEntity.ok(mapper.toDto(service.getOwnedUpgrade(user.getId(), id)));
    }

    /** Updates an owned upgrade. */
    @PutMapping("/{id}")
    public ResponseEntity<UpgradeDto> update(@AuthenticationPrincipal User user,
                                              @PathVariable UUID id,
                                              @Valid @RequestBody UpgradeRequest req) {
        return ResponseEntity.ok(mapper.toDto(service.update(user.getId(), id, req)));
    }

    /** Deletes an owned upgrade. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        service.delete(user.getId(), id);
        return ResponseEntity.noContent().build();
    }

    /** Moves an owned upgrade to PLANNED. */
    @PostMapping("/{id}/plan")
    public ResponseEntity<UpgradeDto> plan(@AuthenticationPrincipal User user,
                                            @PathVariable UUID id,
                                            @Valid @RequestBody PlanRequest req) {
        return ResponseEntity.ok(mapper.toDto(service.plan(user.getId(), id, req.plannedStartDate())));
    }

    /** Activates an owned upgrade. */
    @PostMapping("/{id}/activate")
    public ResponseEntity<UpgradeDto> activate(@AuthenticationPrincipal User user,
                                                @PathVariable UUID id,
                                                @RequestBody(required = false) ActivateRequest req) {
        return ResponseEntity.ok(mapper.toDto(service.activate(user.getId(), id,
                req != null ? req.startDate() : null)));
    }

    /** Pauses an owned upgrade. */
    @PostMapping("/{id}/pause")
    public ResponseEntity<UpgradeDto> pause(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        return ResponseEntity.ok(mapper.toDto(service.pause(user.getId(), id)));
    }

    /** Completes an owned upgrade. */
    @PostMapping("/{id}/complete")
    public ResponseEntity<UpgradeDto> complete(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        return ResponseEntity.ok(mapper.toDto(service.complete(user.getId(), id)));
    }

    /** Abandons an owned upgrade. */
    @PostMapping("/{id}/abandon")
    public ResponseEntity<UpgradeDto> abandon(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        return ResponseEntity.ok(mapper.toDto(service.abandon(user.getId(), id)));
    }

    /** Reschedules an owned upgrade. */
    @PostMapping("/{id}/reschedule")
    public ResponseEntity<UpgradeDto> reschedule(@AuthenticationPrincipal User user,
                                                  @PathVariable UUID id,
                                                  @Valid @RequestBody RescheduleRequest req) {
        return ResponseEntity.ok(mapper.toDto(service.reschedule(user.getId(), id, req.newDate())));
    }
}
