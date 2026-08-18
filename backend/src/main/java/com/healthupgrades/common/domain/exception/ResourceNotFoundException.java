package com.healthupgrades.common.domain.exception;

/**
 * Thrown when a requested resource does not exist <em>for the requesting user</em>.
 *
 * <p>Every repository query is scoped by user id, so a row that belongs to somebody else is
 * indistinguishable from one that does not exist. That is deliberate: answering 403 would confirm the
 * resource exists. Mapped to HTTP 404 (Not Found) by {@code GlobalExceptionHandler}.
 */
public class ResourceNotFoundException extends RuntimeException {

    /** @param message user-facing description of what was not found */
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
