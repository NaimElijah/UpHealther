package com.healthupgrades.tracking;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.healthupgrades.support.PostgresIT;
import com.healthupgrades.tracking.domain.port.out.TrackingConfigRepositoryPort;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
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
 *
 * <p>The same seam shows the other race the insert can lose: the upgrade deleted by another request after
 * its ownership was checked. The foreign key refuses that insert, and the caller is answered 404, as for
 * any upgrade that does not exist.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TrackingConfigRaceIT extends PostgresIT {

    private static final String REGISTRATION =
            "{\"name\":\"Racer\",\"email\":\"%s\",\"password\":\"long-enough-password\"}";
    private static final String UPGRADE = "{\"title\":\"Walk daily\",\"type\":\"HABIT\"}";
    private static final String CONFIGURATION = "{\"trackingType\":\"BOOLEAN\"}";

    /** The losing request's body: different from the winner's, so the stored row says whose it is. */
    private static final String RESENT_CONFIGURATION = "{\"trackingType\":\"NUMERIC\"}";

    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    DataSource dataSource;

    @MockitoSpyBean
    TrackingConfigRepositoryPort configRepository;

    @Test
    void GivenTheLookupMissedAConcurrentFirstConfiguration_WhenTheSecondIsSaved_ThenItIsRefusedWithARetryable409()
            throws Exception {
        String accessToken = registeredAccessToken();
        String upgradeId = createdUpgradeId(accessToken);
        configure(accessToken, upgradeId).andExpect(status().isCreated());
        doReturn(Optional.empty()).when(configRepository).findByUpgradeId(any());

        configure(accessToken, upgradeId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Tracking configuration was saved concurrently. Please retry."));
    }

    @Test
    void GivenTheUpgradeDeletedAfterItsOwnershipWasChecked_WhenItsFirstConfigurationIsSaved_ThenItIsAnsweredNotFound()
            throws Exception {
        // Another tab deletes the upgrade after the service has confirmed it is the caller's and before
        // the insert. The lookup between the two is where this test does it, on a connection of its own
        // that commits at once, as the other tab's request would. The foreign key then refuses the
        // insert, which is a 404 like any request for a missing upgrade, not a 500.
        String accessToken = registeredAccessToken();
        String upgradeId = createdUpgradeId(accessToken);
        doAnswer(lookup -> {
                    deleteUpgradeElsewhere(upgradeId);
                    return Optional.empty();
                })
                .when(configRepository)
                .findByUpgradeId(any());

        configure(accessToken, upgradeId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Upgrade not found: " + upgradeId));
    }

    @Test
    void GivenAFirstConfigurationThatLostItsRace_WhenItIsResent_ThenItReplacesTheWinnersConfiguration()
            throws Exception {
        // The 409 is retryable because a resend finds the winner's configuration and replaces it, which is
        // what a PUT means (ADR-074). The loser's refusal must also leave the winner's row as it was.
        String accessToken = registeredAccessToken();
        String upgradeId = createdUpgradeId(accessToken);
        configure(accessToken, upgradeId).andExpect(status().isCreated());
        doReturn(Optional.empty()).when(configRepository).findByUpgradeId(any());
        configure(accessToken, upgradeId, RESENT_CONFIGURATION).andExpect(status().isConflict());
        reset(configRepository);

        storedConfiguration(accessToken, upgradeId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackingType").value("BOOLEAN"));
        configure(accessToken, upgradeId, RESENT_CONFIGURATION).andExpect(status().isOk());
        storedConfiguration(accessToken, upgradeId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackingType").value("NUMERIC"));
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
        return configure(accessToken, upgradeId, CONFIGURATION);
    }

    private ResultActions configure(String accessToken, String upgradeId, String configuration) throws Exception {
        return mockMvc.perform(put("/api/upgrades/{upgradeId}/tracking-config", upgradeId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(configuration));
    }

    /**
     * Deletes the upgrade on a connection taken straight from the pool, outside the request's
     * transaction, in autocommit: the delete is committed before the request's insert runs.
     */
    private void deleteUpgradeElsewhere(String upgradeId) throws SQLException {
        try (Connection otherTab = dataSource.getConnection();
                PreparedStatement delete = otherTab.prepareStatement("DELETE FROM health_upgrades WHERE id = ?")) {
            delete.setObject(1, UUID.fromString(upgradeId));
            delete.executeUpdate();
        }
    }

    private ResultActions storedConfiguration(String accessToken, String upgradeId) throws Exception {
        return mockMvc.perform(get("/api/upgrades/{upgradeId}/tracking-config", upgradeId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }
}
