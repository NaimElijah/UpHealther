package com.healthupgrades;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the beans {@link HealthUpgradesApplication} declares itself, which is the clock alone.
 */
class HealthUpgradesApplicationTest {

    @Test
    void GivenAHostSetToAnotherZone_WhenTheApplicationClockIsBuilt_ThenItReadsUtc() {
        // NFR-15, ADR-020. The host's zone is what the clock must not inherit, and CI's is UTC already,
        // so the host is given one that never is. Restored before anything else can read it.
        TimeZone hostZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
        try {
            assertThat(new HealthUpgradesApplication().clock().getZone()).isEqualTo(ZoneId.of("UTC"));
        } finally {
            TimeZone.setDefault(hostZone);
        }
    }
}
