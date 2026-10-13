package com.healthupgrades.support;

/**
 * The PostgreSQL image the integration tests run, and the one {@code docker-compose.yml} must run too.
 *
 * <p>Held apart from {@link PostgresIT} because reading anything from that class initialises it, and
 * its initialiser starts the container. {@code ContainerImagesArePinnedTest} compares this name with
 * compose's in the unit suite, which must not need Docker (NFR-11).
 */
public final class PostgresImage {

    /** An exact tag, down to the Alpine release (ADR-030). Moving it means moving compose's in step. */
    public static final String NAME = "postgres:15.19-alpine3.24";

    private PostgresImage() {
    }
}
