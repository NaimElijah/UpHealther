package com.healthupgrades.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A UTC clock a test moves by hand, so a window, a grace period or an expiry can pass without anything
 * sleeping (NFR-15).
 *
 * <p>It stands still until {@link #moveTo} is called. The instant is {@code volatile} because a test may
 * read it from a pool thread, as the limiter's concurrency test does.
 */
public final class MovableClock extends Clock {

    private volatile Instant now;

    /** @param now the instant the clock reads until it is moved */
    public MovableClock(Instant now) {
        this.now = now;
    }

    /** Sets the instant every later read returns; earlier or later than the current one alike. */
    public void moveTo(Instant instant) {
        this.now = instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    /** Returns this clock: the tests that use it reason in UTC instants, and a zone would change nothing. */
    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
