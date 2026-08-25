package com.healthupgrades.dashboard.adapter.in.web;

import com.healthupgrades.common.security.JwtAuthenticationFilter;
import com.healthupgrades.common.security.JwtTokenProvider;
import com.healthupgrades.common.security.SecurityConfig;
import com.healthupgrades.common.security.UserDetailsServiceImpl;
import com.healthupgrades.dashboard.application.port.in.DashboardQuery;
import com.healthupgrades.dashboard.application.port.in.DashboardView;
import com.healthupgrades.support.AnUpgrade;
import com.healthupgrades.support.WebSliceSupport;
import com.healthupgrades.upgrade.adapter.in.web.UpgradeWebMapper;
import com.healthupgrades.upgrade.application.port.out.UpgradeTrackingSummaryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.healthupgrades.support.WebSliceSupport.bearer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract for FR-27 — every section of the dashboard in one request.
 *
 * <p>The weekly completion rate is pinned as a percentage here as well as in the aggregation test.
 * Issue #20 was that number reaching the user a hundred times too large, and the wire is the boundary
 * the two ends of that bug disagreed across, so it is worth saying in both places.
 *
 * <p>An empty account is checked because it is the first thing a new user sees: every section has to
 * serialise as an empty array rather than as null, or the frontend maps over nothing and throws.
 */
@WebMvcTest(DashboardController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class,
        DashboardWebMapper.class, UpgradeWebMapper.class, WebSliceSupport.class})
class DashboardControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean DashboardQuery dashboardQuery;
    @MockBean UpgradeTrackingSummaryPort trackingSummaries;
    @MockBean JwtTokenProvider tokenProvider;
    @MockBean UserDetailsServiceImpl userDetailsService;

    private UUID userId;

    @BeforeEach
    void authenticate() {
        userId = UUID.randomUUID();
        WebSliceSupport.authenticateAs(tokenProvider, userDetailsService, userId);
        when(trackingSummaries.findByUpgradeIds(any())).thenReturn(Map.of());
    }

    @Test
    void GivenAPopulatedAccount_WhenTheDashboardIsRead_ThenEverySectionIsInTheOneResponse() throws Exception {
        when(dashboardQuery.getDashboard(userId)).thenReturn(new DashboardView(
                List.of(AnUpgrade.active(userId)),
                List.of(AnUpgrade.planned(userId)),
                List.of(), List.of(),
                List.of(AnUpgrade.completed(userId)),
                50.0,
                Map.of(),
                List.of(new DashboardView.AreaSummary(UUID.randomUUID(), "Sleep", 3, 1, 1))));

        mockMvc.perform(bearer(get("/api/dashboard")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeUpgrades", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.plannedUpgrades", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.recentlyCompleted", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.weeklyCompletionRate").value(50.0))
                .andExpect(jsonPath("$.areaSummary[0].areaName").value("Sleep"))
                .andExpect(jsonPath("$.areaSummary[0].totalUpgrades").value(3));

        verify(dashboardQuery).getDashboard(userId);
    }

    @Test
    void GivenAnEmptyAccount_WhenTheDashboardIsRead_ThenEverySectionSerialisesAsAnEmptyArray() throws Exception {
        when(dashboardQuery.getDashboard(userId)).thenReturn(new DashboardView(
                List.of(), List.of(), List.of(), List.of(), List.of(), 0.0, Map.of(), List.of()));

        mockMvc.perform(bearer(get("/api/dashboard")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeUpgrades", org.hamcrest.Matchers.hasSize(0)))
                .andExpect(jsonPath("$.overdueUpgrades", org.hamcrest.Matchers.hasSize(0)))
                .andExpect(jsonPath("$.areaSummary", org.hamcrest.Matchers.hasSize(0)))
                .andExpect(jsonPath("$.weeklyCompletionRate").value(0.0));
    }
}
