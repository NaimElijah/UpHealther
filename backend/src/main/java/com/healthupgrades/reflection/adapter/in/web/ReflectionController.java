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

@RestController
@RequestMapping("/api/upgrades/{upgradeId}/reflections")
@RequiredArgsConstructor
public class ReflectionController {

    private final ReflectionService service;
    private final ReflectionWebMapper mapper;

    @PostMapping
    public ResponseEntity<ReflectionDto> create(@AuthenticationPrincipal SecurityUser principal,
                                                 @PathVariable UUID upgradeId,
                                                 @Valid @RequestBody ReflectionRequest req) {
        Reflection created = service.create(principal.getId(), upgradeId, mapper.toDetails(req));
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toDto(created));
    }

    @GetMapping
    public ResponseEntity<List<ReflectionDto>> getAll(@AuthenticationPrincipal SecurityUser principal,
                                                       @PathVariable UUID upgradeId) {
        return ResponseEntity.ok(mapper.toDtos(service.getForUpgrade(principal.getId(), upgradeId)));
    }
}
