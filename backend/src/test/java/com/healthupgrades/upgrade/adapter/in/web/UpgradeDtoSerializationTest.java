package com.healthupgrades.upgrade.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.healthupgrades.upgrade.domain.model.Difficulty;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import com.healthupgrades.upgrade.domain.model.UpgradeType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the JSON contract of the upgrade response.
 *
 * <p>The embedded tracking configuration used to be typed by the tracking context's own DTO, which made
 * a rename there a breaking change here and forced a dependency that closed a cycle between the two
 * contexts. It is now an upgrade-owned record whose field names, order and rendered values are
 * deliberately identical, so the frontend sees no difference. This test is what makes "identical"
 * checkable rather than merely claimed.
 */
class UpgradeDtoSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private UpgradeDto sampleDto(UpgradeTrackingConfigDto trackingConfig) {
        return new UpgradeDto(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Cold showers", "Every morning",
                UpgradeType.HABIT, UpgradeStatus.ACTIVE, Difficulty.HARD,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 2), LocalDate.of(2026, 6, 1),
                "Because it helps", "30 days straight",
                false, 0L, trackingConfig,
                LocalDateTime.of(2026, 3, 1, 9, 0), LocalDateTime.of(2026, 3, 2, 9, 0));
    }

    @Test
    void trackingConfig_serialisesWithTheFieldNamesTheFrontendReads() throws Exception {
        UpgradeTrackingConfigDto config = new UpgradeTrackingConfigDto(
                UUID.randomUUID(), UUID.randomUUID(), "NUMERIC", "DAILY", 30.0, "minutes", true);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(sampleDto(config)));
        JsonNode node = json.get("trackingConfig");

        assertThat(node).isNotNull();
        assertThat(node.fieldNames()).toIterable().containsExactly(
                "id", "upgradeId", "trackingType", "frequency",
                "targetNumericValue", "targetUnit", "requiredDaily");
    }

    @Test
    void trackingConfig_rendersEnumValuedFieldsAsTheirNames() throws Exception {
        // These were tracking's TrackingType and Frequency enums; Jackson wrote their names, and the
        // upgrade-owned record carries the same names as strings. The JSON must not change.
        UpgradeTrackingConfigDto config = new UpgradeTrackingConfigDto(
                UUID.randomUUID(), UUID.randomUUID(), "NUMERIC", "WEEKLY", 5.0, "km", false);

        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(sampleDto(config)))
                .get("trackingConfig");

        assertThat(node.get("trackingType").asText()).isEqualTo("NUMERIC");
        assertThat(node.get("frequency").asText()).isEqualTo("WEEKLY");
        assertThat(node.get("targetNumericValue").asDouble()).isEqualTo(5.0);
        assertThat(node.get("targetUnit").asText()).isEqualTo("km");
        assertThat(node.get("requiredDaily").asBoolean()).isFalse();
    }

    @Test
    void upgradeWithoutTrackingConfig_rendersItAsNull() throws Exception {
        // The frontend guards on `upgrade.trackingConfig?` throughout, so absent must stay null rather
        // than becoming an empty object.
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(sampleDto(null)));

        assertThat(json.hasNonNull("trackingConfig")).isFalse();
    }

    @Test
    void theResponseKeepsTheFieldsTheFrontendTypeDeclares() throws Exception {
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(sampleDto(null)));

        assertThat(json.fieldNames()).toIterable().contains(
                "id", "userId", "areaId", "title", "description", "type", "status", "difficulty",
                "plannedStartDate", "actualStartDate", "targetEndDate", "motivation", "successCriteria",
                "overdue", "version", "trackingConfig", "createdAt", "updatedAt");
    }
}
