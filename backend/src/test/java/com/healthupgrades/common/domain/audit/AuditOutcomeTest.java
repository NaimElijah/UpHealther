package com.healthupgrades.common.domain.audit;

import com.healthupgrades.common.domain.exception.BusinessRuleException;
import com.healthupgrades.common.domain.exception.DuplicateProgressException;
import com.healthupgrades.common.domain.exception.OptimisticLockException;
import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the line between "the system said no" and "the system broke".
 *
 * <p>It matters because the two are the same event to a caller — an exception on the way out — and
 * completely different to whoever is watching. A dashboard where a user attempting an illegal
 * transition looks identical to a database outage is a dashboard nobody can act on.
 */
class AuditOutcomeTest {

    @Test
    void GivenAViolatedRule_WhenTheOutcomeIsClassified_ThenItIsARefusal() {
        assertThat(AuditOutcome.of(new BusinessRuleException("cannot reactivate a completed upgrade")))
                .isEqualTo(AuditOutcome.REFUSED);
    }

    @Test
    void GivenSomebodyElsesRecord_WhenTheOutcomeIsClassified_ThenItIsARefusal() {
        assertThat(AuditOutcome.of(new ResourceNotFoundException("Upgrade not found")))
                .isEqualTo(AuditOutcome.REFUSED);
    }

    @Test
    void GivenASecondEntryForTheSameDay_WhenTheOutcomeIsClassified_ThenItIsARefusal() {
        assertThat(AuditOutcome.of(new DuplicateProgressException("already recorded")))
                .isEqualTo(AuditOutcome.REFUSED);
    }

    /**
     * Pins the classifier, not a live path. A version clash is decided at commit, after the service
     * method has returned, so it never reaches {@code AuditOutcome.of} — {@code LoggingAuditTrail}
     * records it on the rollback path instead ({@code AuditCommitIT}). This keeps the function total
     * over the four exceptions that make up the domain's vocabulary for "no", so that a future throw
     * site is classified correctly the day it appears rather than counted as a fault.
     */
    @Test
    void GivenTheDomainsOptimisticLockException_WhenItIsClassified_ThenItCountsAsARefusal() {
        assertThat(AuditOutcome.of(new OptimisticLockException("version clash")))
                .isEqualTo(AuditOutcome.REFUSED);
    }

    @Test
    void GivenAFaultRatherThanADecision_WhenTheOutcomeIsClassified_ThenItIsAFailure() {
        assertThat(AuditOutcome.of(new IllegalStateException("connection pool exhausted")))
                .isEqualTo(AuditOutcome.FAILED);
    }
}
