package com.healthupgrades.auth.domain.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * NFR-6 for the issued token: printed by accident, in a log line or an assertion message, it gives away
 * when it lapses and never the bearer token itself, which is the whole credential.
 */
class AccessTokenTest {

    private static final String BEARER = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzb21lb25lIn0.c2lnbmF0dXJl";
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-16T10:15:00Z");

    @Test
    void GivenAnIssuedToken_WhenItIsPrinted_ThenOnlyItsExpiryAppears() {
        String printed = new AccessToken(BEARER, EXPIRES_AT).toString();

        assertThat(printed).contains(EXPIRES_AT.toString()).doesNotContain(BEARER);
    }
}
