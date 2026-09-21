package com.healthupgrades.common.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * The access-token settings, bound from {@code app.jwt} and validated when the application starts.
 *
 * <p>A missing value fails the boot with the property named, rather than surfacing as a null inside the
 * first request that needs it. The secret's strength is checked separately, by
 * {@code Keys.hmacShaKeyFor} in {@link JwtTokenProvider}, because bean validation cannot count bits.
 *
 * @param secret         HMAC signing secret; at least 256 bits (NFR-3)
 * @param accessTokenTtl how long an issued access token is accepted (NFR-4)
 * @param issuer         the {@code iss} claim this application writes and requires
 * @param audience       the {@code aud} claim this application writes and requires, so a token minted
 *                       for another client under a shared secret is not accepted here
 * @param clockSkew      how far past {@code exp} a token is still accepted, for hosts whose clocks
 *                       disagree slightly
 */
@Validated
@ConfigurationProperties("app.jwt")
public record JwtProperties(
        @NotBlank String secret,
        @NotNull Duration accessTokenTtl,
        @NotBlank String issuer,
        @NotBlank String audience,
        @NotNull Duration clockSkew
) {
}
