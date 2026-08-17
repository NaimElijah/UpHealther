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

@RestController
@RequestMapping("/api/health-areas")
@RequiredArgsConstructor
public class HealthAreaController {

    private final HealthAreaService service;
    private final HealthAreaWebMapper mapper;

    @PostMapping
    public ResponseEntity<HealthAreaDto> create(@AuthenticationPrincipal SecurityUser principal,
                                                 @Valid @RequestBody HealthAreaRequest req) {
        HealthArea created = service.create(principal.getId(), mapper.toDetails(req));
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toDto(created));
    }

    @GetMapping
    public ResponseEntity<List<HealthAreaDto>> findAll(@AuthenticationPrincipal SecurityUser principal) {
        return ResponseEntity.ok(mapper.toDtos(service.findAll(principal.getId())));
    }

    @GetMapping("/{id}")
    public ResponseEntity<HealthAreaDto> findById(@AuthenticationPrincipal SecurityUser principal,
                                                   @PathVariable UUID id) {
        return ResponseEntity.ok(mapper.toDto(service.findById(principal.getId(), id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<HealthAreaDto> update(@AuthenticationPrincipal SecurityUser principal,
                                                 @PathVariable UUID id,
                                                 @Valid @RequestBody HealthAreaRequest req) {
        HealthArea updated = service.update(principal.getId(), id, mapper.toDetails(req));
        return ResponseEntity.ok(mapper.toDto(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal SecurityUser principal, @PathVariable UUID id) {
        service.delete(principal.getId(), id);
        return ResponseEntity.noContent().build();
    }
}
