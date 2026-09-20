package com.healthupgrades.common.domain.exception;

/**
 * The request collided with a concurrent one and is worth sending again, unchanged.
 *
 * <p>Distinct from {@link BusinessRuleException}, which says the request was wrong and resending it
 * will not help. This one says the request was right and simply arrived at an awkward moment, so a
 * client that retries will succeed. It maps to <strong>409 Conflict</strong>.
 *
 * <p>Its one use today is a refresh credential that was rotated out moments ago: two browser tabs
 * waking together, or a request retried after a dropped connection, can both present a credential that
 * was current when it was sent. The second to arrive is answered 409 rather than 401, because the
 * difference between "try again" and "sign in again" is the difference between a hiccup and being
 * logged out for having two tabs open.
 */
public class RetryableConflictException extends RuntimeException {

    /**
     * @param message what collided, in terms safe to return to the caller
     */
    public RetryableConflictException(String message) {
        super(message);
    }
}
