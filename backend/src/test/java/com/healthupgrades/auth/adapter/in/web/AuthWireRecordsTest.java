package com.healthupgrades.auth.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.auth.adapter.in.web.AuthController.LoginRequest;
import com.healthupgrades.auth.adapter.in.web.AuthController.RegisterRequest;
import com.healthupgrades.user.domain.model.Role;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * NFR-6 for the auth context's wire records: Spring MVC writes a request body's {@code toString} at
 * DEBUG, and a whole one at TRACE, so a record that carries a password, a token or an address must not
 * print it.
 */
class AuthWireRecordsTest {

    private static final String NAME = "Dana Example";
    private static final String EMAIL = "dana@example.com";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String BEARER = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzb21lb25lIn0.c2lnbmF0dXJl";
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-16T10:15:00Z");

    @Test
    void GivenASignInBody_WhenItIsPrinted_ThenNeitherTheAddressNorThePasswordAppears() {
        String printed = new LoginRequest(EMAIL, PASSWORD).toString();

        assertThat(printed).doesNotContain(EMAIL).doesNotContain(PASSWORD);
    }

    @Test
    void GivenARegistrationBody_WhenItIsPrinted_ThenNoneOfItsValuesAppears() {
        String printed = new RegisterRequest(NAME, EMAIL, PASSWORD).toString();

        assertThat(printed).doesNotContain(NAME).doesNotContain(EMAIL).doesNotContain(PASSWORD);
    }

    @Test
    void GivenAProfileResponse_WhenItIsPrinted_ThenItNamesTheAccountByIdAndNeitherItsNameNorItsAddressAppears() {
        // GET /me answers every reload with this record, and a whole one fits inside Spring MVC's DEBUG
        // line, so the generated toString would write the caller's address on each page load.
        UUID id = UUID.randomUUID();

        String printed = new UserDto(id, NAME, EMAIL, Role.USER, LocalDateTime.of(2026, 9, 1, 8, 0)).toString();

        assertThat(printed).contains(id.toString()).doesNotContain(NAME).doesNotContain(EMAIL);
    }

    @Test
    void GivenATokenResponse_WhenItIsPrinted_ThenOnlyItsExpiryAppears() {
        UserDto user = new UserDto(UUID.randomUUID(), NAME, EMAIL, Role.USER, LocalDateTime.of(2026, 9, 1, 8, 0));

        String printed = new TokenPair(BEARER, EXPIRES_AT, user).toString();

        assertThat(printed)
                .contains(EXPIRES_AT.toString())
                .doesNotContain(BEARER)
                .doesNotContain(EMAIL);
    }
}
