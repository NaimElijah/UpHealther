package com.healthupgrades.auth.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.auth.domain.port.out.AccessToken;
import com.healthupgrades.common.security.IssuedAccessToken;
import com.healthupgrades.common.security.JwtProperties;
import com.healthupgrades.common.security.JwtTokenProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Covers the shipped {@code AccessTokenIssuerPort}: it issues exactly the token the kernel's
 * {@link JwtTokenProvider} issues, so the claims, the lifetime and the key are the provider's, and the
 * token is one the HTTP filter and the STOMP interceptor accept.
 *
 * <p>A real provider over a fixed clock rather than a mock. HS256 over the same claims and key is
 * deterministic, so the issuer's token can be compared with the provider's own, character for character.
 * What the token claims, and how strictly it is verified, is {@code JwtTokenProviderTest}'s business.
 */
class JwtAccessTokenIssuerTest {

    /** 65 ASCII characters: 520 bits, over the HS256 minimum. */
    private static final String SECRET = "a-signing-secret-long-enough-for-hs256-at-least-256-bits-long!!!!";

    private static final Duration TTL = Duration.ofMinutes(15);
    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");
    private static final UUID USER_ID = UUID.fromString("0f2c8f5a-2a4e-4a1d-8f0a-3c5b9d1e77a1");
    private static final UUID SESSION_ID = UUID.fromString("6b1f0f4c-9a2d-4c3e-9b7a-1d2e3f4a5b6c");

    private final JwtTokenProvider provider = new JwtTokenProvider(
            new JwtProperties(SECRET, TTL, "healthupgrades", "healthupgrades-web", Duration.ofSeconds(30)),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final JwtAccessTokenIssuer issuer = new JwtAccessTokenIssuer(provider);

    @Test
    void GivenAnAccountAndASession_WhenATokenIsIssued_ThenItIsTheTokenTheProviderIssues() {
        IssuedAccessToken expected = provider.issue(USER_ID, SESSION_ID);

        AccessToken issued = issuer.issue(USER_ID, SESSION_ID);

        assertThat(issued.value()).isEqualTo(expected.value());
    }

    @Test
    void GivenAnAccountAndASession_WhenATokenIsIssued_ThenTheKernelVerifiesItAsThatAccountWithinThatSession() {
        AccessToken issued = issuer.issue(USER_ID, SESSION_ID);

        assertThat(provider.verify(issued.value())).hasValueSatisfying(verified -> {
            assertThat(verified.userId()).isEqualTo(USER_ID);
            assertThat(verified.sessionId()).isEqualTo(SESSION_ID);
        });
    }

    @Test
    void GivenTheProvidersLifetime_WhenATokenIsIssued_ThenItsExpiryIsTheProvidersExpiry() {
        AccessToken issued = issuer.issue(USER_ID, SESSION_ID);

        assertThat(issued.expiresAt())
                .isEqualTo(provider.issue(USER_ID, SESSION_ID).expiresAt())
                .isEqualTo(NOW.plus(TTL));
    }
}
