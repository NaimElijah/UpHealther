package com.healthupgrades.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** NFR-6 for the signed token: printed by accident, it gives away when it lapses and never the JWT. */
class IssuedAccessTokenTest {

    private static final String JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzb21lb25lIn0.c2lnbmF0dXJl";
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-16T10:15:00Z");

    @Test
    void GivenAnIssuedToken_WhenItIsPrinted_ThenOnlyItsExpiryAppears() {
        String printed = new IssuedAccessToken(JWT, EXPIRES_AT).toString();

        assertThat(printed).contains(EXPIRES_AT.toString()).doesNotContain(JWT);
    }
}
