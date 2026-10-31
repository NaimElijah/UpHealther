package com.healthupgrades.user.domain.model;

import com.healthupgrades.common.domain.exception.ConflictException;

/**
 * Raised when a write to an account is overtaken: another write to the same account committed after this
 * one loaded it, so saving it would put back what the other write changed (NFR-34, ADR-073).
 *
 * <p>A {@link ConflictException}, so the kernel answers it 409 and the audit trail records it
 * {@code REFUSED}: two administrators acting on one account at once is the system working, not broken. The
 * request can be resent; the resend loads the account as it now stands.
 *
 * <p>Carries a fixed message, naming neither the account nor the versions that clashed.
 */
public class AccountModifiedConcurrentlyException extends ConflictException {

    /** The wording, the same in meaning as the kernel's answer to any other version clash. */
    static final String MESSAGE = "Account was modified concurrently. Please retry.";

    /** Creates the exception with its fixed message. */
    public AccountModifiedConcurrentlyException() {
        super(MESSAGE);
    }
}
