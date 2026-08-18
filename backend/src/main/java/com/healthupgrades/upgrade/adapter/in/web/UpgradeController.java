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
 * REST adapter for the upgrade lifecycle — the API's central resource.
 *
 * <p>Beyond the CRUD endpoints, each lifecycle transition is its own {@code POST} sub-resource
 * ({@code /plan}, {@code /activate}, {@code /pause}, {@code /complete}, {@code /abandon},
 * {@code /reschedule}) rather than a status field a client may set. The transitions are guarded by the
 * aggregate, so naming them keeps an illegal move a 422 with a reason instead of a silently accepted
 * write.
 *
 * <p>Every endpoint is scoped to the authenticated principal; an upgrade owned by somebody else is
 * reported as not found. Delegates to {@link UpgradeService}, which returns domain objects, and maps
 * them with {@link UpgradeWebMapper}.
 */
@RestController
@RequestMapping("/api/upgrades")
@RequiredArgsConstructor
public class UpgradeController {

    private final UpgradeService service; // application service (returns domain aggregates)
    private final UpgradeWebMapper mapper; // maps domain -> UpgradeDto (with tracking config)

    /**
     * Creates an upgrade, which always starts as an IDEA.
     *
     * @param req title and type are required; the rest is optional
     * @return 201 with the created upgrade
     */
    @PostMapping
    public ResponseEntity<UpgradeDto> create(@AuthenticationPrincipal SecurityUser principal,
                                              @Valid @RequestBody UpgradeRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(mapper.toDto(service.create(principal.getId(), mapper.toDetails(req))));
    }

    /**
     * Lists the caller's upgrades, narrowed by at most one filter.
     *
     * <p>The query parameters are alternatives, not a conjunction: the first one supplied wins, in the
     * order status, type, areaId, difficulty.
     *
     * @param status     lifecycle filter, optional
     * @param type       type filter, optional
     * @param areaId     health-area filter, optional
     * @param difficulty difficulty filter, optional
     * @return 200 with the matching upgrades, each carrying its tracking configuration
     */
    @GetMapping
    public ResponseEntity<List<UpgradeDto>> findAll(@AuthenticationPrincipal SecurityUser principal,
                                                     @RequestParam(required = false) UpgradeStatus status,
                                                     @RequestParam(required = false) UpgradeType type,
                                                     @RequestParam(required = false) UUID areaId,
                                                     @RequestParam(required = false) Difficulty difficulty) {
        return ResponseEntity.ok(mapper.toDtos(service.findAll(principal.getId(), status, type, areaId, difficulty)));
    }

    /**
     * Fetches one of the caller's upgrades.
     *
     * @param id the upgrade's identifier
     * @return 200 with the upgrade and its tracking configuration
     * @throws com.healthupgrades.common.domain.exception.ResourceNotFoundException if no such
     *         upgrade is owned by the caller (404)
     */
    @GetMapping("/{id}")
    public ResponseEntity<UpgradeDto> findById(@AuthenticationPrincipal SecurityUser principal, @PathVariable UUID id) {
        return ResponseEntity.ok(mapper.toDto(service.getOwnedUpgrade(principal.getId(), id)));
    }

    /**
     * Replaces the editable attributes of one of the caller's upgrades.
     *
     * <p>Status is not editable here — use the transition endpoints. Changing difficulty to HARD is
     * subject to the concurrent-HARD limit when the upgrade is running.
     *
     * @param id  the upgrade's identifier
     * @param req the replacement attributes
     * @return 200 with the updated upgrade
     * @throws com.healthupgrades.common.domain.exception.ResourceNotFoundException if no such
     *         upgrade is owned by the caller (404)
     * @throws com.healthupgrades.common.domain.exception.BusinessRuleException if the change would exceed the
     *         concurrent-HARD limit (422)
     */
    @PutMapping("/{id}")
    public ResponseEntity<UpgradeDto> update(@AuthenticationPrincipal SecurityUser principal,
                                              @PathVariable UUID id,
                                              @Valid @RequestBody UpgradeRequest req) {
        return ResponseEntity.ok(mapper.toDto(service.update(principal.getId(), id, mapper.toDetails(req))));
    }

