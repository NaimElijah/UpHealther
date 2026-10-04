package com.healthupgrades.common.time;

import java.time.ZoneId;

/**
 * The one zone the server keeps time in: UTC, whatever the host is set to (ADR-020).
 *
 * <p>The injected {@code Clock} reads it and every {@code @Scheduled} cron names it, so "today", the time
 * an event happened and the minute a reminder fires are all decided in the same zone. A cron that left
 * it out would run in the host's zone while the clock it consults reads UTC — the drift this exists to
 * prevent, and the one {@code ServerTimeArchitectureTest} fails the build on.
 */
public final class ServerZone {

    /** The zone's id, a compile-time constant so that an annotation can name it. */
    public static final String ID = "UTC";

    /** The zone itself, for building the clock. */
    public static final ZoneId ZONE = ZoneId.of(ID);

    private ServerZone() {
    }
}
