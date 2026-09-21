package com.healthupgrades.common.ratelimit;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * How hard the anonymous authentication endpoints may be hammered, bound from {@code app.rate-limit}.
 *
 * @param limit             how many attempts one client address may make per window. Generous enough
 *                          that a person mistyping a password never meets it, small enough that a
 *                          credential-stuffing run is not worth attempting from one address
 * @param window            the fixed window those attempts are counted in
 * @param maxTrackedClients how many client addresses are held at once. Bounded because the map is keyed
 *                          by something the caller influences: without a cap, addresses that appear once
 *                          and never again accumulate until the process runs out of memory, which turns
 *                          a defence against one attack into a different one
 */
@Validated
@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(
        @Min(1) int limit,
        @NotNull Duration window,
        @Min(1) int maxTrackedClients
) {
}
