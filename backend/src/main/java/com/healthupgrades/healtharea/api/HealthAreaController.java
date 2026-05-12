package com.healthupgrades.healtharea.api;

import com.healthupgrades.healtharea.application.HealthAreaService;
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
@RequestMapping("/api/health-areas")
@RequiredArgsConstructor
public class HealthAreaController {

    private final HealthAreaService service;

    @PostMapping
    public ResponseEntity<HealthAreaDto> create(@AuthenticationPrincipal User user,
                                                 @Valid @RequestBody HealthAreaRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(user.getId(), req));
    }

    @GetMapping
    public ResponseEntity<List<HealthAreaDto>> findAll(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(service.findAll(user.getId()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<HealthAreaDto> findById(@AuthenticationPrincipal User user,
                                                   @PathVariable UUID id) {
        return ResponseEntity.ok(service.findById(user.getId(), id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<HealthAreaDto> update(@AuthenticationPrincipal User user,
                                                 @PathVariable UUID id,
                                                 @Valid @RequestBody HealthAreaRequest req) {
        return ResponseEntity.ok(service.update(user.getId(), id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        service.delete(user.getId(), id);
        return ResponseEntity.noContent().build();
    }
}
