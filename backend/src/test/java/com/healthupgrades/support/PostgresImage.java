package com.healthupgrades.support;

/**
 * The PostgreSQL image the integration tests run, and the one {@code docker-compose.yml} must run too.
 *
 * <p>{@code ContainerImagesArePinnedTest} compares this name with compose's in the unit suite, which
 * must not need Docker (NFR-11). On {@link PostgresIT} that comparison would be safe only for as long
 * as the name stayed a compile-time constant. Reading a constant does not initialise a class, but
 * reading anything else does, and {@code PostgresIT}'s initialiser starts the container. A class of its
 * own has no initialiser to run.
 */
public final class PostgresImage {

    /** An exact tag, down to the Alpine release (ADR-030). Moving it means moving compose's in step. */
    public static final String NAME = "postgres:15.19-alpine3.24";

    private PostgresImage() {}
}
