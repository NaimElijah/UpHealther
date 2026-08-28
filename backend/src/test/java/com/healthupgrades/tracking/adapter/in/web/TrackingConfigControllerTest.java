package com.healthupgrades.tracking.adapter.in.web;

import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.common.security.JwtAuthenticationFilter;
import com.healthupgrades.common.security.JwtTokenProvider;
import com.healthupgrades.common.security.SecurityConfig;
import com.healthupgrades.common.security.UserDetailsServiceImpl;
import com.healthupgrades.support.ATrackingConfig;
import com.healthupgrades.support.WebSliceSupport;
import com.healthupgrades.tracking.application.TrackingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static com.healthupgrades.support.WebSliceSupport.bearer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract for how an upgrade is measured: FR-16 (the four tracking types) and FR-17 (a numeric
 * target and its unit).
 *
 * <p>The route is a PUT on a singular sub-resource rather than a POST, which is the right shape: an
 * upgrade has at most one configuration, so writing it twice must replace rather than accumulate.
 */
@WebMvcTest(TrackingConfigController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class,
        TrackingWebMapper.class, WebSliceSupport.class})
class TrackingConfigControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean TrackingService trackingService;
    @MockBean JwtTokenProvider tokenProvider;
    @MockBean UserDetailsServiceImpl userDetailsService;

    private UUID userId;
    private final UUID upgradeId = UUID.randomUUID();
    private String path;

    @BeforeEach
    void authenticate() {
        userId = UUID.randomUUID();
        path = "/api/upgrades/" + upgradeId + "/tracking-config";
        WebSliceSupport.authenticateAs(tokenProvider, userDetailsService, userId);
    }

    @Test
    void GivenANumericConfiguration_WhenItIsSaved_ThenItAnswers200CarryingTheTargetAndItsUnit()
            throws Exception {
        when(trackingService.saveConfig(eq(userId), eq(upgradeId), any()))
                .thenReturn(ATrackingConfig.numeric(upgradeId, 10_000.0, "steps"));

        mockMvc.perform(bearer(put(path)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackingType\":\"NUMERIC\",\"frequency\":\"DAILY\","
                                + "\"targetNumericValue\":10000,\"targetUnit\":\"steps\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackingType").value("NUMERIC"))
                .andExpect(jsonPath("$.targetNumericValue").value(10000.0))
                .andExpect(jsonPath("$.targetUnit").value("steps"));
    }

    @Test
    void GivenNoTrackingType_WhenAConfigurationIsSaved_ThenItAnswers400AndNothingIsSaved() throws Exception {
        // The type decides which value field is scored, so a configuration without one cannot judge
        // anything.
        mockMvc.perform(bearer(put(path)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frequency\":\"DAILY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.trackingType").exists());

        verify(trackingService, never()).saveConfig(any(), any(), any());
    }

    @Test
    void GivenATargetUnitLongerThanItsColumn_WhenAConfigurationIsSaved_ThenItAnswers400NamingTheField()
            throws Exception {
        // tracking_configs.target_unit is VARCHAR(100), which is where the 100 comes from. Unbounded
        // here, an over-long unit reached the flush in production and came back 500. This slice has no
        // database and cannot see that flush; what it pins is the boundary contract. BR-16.
        mockMvc.perform(bearer(put(path)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackingType\":\"NUMERIC\",\"targetUnit\":\"" + "u".repeat(101) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.targetUnit").exists());

        verify(trackingService, never()).saveConfig(any(), any(), any());
    }

    @Test
    void GivenATrackingTypeThatIsNotInTheEnum_WhenAConfigurationIsSaved_ThenItAnswers400RatherThan500()
            throws Exception {
        mockMvc.perform(bearer(put(path)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackingType\":\"TELEPATHY\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void GivenAConfiguredUpgrade_WhenItsConfigurationIsRead_ThenItAnswers200() throws Exception {
        when(trackingService.getConfig(userId, upgradeId))
                .thenReturn(ATrackingConfig.booleanTracking(upgradeId));

        mockMvc.perform(bearer(get(path)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackingType").value("BOOLEAN"));
    }

    @Test
    void GivenAnUpgradeWithNoConfiguration_WhenItIsRead_ThenItAnswers404() throws Exception {
        when(trackingService.getConfig(userId, upgradeId))
                .thenThrow(new ResourceNotFoundException("Tracking config not found for upgrade: " + upgradeId));

        mockMvc.perform(bearer(get(path)))
                .andExpect(status().isNotFound());
    }
}
