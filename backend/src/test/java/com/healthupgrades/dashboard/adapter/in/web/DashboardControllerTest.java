package com.healthupgrades.dashboard.adapter.in.web;

import static com.healthupgrades.support.WebSliceSupport.bearer;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.healthupgrades.common.security.BearerTokenAuthenticator;
import com.healthupgrades.common.security.JwtAuthenticationFilter;
import com.healthupgrades.common.security.SecurityConfig;
import com.healthupgrades.common.security.UserDetailsServiceImpl;
import com.healthupgrades.dashboard.application.port.in.DashboardQuery;
import com.healthupgrades.dashboard.application.port.in.DashboardView;
import com.healthupgrades.support.AnUpgrade;
import com.healthupgrades.support.WebSliceSupport;
import com.healthupgrades.upgrade.adapter.in.web.UpgradeWebMapper;
import com.healthupgrades.upgrade.application.port.out.UpgradeTrackingSummaryPort;
import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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
@Import({
    SecurityConfig.class,
    JwtAuthenticationFilter.class,
    DashboardWebMapper.class,
    UpgradeWebMapper.class,
    WebSliceSupport.class
})
class DashboardControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DashboardQuery dashboardQuery;

    @MockitoBean
    UpgradeTrackingSummaryPort trackingSummaries;

    @MockitoBean
    BearerTokenAuthenticator authenticator;

    @MockitoBean
    UserDetailsServiceImpl userDetailsService;

    private UUID userId;

    @BeforeEach
    void authenticate() {
        userId = UUID.randomUUID();
        WebSliceSupport.authenticateAs(authenticator, userId);
        when(trackingSummaries.findByUpgradeIds(any())).thenReturn(Map.of());
    }

    @Test
    void GivenAPopulatedAccount_WhenTheDashboardIsRead_ThenEverySectionIsInTheOneResponse() throws Exception {
        when(dashboardQuery.getDashboard(userId))
                .thenReturn(new DashboardView(
                        List.of(AnUpgrade.active(userId)),
                        List.of(AnUpgrade.planned(userId)),
                        List.of(),
                        List.of(),
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
    void GivenEveryBucketHoldsADifferentUpgrade_WhenTheDashboardIsRead_ThenEachLandsInItsOwnSection() throws Exception {
        // One distinct upgrade per bucket, a streak, and three different counts, so a section filled from
        // the wrong bucket or a count put into the wrong field changes what the response says.
        HealthUpgrade active = AnUpgrade.active(userId);
        HealthUpgrade planned = AnUpgrade.planned(userId);
        HealthUpgrade today = AnUpgrade.active(userId);
        HealthUpgrade overdue = AnUpgrade.active(userId);
        HealthUpgrade completed = AnUpgrade.completed(userId);
        UUID areaId = UUID.randomUUID();
        when(dashboardQuery.getDashboard(userId))
                .thenReturn(new DashboardView(
                        List.of(active),
                        List.of(planned),
                        List.of(today),
                        List.of(overdue),
                        List.of(completed),
                        50.0,
                        Map.of(active.getId(), 9),
                        List.of(new DashboardView.AreaSummary(areaId, "Sleep", 6, 2, 1))));

        mockMvc.perform(bearer(get("/api/dashboard")))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.activeUpgrades[*].id", contains(active.getId().toString())))
                .andExpect(jsonPath(
                        "$.plannedUpgrades[*].id", contains(planned.getId().toString())))
                .andExpect(
                        jsonPath("$.todayUpgrades[*].id", contains(today.getId().toString())))
                .andExpect(jsonPath(
                        "$.overdueUpgrades[*].id", contains(overdue.getId().toString())))
                .andExpect(jsonPath(
                        "$.recentlyCompleted[*].id", contains(completed.getId().toString())))
                .andExpect(jsonPath("$.streaks['" + active.getId() + "']").value(9))
                .andExpect(jsonPath("$.areaSummary[0].areaId").value(areaId.toString()))
                .andExpect(jsonPath("$.areaSummary[0].totalUpgrades").value(6))
                .andExpect(jsonPath("$.areaSummary[0].activeCount").value(2))
                .andExpect(jsonPath("$.areaSummary[0].completedCount").value(1));
    }

    @Test
    void GivenAnEmptyAccount_WhenTheDashboardIsRead_ThenEverySectionSerialisesAsAnEmptyArray() throws Exception {
        when(dashboardQuery.getDashboard(userId))
                .thenReturn(new DashboardView(
                        List.of(), List.of(), List.of(), List.of(), List.of(), 0.0, Map.of(), List.of()));

        mockMvc.perform(bearer(get("/api/dashboard")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeUpgrades", org.hamcrest.Matchers.hasSize(0)))
                .andExpect(jsonPath("$.overdueUpgrades", org.hamcrest.Matchers.hasSize(0)))
                .andExpect(jsonPath("$.areaSummary", org.hamcrest.Matchers.hasSize(0)))
                .andExpect(jsonPath("$.weeklyCompletionRate").value(0.0));
    }
}
