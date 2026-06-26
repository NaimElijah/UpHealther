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

@RestController
@RequestMapping("/api/upgrades")
@RequiredArgsConstructor
public class UpgradeController {

    private final UpgradeService service;

    @PostMapping
    public ResponseEntity<UpgradeDto> create(@AuthenticationPrincipal User user,
                                              @Valid @RequestBody UpgradeRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(user.getId(), req));
    }

    @GetMapping
    public ResponseEntity<List<UpgradeDto>> findAll(@AuthenticationPrincipal User user,
                                                     @RequestParam(required = false) UpgradeStatus status,
                                                     @RequestParam(required = false) UpgradeType type,
                                                     @RequestParam(required = false) UUID areaId,
                                                     @RequestParam(required = false) Difficulty difficulty) {
        return ResponseEntity.ok(service.findAll(user.getId(), status, type, areaId, difficulty));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UpgradeDto> findById(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        return ResponseEntity.ok(service.findById(user.getId(), id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<UpgradeDto> update(@AuthenticationPrincipal User user,
                                              @PathVariable UUID id,
                                              @Valid @RequestBody UpgradeRequest req) {
        return ResponseEntity.ok(service.update(user.getId(), id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        service.delete(user.getId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/plan")
    public ResponseEntity<UpgradeDto> plan(@AuthenticationPrincipal User user,
                                            @PathVariable UUID id,
                                            @Valid @RequestBody PlanRequest req) {
        return ResponseEntity.ok(service.plan(user.getId(), id, req.plannedStartDate()));
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<UpgradeDto> activate(@AuthenticationPrincipal User user,
                                                @PathVariable UUID id,
                                                @RequestBody(required = false) ActivateRequest req) {
        return ResponseEntity.ok(service.activate(user.getId(), id,
                req != null ? req.startDate() : null));
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<UpgradeDto> pause(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        return ResponseEntity.ok(service.pause(user.getId(), id));
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<UpgradeDto> complete(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        return ResponseEntity.ok(service.complete(user.getId(), id));
    }

    @PostMapping("/{id}/abandon")
    public ResponseEntity<UpgradeDto> abandon(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        return ResponseEntity.ok(service.abandon(user.getId(), id));
    }

    @PostMapping("/{id}/reschedule")
    public ResponseEntity<UpgradeDto> reschedule(@AuthenticationPrincipal User user,
                                                  @PathVariable UUID id,
                                                  @Valid @RequestBody RescheduleRequest req) {
        return ResponseEntity.ok(service.reschedule(user.getId(), id, req.newDate()));
    }
}
