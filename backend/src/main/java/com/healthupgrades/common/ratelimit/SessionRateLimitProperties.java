package com.healthupgrades.common.ratelimit;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How hard refresh and sign-out may be hammered, bound from {@code app.session-rate-limit}.
 *
 * <p>A limit of its own, looser than sign-in's and counted in a separate bucket (S5.A11). Neither
 * endpoint takes a password, so there is nothing to guess at; what they cost is an audit entry and a
 * locked session read per attempt, and unthrottled, an anonymous caller could write the audit stream at
 * whatever rate it liked. A household behind one address renews every tab's session every quarter of an
 * hour and once per page load, so the limit has to leave room for that.
 *
 * @param limit             how many attempts one client address may make per window, refresh and
 *                          sign-out together
 * @param window            the fixed window those attempts are counted in
 * @param maxTrackedClients how many client addresses are held at once, bounded for the reason
 *                          {@link RateLimitProperties} gives
 */
@Validated
@ConfigurationProperties("app.session-rate-limit")
public record SessionRateLimitProperties(
        @Min(1) int limit, @NotNull Duration window, @Min(1) int maxTrackedClients) {

    /** The same three numbers in the shape a {@link FixedWindowRateLimiter} is built from. */
    public RateLimitProperties limits() {
        return new RateLimitProperties(limit, window, maxTrackedClients);
    }
}
