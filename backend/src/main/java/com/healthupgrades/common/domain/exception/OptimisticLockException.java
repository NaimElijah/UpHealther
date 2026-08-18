package com.healthupgrades.common.domain.exception;

/**
 * Thrown when a concurrent edit is detected — the aggregate's {@code @Version} moved between load and
 * save.
 *
 * <p>Domain-level counterpart to {@code jakarta.persistence.OptimisticLockException}, so the
 * application layer can signal the conflict without leaking JPA. Both are mapped to HTTP 409
 * (Conflict) by {@code GlobalExceptionHandler}, which replaces the message with a retry instruction.
 */
public class OptimisticLockException extends RuntimeException {

    /** @param message diagnostic description of the conflicting update */
    public OptimisticLockException(String message) {
        super(message);
    }
}
