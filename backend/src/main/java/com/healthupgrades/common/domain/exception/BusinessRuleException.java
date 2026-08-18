package com.healthupgrades.common.domain.exception;

/**
 * Thrown when an operation is well-formed but violates a domain rule — an illegal state transition,
 * or the max-concurrent-HARD-upgrade limit.
 *
 * <p>Mapped to HTTP 422 (Unprocessable Entity) by {@code GlobalExceptionHandler}. The message reaches
 * the client verbatim, so it must be phrased for a user and must not carry internal detail.
 */
public class BusinessRuleException extends RuntimeException {

    /** @param message user-facing explanation of which rule was violated */
    public BusinessRuleException(String message) {
        super(message);
    }
}