    /**
     * Deletes one of the caller's upgrades, along with nothing else — progress entries and
     * reflections filed against it are not cascaded.
     *
     * @param id the upgrade's identifier
     * @return 204 with no body
     * @throws com.healthupgrades.common.domain.exception.ResourceNotFoundException if no such
     *         upgrade is owned by the caller (404)
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal SecurityUser principal, @PathVariable UUID id) {
        service.delete(principal.getId(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Commits an idea to a start date: IDEA to PLANNED.
     *
     * @param id the upgrade's identifier
     * @param req the intended start date, required
     * @return 200 with the updated upgrade
     * @throws com.healthupgrades.common.domain.exception.ResourceNotFoundException if no such
     *         upgrade is owned by the caller (404)
     * @throws com.healthupgrades.common.domain.exception.BusinessRuleException if the upgrade is not an IDEA (422)
     */
    @PostMapping("/{id}/plan")
    public ResponseEntity<UpgradeDto> plan(@AuthenticationPrincipal SecurityUser principal,
                                            @PathVariable UUID id,
                                            @Valid @RequestBody PlanRequest req) {
        return ResponseEntity.ok(mapper.toDto(service.plan(principal.getId(), id, req.plannedStartDate())));
    }

    /**
     * Starts or resumes an upgrade: PLANNED or PAUSED to ACTIVE.
     *
     * @param id the upgrade's identifier
     * @param req optional body carrying the start date; today when the body is absent
     * @return 200 with the updated upgrade
     * @throws com.healthupgrades.common.domain.exception.ResourceNotFoundException if no such
     *         upgrade is owned by the caller (404)
     * @throws com.healthupgrades.common.domain.exception.BusinessRuleException if the upgrade is not PLANNED or PAUSED,
     *         or activating it would exceed the concurrent-HARD limit (422)
     */
    @PostMapping("/{id}/activate")
    public ResponseEntity<UpgradeDto> activate(@AuthenticationPrincipal SecurityUser principal,
                                                @PathVariable UUID id,
                                                @RequestBody(required = false) ActivateRequest req) {
        return ResponseEntity.ok(mapper.toDto(service.activate(principal.getId(), id,
                req != null ? req.startDate() : null)));
    }

    /**
     * Suspends a running upgrade: ACTIVE to PAUSED, releasing any HARD slot it held.
     *
     * @param id the upgrade's identifier
     * @return 200 with the updated upgrade
     * @throws com.healthupgrades.common.domain.exception.ResourceNotFoundException if no such
     *         upgrade is owned by the caller (404)
     * @throws com.healthupgrades.common.domain.exception.BusinessRuleException if the upgrade is not ACTIVE (422)
     */
    @PostMapping("/{id}/pause")
    public ResponseEntity<UpgradeDto> pause(@AuthenticationPrincipal SecurityUser principal, @PathVariable UUID id) {
        return ResponseEntity.ok(mapper.toDto(service.pause(principal.getId(), id)));
    }

    /**
     * Finishes a running upgrade: ACTIVE to COMPLETED, which is terminal.
     *
     * @param id the upgrade's identifier
     * @return 200 with the updated upgrade
     * @throws com.healthupgrades.common.domain.exception.ResourceNotFoundException if no such
     *         upgrade is owned by the caller (404)
     * @throws com.healthupgrades.common.domain.exception.BusinessRuleException if the upgrade is not ACTIVE (422)
     */
    @PostMapping("/{id}/complete")
    public ResponseEntity<UpgradeDto> complete(@AuthenticationPrincipal SecurityUser principal, @PathVariable UUID id) {
        return ResponseEntity.ok(mapper.toDto(service.complete(principal.getId(), id)));
    }

    /**
     * Gives an upgrade up. Reversible by rescheduling it.
     *
     * @param id the upgrade's identifier
     * @return 200 with the updated upgrade
     * @throws com.healthupgrades.common.domain.exception.ResourceNotFoundException if no such
     *         upgrade is owned by the caller (404)
     * @throws com.healthupgrades.common.domain.exception.BusinessRuleException if it is already COMPLETED or
     *         ABANDONED (422)
     */
    @PostMapping("/{id}/abandon")
    public ResponseEntity<UpgradeDto> abandon(@AuthenticationPrincipal SecurityUser principal, @PathVariable UUID id) {
        return ResponseEntity.ok(mapper.toDto(service.abandon(principal.getId(), id)));
    }

    /**
     * Moves the planned start date, reviving an ABANDONED upgrade into PLANNED.
     *
     * @param id the upgrade's identifier
     * @param req the new planned start date, required
     * @return 200 with the updated upgrade
     * @throws com.healthupgrades.common.domain.exception.ResourceNotFoundException if no such
     *         upgrade is owned by the caller (404)
     * @throws com.healthupgrades.common.domain.exception.BusinessRuleException if the upgrade is COMPLETED (422)
     */
    @PostMapping("/{id}/reschedule")
    public ResponseEntity<UpgradeDto> reschedule(@AuthenticationPrincipal SecurityUser principal,
                                                  @PathVariable UUID id,
                                                  @Valid @RequestBody RescheduleRequest req) {
        return ResponseEntity.ok(mapper.toDto(service.reschedule(principal.getId(), id, req.newDate())));
    }
}
