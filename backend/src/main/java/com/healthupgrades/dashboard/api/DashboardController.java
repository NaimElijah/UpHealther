package com.healthupgrades.dashboard.api;

import com.healthupgrades.dashboard.application.port.in.DashboardQuery;
import com.healthupgrades.common.security.SecurityUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound web adapter for the dashboard. Drives the {@link DashboardQuery} inbound port and maps the
 * resulting domain {@code DashboardView} into the {@link DashboardDto} response via {@link DashboardWebMapper}.
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardQuery dashboardQuery; // application aggregation port
    private final DashboardWebMapper mapper; // domain view -> HTTP DTO

    /** Returns the caller's dashboard. */
    @GetMapping
    public ResponseEntity<DashboardDto> getDashboard(@AuthenticationPrincipal SecurityUser principal) {
        return ResponseEntity.ok(mapper.toDto(dashboardQuery.getDashboard(principal.getId())));
    }
}
