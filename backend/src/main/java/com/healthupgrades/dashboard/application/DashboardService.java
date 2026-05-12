package com.healthupgrades.dashboard.application;

import com.healthupgrades.dashboard.api.DashboardDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final DashboardAggregationService aggregationService;

    public DashboardDto getDashboard(UUID userId) {
        return aggregationService.buildDashboard(userId);
    }
}
