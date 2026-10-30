package com.healthupgrades.tracking;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.healthupgrades.support.PostgresIT;
import com.healthupgrades.tracking.domain.port.out.TrackingConfigRepositoryPort;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * NFR-7 when two first configurations of one upgrade race: both can find no configuration, both insert,
 * and only the unique constraint on {@code tracking_configs.upgrade_id} decides (ADR-074).
 *
 * <p>Racing two threads would make this test flaky by construction. Instead the lookup is forced to miss
 * while a configuration exists, which is exactly the state the losing request is in, and the real
 * database is left to refuse the insert. Unflushed, that refusal surfaced at commit, where nothing could
 * translate it, and the caller was answered 500 for a request that a moment later would have worked.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TrackingConfigRaceIT extends PostgresIT {

    private static final String REGISTRATION =
            "{\"name\":\"Racer\",\"email\":\"%s\",\"password\":\"long-enough-password\"}";
    private static final String UPGRADE = "{\"title\":\"Walk daily\",\"type\":\"HABIT\"}";
    private static final String CONFIGURATION = "{\"trackingType\":\"BOOLEAN\"}";

    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    MockMvc mockMvc;

    @MockitoSpyBean
    TrackingConfigRepositoryPort configRepository;

    @Test
    void GivenTheLookupMissedAConcurrentFirstConfiguration_WhenTheSecondIsSaved_ThenItIsRefusedWithARetryable409()
            throws Exception {
        String accessToken = registeredAccessToken();
        String upgradeId = createdUpgradeId(accessToken);
        configure(accessToken, upgradeId).andExpect(status().isOk());
        doReturn(Optional.empty()).when(configRepository).findByUpgradeId(any());

        configure(accessToken, upgradeId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Tracking configuration was saved concurrently. Please retry."));
    }

    private String registeredAccessToken() throws Exception {
        // Unique per run: the container is shared by every IT in the build.
        String email = "tracking-race-" + UUID.randomUUID() + "@example.com";
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTRATION.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(body).get("accessToken").asString();
    }

    private String createdUpgradeId(String accessToken) throws Exception {
        String body = mockMvc.perform(post("/api/upgrades")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(UPGRADE))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(body).get("id").asString();
    }

    private ResultActions configure(String accessToken, String upgradeId) throws Exception {
        return mockMvc.perform(put("/api/upgrades/{upgradeId}/tracking-config", upgradeId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CONFIGURATION));
    }
}
