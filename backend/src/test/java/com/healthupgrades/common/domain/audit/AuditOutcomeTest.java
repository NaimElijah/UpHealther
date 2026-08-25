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

    @Test
    void GivenALostConcurrentEdit_WhenTheOutcomeIsClassified_ThenItIsARefusal() {
        assertThat(AuditOutcome.of(new OptimisticLockException("version clash")))
                .isEqualTo(AuditOutcome.REFUSED);
    }

    @Test
    void GivenAFaultRatherThanADecision_WhenTheOutcomeIsClassified_ThenItIsAFailure() {
        assertThat(AuditOutcome.of(new IllegalStateException("connection pool exhausted")))
                .isEqualTo(AuditOutcome.FAILED);
    }
}
