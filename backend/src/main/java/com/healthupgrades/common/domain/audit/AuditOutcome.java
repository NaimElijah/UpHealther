package com.healthupgrades.common.domain.audit;

import com.healthupgrades.common.domain.exception.BusinessRuleException;
import com.healthupgrades.common.domain.exception.DuplicateProgressException;
import com.healthupgrades.common.domain.exception.OptimisticLockException;
import com.healthupgrades.common.domain.exception.ResourceNotFoundException;

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
     * <p>The four exceptions in {@code common.domain.exception} are the vocabulary this application
     * uses for "no", so they are refusals and everything else is a fault. Listing them explicitly
     * rather than testing a package name keeps the classification greppable, and a fifth exception
     * added later and forgotten here is recorded {@link #FAILED} — visibly wrong in a dashboard rather
     * than silently miscounted.
     *
     * @param thrown what the operation threw
     * @return {@link #REFUSED} for a decision, {@link #FAILED} for a fault
     */
    public static AuditOutcome of(RuntimeException thrown) {
        boolean refusal = thrown instanceof BusinessRuleException
                || thrown instanceof ResourceNotFoundException
                || thrown instanceof DuplicateProgressException
                || thrown instanceof OptimisticLockException;
        return refusal ? REFUSED : FAILED;
    }
}
