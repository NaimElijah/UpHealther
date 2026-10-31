package com.healthupgrades.tracking.domain.model;

import com.healthupgrades.common.domain.exception.ConflictException;

/**
 * Thrown when a progress entry already exists for the same upgrade and date (BR-6).
 *
 * <p>One entry per upgrade per day is a domain invariant, also backed by a unique constraint in the
 * schema. A {@link ConflictException}, so it is answered 409 (Conflict) rather than 422: the client
 * should treat it as a conflict to resolve, by editing the existing entry, rather than as invalid input.
 * It lives here, beside the entry it protects, because it is this context's concept; the kernel knows
 * only the shape.
 */
public class DuplicateProgressException extends ConflictException {

    /** @param message user-facing explanation naming the date that already has an entry */
    public DuplicateProgressException(String message) {
        super(message);
    }
}
