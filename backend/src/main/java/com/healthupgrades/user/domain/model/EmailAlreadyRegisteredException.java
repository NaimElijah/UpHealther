package com.healthupgrades.user.domain.model;

/**
 * Raised when an address is written that another account already holds (FR-4).
 *
 * <p>Declared in the user context rather than reusing {@code common}'s {@code BusinessRuleException}:
 * {@code common} already depends on {@code user}, so the reverse import would be a cycle. The caller
 * that knows what the refusal means to a client, registration, translates it.
 *
 * <p>Carries no message naming the address; an exception message is one careless log line away from a
 * log file (NFR-6).
 */
public class EmailAlreadyRegisteredException extends RuntimeException {

    /** Creates the exception with a fixed, address-free message. */
    public EmailAlreadyRegisteredException() {
        super("The email is already registered");
    }
}
