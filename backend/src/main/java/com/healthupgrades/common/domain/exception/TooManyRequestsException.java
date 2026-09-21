package com.healthupgrades.common.domain.exception;

import java.time.Duration;

/**
 * The caller has made too many attempts in too short a time; surfaces as <strong>429</strong>.
 *
 * <p>A refusal, not a fault: the system is working exactly as intended. It carries how long to wait so
 * the response can say so in {@code Retry-After} — a client told only "no" has nothing to do but keep
 * asking, which is the behaviour the limit exists to stop.
 *
 * <p>The wait is deliberately the only detail. It says nothing about which limit was hit, how many
 * attempts remain, or whether the address is close to one, because an attacker tuning a script is
 * exactly the caller most interested in that.
 */
public class TooManyRequestsException extends RuntimeException {

    private final Duration retryAfter;

    /**
     * @param retryAfter how long until the caller may try again
     */
    public TooManyRequestsException(Duration retryAfter) {
        super("Too many attempts");
        this.retryAfter = retryAfter;
    }

    /**
     * How long to wait, in whole seconds, rounded up.
     *
     * <p>Rounded up rather than down because {@code Retry-After: 0} invites an immediate retry that is
     * refused again, and a client honouring it would spin.
     */
    public long retryAfterSeconds() {
        return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }
}
