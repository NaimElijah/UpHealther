package com.healthupgrades.common.domain.audit;

import com.healthupgrades.common.domain.exception.AuthenticationRequiredException;
import com.healthupgrades.common.domain.exception.BusinessRuleException;
import com.healthupgrades.common.domain.exception.ConflictException;
import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.common.domain.exception.RetryableConflictException;
import com.healthupgrades.common.domain.exception.TooManyRequestsException;

/**
 * How an audited attempt ended.
 *
 * <p>The distinction between {@link #REFUSED} and {@link #FAILED} is the whole point of having three
 * values rather than two: a refusal is the system working — somebody asked for something the rules do
 * not allow — while a failure is the system broken. Collapsing them would make the one alerting signal
 * worth having indistinguishable from ordinary traffic.
 */
public enum AuditOutcome {

    /** The attempt was allowed and completed. */
    ALLOWED,

    /** The system decided no: a rule, an ownership check or a conflict refused it. Nobody is paged. */
    REFUSED,

    /** The attempt did not complete because something broke. Someone has to look. */
    FAILED;

    /**
     * Classifies the exception an audited operation threw.
     *
     * <p>The exceptions in {@code common.domain.exception} are the vocabulary this application uses
     * for "no", so they are refusals and everything else is a fault. Listing them explicitly rather
     * than testing a package name keeps the classification greppable, and an exception
     * added later and forgotten here is recorded {@link #FAILED} — visibly wrong in a dashboard rather
     * than silently miscounted. A context's own refusal counts by the kernel type it extends: its
     * conflict is a {@link ConflictException}, so the kernel classifies it without naming it.
     *
     * <p>Note what does <em>not</em> arrive here: a {@code @Version} clash decided at commit. The only
     * repository writes that flush are registration's, the tracking configuration's and the account
     * save, and each translates what it can lose into one of the refusals above: a unique-key violation
     * (ADR-074), or the account's version clash, into the {@code user} context's conflict (ADR-073).
     * Everywhere else that exception is thrown by the transaction proxy after the service method has
     * returned — outside any call this classifier sees. The audit adapter handles it on the rollback
     * path instead.
     *
     * @param thrown what the operation threw
     * @return {@link #REFUSED} for a decision, {@link #FAILED} for a fault
     */
    public static AuditOutcome of(RuntimeException thrown) {
        boolean refusal = thrown instanceof BusinessRuleException
                || thrown instanceof AuthenticationRequiredException
                || thrown instanceof ResourceNotFoundException
                || thrown instanceof ConflictException
                || thrown instanceof RetryableConflictException
                || thrown instanceof TooManyRequestsException;
        return refusal ? REFUSED : FAILED;
    }
}
