package com.healthupgrades.tracking.adapter.in.web;

import com.healthupgrades.common.domain.exception.DuplicateProgressException;
import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.common.security.JwtAuthenticationFilter;
import com.healthupgrades.common.security.JwtTokenProvider;
import com.healthupgrades.common.security.SecurityConfig;
import com.healthupgrades.common.security.UserDetailsServiceImpl;
import com.healthupgrades.support.AProgressEntry;
import com.healthupgrades.support.WebSliceSupport;
import com.healthupgrades.tracking.application.TrackingService;
import com.healthupgrades.tracking.application.port.in.StreakSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.healthupgrades.support.WebSliceSupport.bearer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract for logging and reading progress: FR-18, FR-20, FR-21, FR-22 and BR-6.
 *
 * <p>BR-6 is the one that matters most here. A second entry for the same upgrade and day is a
 * <strong>409</strong>, not a 400 and not a silent overwrite: the client is being told the day is
 * already logged, which is a state it can resolve, rather than that its request was malformed.
 */
@WebMvcTest(ProgressController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class,
        TrackingWebMapper.class, WebSliceSupport.class})
class ProgressControllerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 15);

    @Autowired MockMvc mockMvc;

    @MockBean TrackingService trackingService;
    @MockBean JwtTokenProvider tokenProvider;
    @MockBean UserDetailsServiceImpl userDetailsService;

    private UUID userId;
    private final UUID upgradeId = UUID.randomUUID();
    private String progressPath;

    @BeforeEach
    void authenticate() {
        userId = UUID.randomUUID();
        progressPath = "/api/upgrades/" + upgradeId + "/progress";
        WebSliceSupport.authenticateAs(tokenProvider, userDetailsService, userId);
    }

    @Test
    void GivenAValidEntry_WhenProgressIsLogged_ThenItAnswers201WithTheServersVerdict() throws Exception {
        when(trackingService.recordProgress(eq(userId), eq(upgradeId), any()))
                .thenReturn(AProgressEntry.completedOn(upgradeId, userId, DAY, true));

        mockMvc.perform(bearer(post(progressPath)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-03-15\",\"completed\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.date").value("2026-03-15"));
    }

    @Test
    void GivenTheDayIsAlreadyLogged_WhenProgressIsLoggedAgain_ThenItAnswers409AndNot400() throws Exception {
        // BR-6. A conflict is a state the client can act on; a 400 would say the request was wrong.
        when(trackingService.recordProgress(eq(userId), eq(upgradeId), any()))
                .thenThrow(new DuplicateProgressException("Progress already recorded for date: " + DAY));

        mockMvc.perform(bearer(post(progressPath)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-03-15\",\"completed\":true}"))
                .andExpect(status().isConflict());
    }

    @Test
    void GivenARatingOutsideOneToFive_WhenProgressIsLogged_ThenItAnswers400AndNothingIsRecorded()
            throws Exception {
        mockMvc.perform(bearer(post(progressPath)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.rating").exists());

        verify(trackingService, never()).recordProgress(any(), any(), any());
    }

    @Test
    void GivenANegativeNumericValue_WhenProgressIsLogged_ThenItAnswers400() throws Exception {
        mockMvc.perform(bearer(post(progressPath)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"numericValue\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.numericValue").exists());
    }

    @Test
    void GivenAnUpgradeOwnedBySomebodyElse_WhenProgressIsLogged_ThenItAnswers404AndNot403() throws Exception {
        when(trackingService.recordProgress(eq(userId), eq(upgradeId), any()))
                .thenThrow(new ResourceNotFoundException("Upgrade not found: " + upgradeId));

        mockMvc.perform(bearer(post(progressPath)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void GivenAnOwnedUpgrade_WhenItsProgressIsRead_ThenItAnswers200() throws Exception {
        when(trackingService.getProgress(userId, upgradeId))
                .thenReturn(List.of(AProgressEntry.completedOn(upgradeId, userId, DAY, true)));

        mockMvc.perform(bearer(get(progressPath)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].upgradeId").value(upgradeId.toString()));
    }

    @Test
    void GivenAnOwnedUpgrade_WhenItsStreakIsRead_ThenBothFiguresAreInTheResponse() throws Exception {
        when(trackingService.getStreakSummary(userId, upgradeId)).thenReturn(new StreakSummary(4, 11));

        mockMvc.perform(bearer(get("/api/upgrades/" + upgradeId + "/streak")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value(4))
                .andExpect(jsonPath("$.longest").value(11));
    }

    @Test
    void GivenTheCaller_WhenTodaysProgressIsRead_ThenItIsScopedToThePrincipalWithoutAnUpgradeId()
            throws Exception {
        // FR-21's unscoped-by-upgrade reads. They take no path variable, so the principal is the only
        // thing narrowing them — the case where losing user scoping would leak the most.
        when(trackingService.getTodayProgress(userId)).thenReturn(List.of());

        mockMvc.perform(bearer(get("/api/progress/today")))
                .andExpect(status().isOk());

        verify(trackingService).getTodayProgress(userId);
    }

    @Test
    void GivenTheCaller_WhenTheWeeksProgressIsRead_ThenItIsScopedToThePrincipal() throws Exception {
        when(trackingService.getWeekProgress(userId)).thenReturn(List.of());

        mockMvc.perform(bearer(get("/api/progress/week")))
                .andExpect(status().isOk());

        verify(trackingService).getWeekProgress(userId);
    }
}
