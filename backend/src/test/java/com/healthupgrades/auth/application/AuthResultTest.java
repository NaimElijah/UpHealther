package com.healthupgrades.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.auth.application.port.in.SessionGrant;
import com.healthupgrades.support.AUser;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** NFR-6 for an authentication's result: printed by accident, it gives away neither credential. */
class AuthResultTest {

    private static final String BEARER = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzb21lb25lIn0.c2lnbmF0dXJl";
    private static final UUID SESSION_ID = UUID.fromString("6f1c2a54-8a7e-4d3b-9c1f-0e2d4b6a8c10");
    private static final String CREDENTIAL = SESSION_ID + ".q8Zr3kV0yWm2NtLs7HcBxPa9EuJdGf4o";
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-16T10:15:00Z");

    @Test
    void GivenAnAuthResult_WhenItIsPrinted_ThenNeitherTheAccessTokenNorTheRefreshCredentialAppears() {
        SessionGrant session = new SessionGrant(SESSION_ID, CREDENTIAL, EXPIRES_AT.plusSeconds(86_400));

        String printed = new AuthResult(BEARER, EXPIRES_AT, AUser.withId(UUID.randomUUID()), session).toString();

        assertThat(printed)
                .contains(EXPIRES_AT.toString())
                .doesNotContain(BEARER)
                .doesNotContain(CREDENTIAL)
                .doesNotContain(SESSION_ID.toString())
                .doesNotContain(AUser.EMAIL);
    }
}
