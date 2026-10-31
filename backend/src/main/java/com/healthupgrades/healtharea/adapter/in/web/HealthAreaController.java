package com.healthupgrades.healtharea.adapter.in.web;

import com.healthupgrades.common.security.SecurityUser;
import com.healthupgrades.healtharea.application.HealthAreaService;
import com.healthupgrades.healtharea.domain.model.HealthArea;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * REST adapter for health areas — the folders a user organises upgrades into.
 *
 * <p>Every endpoint is scoped to the authenticated principal: the owner id comes from the token, never
 * from the request, so one user cannot address another's areas. An area that belongs to somebody else
 * is reported as not found (404), not forbidden.
 */
@RestController
@RequestMapping(HealthAreaController.PATH)
@RequiredArgsConstructor
public class HealthAreaController {

    /** The collection's route; an area's own route is this followed by its id. */
    static final String PATH = "/api/health-areas";

    private final HealthAreaService service;
    private final HealthAreaWebMapper mapper;

    /**
     * Creates a health area for the caller.
     *
     * <p>{@code Location} is a relative reference (RFC 9110 section 10.2.2), so it stays right behind a
     * proxy that rewrites the host or the scheme.
     *
     * @param req name (required), plus optional description, priority, icon and colour
     * @return 201 with the created area, and a {@code Location} naming the route that reads it back
     */
    @PostMapping
    public ResponseEntity<HealthAreaDto> create(
            @AuthenticationPrincipal SecurityUser principal, @Valid @RequestBody HealthAreaRequest req) {
        HealthArea created = service.create(principal.getId(), mapper.toDetails(req));
        URI location = UriComponentsBuilder.fromPath(PATH)
                .pathSegment("{id}")
                .buildAndExpand(created.getId())
                .toUri();
        return ResponseEntity.created(location).body(mapper.toDto(created));
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
    public ResponseEntity<HealthAreaDto> findById(
            @AuthenticationPrincipal SecurityUser principal, @PathVariable UUID id) {
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
    public ResponseEntity<HealthAreaDto> update(
            @AuthenticationPrincipal SecurityUser principal,
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
