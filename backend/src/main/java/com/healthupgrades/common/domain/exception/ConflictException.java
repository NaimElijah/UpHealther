package com.healthupgrades.common.domain.exception;

/**
 * A refusal because the request conflicts with the state it would change: the client should resolve the
 * conflict, not correct its input. Mapped to HTTP 409 (Conflict) by {@code GlobalExceptionHandler},
 * with this exception's message as the body's.
 *
 * <p>Separate from {@link BusinessRuleException}, whose 422 says the request itself was wrong. A
 * conflict says the request was well-formed and something already in place stands in its way: a
 * progress entry for that day, say, which the client can edit instead.
 *
 * <p>Abstract, because the kernel names the shape and each context names the conflict. A context
 * declares its own subclass beside its model, so the kernel maps and classifies the status without
 * naming any one context's concept (ADR-002, ADR-062).
 */
public abstract class ConflictException extends RuntimeException {

    /** @param message user-facing explanation of what the request conflicts with */
    protected ConflictException(String message) {
        super(message);
    }
}
