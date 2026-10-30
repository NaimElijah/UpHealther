package com.healthupgrades.auth.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** NFR-6 for a granted session: printed by accident, it gives away when it lapses and nothing else. */
class SessionGrantTest {

    private static final UUID SESSION_ID = UUID.fromString("6f1c2a54-8a7e-4d3b-9c1f-0e2d4b6a8c10");
    private static final String CREDENTIAL = SESSION_ID + ".q8Zr3kV0yWm2NtLs7HcBxPa9EuJdGf4o";
    private static final Instant EXPIRES_AT = Instant.parse("2026-10-16T10:15:00Z");

    @Test
    void GivenAGrantedSession_WhenItIsPrinted_ThenOnlyItsExpiryAppears() {
        String printed = new SessionGrant(SESSION_ID, CREDENTIAL, EXPIRES_AT).toString();

        assertThat(printed)
                .contains(EXPIRES_AT.toString())
                .doesNotContain(CREDENTIAL)
                .doesNotContain(SESSION_ID.toString());
    }
}
