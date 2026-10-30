package com.healthupgrades.auth.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** NFR-6 for the refresh credential: printed by accident, it gives away neither of its halves. */
class RefreshTokenTest {

    private static final UUID SESSION_ID = UUID.fromString("6f1c2a54-8a7e-4d3b-9c1f-0e2d4b6a8c10");
    private static final String SECRET = "q8Zr3kV0yWm2NtLs7HcBxPa9EuJdGf4o";

    @Test
    void GivenARefreshCredential_WhenItIsPrinted_ThenNeitherTheSessionIdNorTheSecretAppears() {
        String printed = new RefreshToken(SESSION_ID, SECRET).toString();

        assertThat(printed).doesNotContain(SECRET).doesNotContain(SESSION_ID.toString());
    }
}
