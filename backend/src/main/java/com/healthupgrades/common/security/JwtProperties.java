package com.healthupgrades.common.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The access-token settings, bound from {@code app.jwt} and validated when the application starts.
 *
 * <p>A missing value fails the boot with the property named, rather than surfacing as a null inside the
 * first request that needs it. The secret's strength is checked separately, by
 * {@code Keys.hmacShaKeyFor} in {@link JwtTokenProvider}, because bean validation cannot count bits.
 *
 * @param secret         HMAC signing secret; at least 256 bits (NFR-3)
 * @param accessTokenTtl how long an issued access token is accepted (NFR-4), between a minute and an
 *                       hour
 * @param issuer         the {@code iss} claim this application writes and requires
 * @param audience       the {@code aud} claim this application writes and requires, so a token minted
 *                       for another client under a shared secret is not accepted here
 * @param clockSkew      how far past {@code exp} a token is still accepted, for hosts whose clocks
 *                       disagree slightly; between zero and five minutes
 */
@Validated
@ConfigurationProperties("app.jwt")
public record JwtProperties(
        @NotBlank String secret,

        @NotNull @DurationMin(minutes = MIN_ACCESS_TOKEN_TTL_MINUTES) @DurationMax(hours = MAX_ACCESS_TOKEN_TTL_HOURS)
        Duration accessTokenTtl,

        @NotBlank String issuer,
        @NotBlank String audience,

        @NotNull @DurationMin(seconds = 0) @DurationMax(minutes = MAX_CLOCK_SKEW_MINUTES)
        Duration clockSkew) {

    /**
     * The shortest access-token lifetime: a minute. Every open tab renews once per lifetime, so a shorter
     * one would spend the refresh endpoints' rate limit on renewals alone (S5.A13).
     */
    public static final long MIN_ACCESS_TOKEN_TTL_MINUTES = 1;

    /**
     * The longest: an hour, four times the shipped fifteen minutes. The token cannot be revoked before it
     * lapses except through its session, so its lifetime is how long a leaked one is worth having (NFR-4).
     */
    public static final long MAX_ACCESS_TOKEN_TTL_HOURS = 1;

    /**
     * The longest clock skew: five minutes, ten times the shipped thirty seconds, and the tolerance
     * Kerberos defaults to. Hosts kept by NTP agree to well within a second. The skew is added straight
     * onto every token's expiry, so past a few minutes it stops being a tolerance and becomes a longer
     * lifetime than {@link #MAX_ACCESS_TOKEN_TTL_HOURS} allows (S5.A13).
     */
    public static final long MAX_CLOCK_SKEW_MINUTES = 5;
}
