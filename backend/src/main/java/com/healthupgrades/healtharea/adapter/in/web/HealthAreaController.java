package com.healthupgrades.healtharea.adapter.in.web;

import com.healthupgrades.healtharea.application.HealthAreaService;
import com.healthupgrades.healtharea.domain.model.HealthArea;
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
 * REST adapter for health areas — the folders a user organises upgrades into.
 *
 * <p>Every endpoint is scoped to the authenticated principal: the owner id comes from the token, never
 * from the request, so one user cannot address another's areas. An area that belongs to somebody else
 * is reported as not found (404), not forbidden.
 */
@RestController
@RequestMapping("/api/health-areas")
@RequiredArgsConstructor
public class HealthAreaController {

    private final HealthAreaService service;
    private final HealthAreaWebMapper mapper;

    /**
     * Creates a health area for the caller.
     *
     * @param req name (required), plus optional description, priority, icon and colour
     * @return 201 with the created area
     */
    @PostMapping
    public ResponseEntity<HealthAreaDto> create(@AuthenticationPrincipal SecurityUser principal,
                                                 @Valid @RequestBody HealthAreaRequest req) {
        HealthArea created = service.create(principal.getId(), mapper.toDetails(req));
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toDto(created));
    }

    /**
     * Lists the caller's health areas.
     *
     * @return 200 with every area the caller owns, in persistence order
     */
    @GetMapping
    public ResponseEntity<List<HealthAreaDto>> findAll(@AuthenticationPrincipal SecurityUser principal) {
        return ResponseEntity.ok(mapper.toDtos(service.listByUser(principal.getId())));
    }

    /**
     * Fetches one of the caller's health areas.
     *
     * @param id the area's identifier
     * @return 200 with the area
     * @throws com.healthupgrades.common.domain.exception.ResourceNotFoundException if no such area is
     *         owned by the caller (404)
     */
    @GetMapping("/{id}")
    public ResponseEntity<HealthAreaDto> findById(@AuthenticationPrincipal SecurityUser principal,
                                                   @PathVariable UUID id) {
        return ResponseEntity.ok(mapper.toDto(service.findById(principal.getId(), id)));
    }

    /**
     * Replaces the editable attributes of one of the caller's health areas.
     *
     * <p>A full replacement, not a patch: an omitted optional field is written as null.
     *
     * @param id  the area's identifier
     * @param req the new attribute values
     * @return 200 with the updated area
     * @throws com.healthupgrades.common.domain.exception.ResourceNotFoundException if no such area is
     *         owned by the caller (404)
     */
    @PutMapping("/{id}")
    public ResponseEntity<HealthAreaDto> update(@AuthenticationPrincipal SecurityUser principal,
                                                 @PathVariable UUID id,
                                                 @Valid @RequestBody HealthAreaRequest req) {
        HealthArea updated = service.update(principal.getId(), id, mapper.toDetails(req));
        return ResponseEntity.ok(mapper.toDto(updated));
    }

    /**
     * Deletes one of the caller's health areas.
     *
     * @param id the area's identifier
     * @return 204 with no body
     * @throws com.healthupgrades.common.domain.exception.ResourceNotFoundException if no such area is
     *         owned by the caller (404)
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal SecurityUser principal, @PathVariable UUID id) {
        service.delete(principal.getId(), id);
        return ResponseEntity.noContent().build();
    }
}
