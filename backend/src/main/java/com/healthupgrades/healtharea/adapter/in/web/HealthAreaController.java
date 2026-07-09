package com.healthupgrades.healtharea.adapter.in.web;

import com.healthupgrades.healtharea.application.HealthAreaService;
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
@RequestMapping("/api/health-areas")
@RequiredArgsConstructor
public class HealthAreaController {

    private final HealthAreaService service;

    @PostMapping
    public ResponseEntity<HealthAreaDto> create(@AuthenticationPrincipal SecurityUser principal,
                                                 @Valid @RequestBody HealthAreaRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(principal.getId(), req));
    }

    @GetMapping
    public ResponseEntity<List<HealthAreaDto>> findAll(@AuthenticationPrincipal SecurityUser principal) {
        return ResponseEntity.ok(service.findAll(principal.getId()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<HealthAreaDto> findById(@AuthenticationPrincipal SecurityUser principal,
                                                   @PathVariable UUID id) {
        return ResponseEntity.ok(service.findById(principal.getId(), id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<HealthAreaDto> update(@AuthenticationPrincipal SecurityUser principal,
                                                 @PathVariable UUID id,
                                                 @Valid @RequestBody HealthAreaRequest req) {
        return ResponseEntity.ok(service.update(principal.getId(), id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal SecurityUser principal, @PathVariable UUID id) {
        service.delete(principal.getId(), id);
        return ResponseEntity.noContent().build();
    }
}
