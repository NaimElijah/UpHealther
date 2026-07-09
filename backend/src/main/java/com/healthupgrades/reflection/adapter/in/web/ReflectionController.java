package com.healthupgrades.reflection.adapter.in.web;

import com.healthupgrades.reflection.application.ReflectionService;
import com.healthupgrades.common.security.SecurityUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/upgrades/{upgradeId}/reflections")
@RequiredArgsConstructor
public class ReflectionController {

    private final ReflectionService service;

    @PostMapping
    public ResponseEntity<ReflectionDto> create(@AuthenticationPrincipal SecurityUser principal,
                                                 @PathVariable UUID upgradeId,
                                                 @Valid @RequestBody ReflectionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(principal.getId(), upgradeId, req));
    }

    @GetMapping
    public ResponseEntity<List<ReflectionDto>> getAll(@AuthenticationPrincipal SecurityUser principal,
                                                       @PathVariable UUID upgradeId) {
        return ResponseEntity.ok(service.getForUpgrade(principal.getId(), upgradeId));
    }
}
