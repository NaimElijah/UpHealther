package com.healthupgrades.reflection.adapter.in.web;

import com.healthupgrades.reflection.application.ReflectionService;
import com.healthupgrades.reflection.domain.model.Reflection;
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
 * REST adapter for reflections — the periodic reviews a user writes about how an upgrade is going.
 *
 * <p>A sub-resource of an upgrade, and append-only: reflections can be written and read but never
 * edited or deleted, because a review is a record of what the user thought at the time.
 */
@RestController
@RequestMapping("/api/upgrades/{upgradeId}/reflections")
@RequiredArgsConstructor
public class ReflectionController {

    private final ReflectionService service;
    private final ReflectionWebMapper mapper;

    /**
     * Writes a reflection about an upgrade.
     *
     * @param upgradeId the upgrade being reflected on
     * @param req       the reflection; every field is optional and the date defaults to today
     * @return 201 with the stored reflection
     * @throws com.healthupgrades.common.domain.exception.ResourceNotFoundException if no such
     *         upgrade is owned by the caller (404)
     */
    @PostMapping
    public ResponseEntity<ReflectionDto> create(@AuthenticationPrincipal SecurityUser principal,
                                                 @PathVariable UUID upgradeId,
                                                 @Valid @RequestBody ReflectionRequest req) {
        Reflection created = service.create(principal.getId(), upgradeId, mapper.toDetails(req));
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toDto(created));
    }

    /**
     * Lists an upgrade's reflections, newest first.
     *
     * @param upgradeId the upgrade to read
     * @return 200 with its reflections; empty when none have been written
     * @throws com.healthupgrades.common.domain.exception.ResourceNotFoundException if no such
     *         upgrade is owned by the caller (404)
     */
    @GetMapping
    public ResponseEntity<List<ReflectionDto>> getAll(@AuthenticationPrincipal SecurityUser principal,
                                                       @PathVariable UUID upgradeId) {
        return ResponseEntity.ok(mapper.toDtos(service.getForUpgrade(principal.getId(), upgradeId)));
    }
}
