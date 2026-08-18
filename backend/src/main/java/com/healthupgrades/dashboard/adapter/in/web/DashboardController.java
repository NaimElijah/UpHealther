package com.healthupgrades.dashboard.adapter.in.web;

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

    /**
     * Builds and returns the caller's dashboard.
     *
     * <p>Read-only and computed per request: nothing here is cached or stored, so the figures are always
     * consistent with the data behind them.
     *
     * @param principal the authenticated caller, whose id scopes every section
     * @return 200 with the dashboard; the buckets are empty rather than absent for a new account
     */
    @GetMapping
    public ResponseEntity<DashboardDto> getDashboard(@AuthenticationPrincipal SecurityUser principal) {
        return ResponseEntity.ok(mapper.toDto(dashboardQuery.getDashboard(principal.getId())));
    }
}
