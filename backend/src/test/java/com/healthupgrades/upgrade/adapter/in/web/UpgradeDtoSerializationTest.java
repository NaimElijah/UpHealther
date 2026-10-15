package com.healthupgrades.upgrade.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.upgrade.domain.model.Difficulty;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import com.healthupgrades.upgrade.domain.model.UpgradeType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the JSON contract of the upgrade response.
 *
 * <p>The embedded tracking configuration used to be typed by the tracking context's own DTO, which made
 * a rename there a breaking change here and forced a dependency that closed a cycle between the two
 * contexts. It is now an upgrade-owned record whose field names, order and rendered values are
 * deliberately identical, so the frontend sees no difference. This test is what makes "identical"
 * checkable rather than merely claimed.
 *
 * <p>It serialises through the mapper Spring Boot configures, which is the one every response goes
 * through; a mapper built here would pin a contract nobody is sent. Records keep their declaration order
 * under that mapper, Jackson 3 included, so the order is asserted too.
 */
@JsonTest
class UpgradeDtoSerializationTest {

    @Autowired
    private ObjectMapper objectMapper;

    private UpgradeDto sampleDto(UpgradeTrackingConfigDto trackingConfig) {
        return new UpgradeDto(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Cold showers",
                "Every morning",
                UpgradeType.HABIT,
                UpgradeStatus.ACTIVE,
                Difficulty.HARD,
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 2),
                LocalDate.of(2026, 6, 1),
                "Because it helps",
                "30 days straight",
                false,
                0L,
                trackingConfig,
                LocalDateTime.of(2026, 3, 1, 9, 0),
                LocalDateTime.of(2026, 3, 2, 9, 0));
    }

    @Test
    void GivenAnUpgradeWithTrackingConfig_WhenTheResponseIsSerialised_ThenTheFieldNamesAreTheOnesTheFrontendReads()
            throws Exception {
        UpgradeTrackingConfigDto config = new UpgradeTrackingConfigDto(
                UUID.randomUUID(), UUID.randomUUID(), "NUMERIC", "DAILY", 30.0, "minutes", true);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(sampleDto(config)));
        JsonNode node = json.get("trackingConfig");

        assertThat(node).isNotNull();
        assertThat(node.propertyNames())
                .containsExactly(
                        "id",
                        "upgradeId",
                        "trackingType",
                        "frequency",
                        "targetNumericValue",
                        "targetUnit",
                        "requiredDaily");
    }

    @Test
    void GivenTrackingConfigWithEnumValuedFields_WhenTheResponseIsSerialised_ThenTheyRenderAsTheirNames()
            throws Exception {
        // These were tracking's TrackingType and Frequency enums; Jackson wrote their names, and the
        // upgrade-owned record carries the same names as strings. The JSON must not change.
        UpgradeTrackingConfigDto config = new UpgradeTrackingConfigDto(
                UUID.randomUUID(), UUID.randomUUID(), "NUMERIC", "WEEKLY", 5.0, "km", false);

        JsonNode node = objectMapper
                .readTree(objectMapper.writeValueAsString(sampleDto(config)))
                .get("trackingConfig");

        assertThat(node.get("trackingType").asString()).isEqualTo("NUMERIC");
        assertThat(node.get("frequency").asString()).isEqualTo("WEEKLY");
        assertThat(node.get("targetNumericValue").asDouble()).isEqualTo(5.0);
        assertThat(node.get("targetUnit").asString()).isEqualTo("km");
        assertThat(node.get("requiredDaily").asBoolean()).isFalse();
    }

    @Test
    void GivenAnUpgradeWithoutTrackingConfig_WhenTheResponseIsSerialised_ThenTheConfigIsNull() throws Exception {
        // The frontend guards on `upgrade.trackingConfig?` throughout, so absent must stay null rather
        // than becoming an empty object.
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(sampleDto(null)));

        assertThat(json.hasNonNull("trackingConfig")).isFalse();
    }

    @Test
    void GivenTheFrontendUpgradeType_WhenTheResponseIsSerialised_ThenEveryFieldItDeclaresIsPresent() throws Exception {
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(sampleDto(null)));

        assertThat(json.propertyNames())
                .contains(
                        "id",
                        "userId",
                        "areaId",
                        "title",
                        "description",
                        "type",
                        "status",
                        "difficulty",
                        "plannedStartDate",
                        "actualStartDate",
                        "targetEndDate",
                        "motivation",
                        "successCriteria",
                        "overdue",
                        "version",
                        "trackingConfig",
                        "createdAt",
                        "updatedAt");
    }
}
