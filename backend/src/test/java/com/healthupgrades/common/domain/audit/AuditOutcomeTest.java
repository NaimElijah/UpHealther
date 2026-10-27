package com.healthupgrades.common.domain.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.common.domain.exception.AuthenticationRequiredException;
import com.healthupgrades.common.domain.exception.BusinessRuleException;
import com.healthupgrades.common.domain.exception.DuplicateProgressException;
import com.healthupgrades.common.domain.exception.OptimisticLockException;
import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.common.domain.exception.RetryableConflictException;
import com.healthupgrades.common.domain.exception.TooManyRequestsException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

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
    void GivenARequestWithNoUsableCredential_WhenTheOutcomeIsClassified_ThenItIsARefusal() {
        assertThat(AuditOutcome.of(AuthenticationRequiredException.rejectedToken()))
                .isEqualTo(AuditOutcome.REFUSED);
    }

    @Test
    void GivenARequestOvertakenByAConcurrentOne_WhenTheOutcomeIsClassified_ThenItIsARefusal() {
        // A lost race the client simply retries, as with a refresh that lost its rotation: not a fault.
        assertThat(AuditOutcome.of(new RetryableConflictException("overtaken by a concurrent request")))
                .isEqualTo(AuditOutcome.REFUSED);
    }

    @Test
    void GivenACallerOverTheRateLimit_WhenTheOutcomeIsClassified_ThenItIsARefusal() {
        // The limiter working as intended; counted as a fault, a guessing run would read as an outage.
        assertThat(AuditOutcome.of(new TooManyRequestsException(Duration.ofSeconds(30))))
                .isEqualTo(AuditOutcome.REFUSED);
    }

    @Test
    void GivenAFaultRatherThanADecision_WhenTheOutcomeIsClassified_ThenItIsAFailure() {
        assertThat(AuditOutcome.of(new IllegalStateException("connection pool exhausted")))
                .isEqualTo(AuditOutcome.FAILED);
    }
}
