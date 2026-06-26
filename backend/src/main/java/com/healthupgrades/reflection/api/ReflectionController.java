package com.healthupgrades.reflection.api;

import com.healthupgrades.reflection.application.ReflectionService;
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
@RequestMapping("/api/upgrades/{upgradeId}/reflections")
@RequiredArgsConstructor
public class ReflectionController {

    private final ReflectionService service;

    @PostMapping
    public ResponseEntity<ReflectionDto> create(@AuthenticationPrincipal User user,
                                                 @PathVariable UUID upgradeId,
                                                 @Valid @RequestBody ReflectionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(user.getId(), upgradeId, req));
    }

    @GetMapping
    public ResponseEntity<List<ReflectionDto>> getAll(@AuthenticationPrincipal User user,
                                                       @PathVariable UUID upgradeId) {
        return ResponseEntity.ok(service.getForUpgrade(user.getId(), upgradeId));
    }
}
