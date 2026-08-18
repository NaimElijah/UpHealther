package com.healthupgrades.common.domain.exception;

/**
 * Thrown when a progress entry already exists for the same upgrade and date.
 *
 * <p>One entry per upgrade per day is a domain invariant, also backed by a unique constraint in the
 * schema. Mapped to HTTP 409 (Conflict) by {@code GlobalExceptionHandler} — separate from
 * {@link BusinessRuleException} precisely because the client should treat it as a conflict to resolve
 * (edit the existing entry) rather than as invalid input.
 */
public class DuplicateProgressException extends RuntimeException {

    /** @param message user-facing explanation naming the upgrade and date that already have an entry */
    public DuplicateProgressException(String message) {
        super(message);
    }
}
