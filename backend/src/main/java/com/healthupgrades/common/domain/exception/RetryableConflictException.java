package com.healthupgrades.common.domain.exception;

/**
 * The request collided with a concurrent one and is worth sending again, unchanged.
 *
 * <p>Distinct from {@link BusinessRuleException}, which says the request was wrong and resending it
 * will not help. This one says the request was right and simply arrived at an awkward moment, so a
 * client that retries should succeed. It maps to <strong>409 Conflict</strong>.
 *
 * <p>It has three uses. The first is a refresh credential that was rotated out moments ago: two
 * browser tabs waking together, or a request retried after a dropped connection, can both present a
 * credential that was current when it was sent. The later of the two is answered 409 rather than 401,
 * because the difference between "try again" and "sign in again" is the difference between a hiccup and
 * being logged out for having two tabs open. The other uses are ADR-074's. One is an upgrade's first
 * tracking configuration saved twice at once: the loser's insert is refused by the unique key, and the
 * same request resent finds the winner's configuration and replaces it. The last is a check of the
 * concurrent-HARD limit (BR-5) made while another of the user's is in progress: it is refused before it
 * changes anything, and the same request resent counts afresh.
 */
public class RetryableConflictException extends RuntimeException {

    /**
     * @param message what collided, in terms safe to return to the caller
     */
    public RetryableConflictException(String message) {
        super(message);
    }
}
