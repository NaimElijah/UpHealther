package com.healthupgrades.user.domain.model;

/**
 * Raised when an address is written that another account already holds (FR-4).
 *
 * <p>A type of its own rather than {@code common}'s {@code BusinessRuleException}, so that registration
 * can catch this one refusal around the save and nothing else a save might raise. Registration is the
 * caller that knows what the refusal means to a client, and it translates it.
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
