package com.healthupgrades.common.domain.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.healthupgrades.common.domain.audit.AuditAction;
import com.healthupgrades.common.domain.audit.AuditEvent;
import com.healthupgrades.common.domain.audit.AuditOutcome;
import com.healthupgrades.common.domain.exception.BusinessRuleException;
import com.healthupgrades.support.RecordingAuditTrail;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins the wrapper every service records through.
 *
 * <p>The behaviour worth guarding is the failure half. A trail that records only what succeeded is not
 * a trail — an ownership check that refused and a rule that rejected a transition are the entries
 * somebody goes looking for — and it is the half that is easy to leave out and impossible to notice
 * missing, because everything still works.
 */
class AuditTrailTest {

    private final RecordingAuditTrail trail = new RecordingAuditTrail();
    private final UUID actor = UUID.randomUUID();
    private final UUID resource = UUID.randomUUID();

    @Test
    void GivenAnOperationThatSucceeds_WhenItIsRecorded_ThenItsResultIsReturnedAndTheAttemptAllowed() {
        String result = trail.recording(AuditAction.UPGRADE_ACTIVATE, actor, resource, () -> "activated");

        assertThat(result).isEqualTo("activated");
        assertThat(trail.only(AuditAction.UPGRADE_ACTIVATE))
                .hasValue(new AuditEvent(AuditAction.UPGRADE_ACTIVATE, actor, resource, AuditOutcome.ALLOWED));
    }

    @Test
    void GivenARuleThatRefuses_WhenItIsRecorded_ThenTheRefusalIsKeptAndTheExceptionRethrown() {
        BusinessRuleException refusal = new BusinessRuleException("a completed upgrade cannot be paused");

        assertThatThrownBy(() -> trail.recording(AuditAction.UPGRADE_PAUSE, actor, resource, () -> {
                    throw refusal;
                }))
                .isSameAs(refusal); // rethrown untouched: this observes the operation, it does not handle it

        assertThat(trail.only(AuditAction.UPGRADE_PAUSE))
                .hasValue(new AuditEvent(AuditAction.UPGRADE_PAUSE, actor, resource, AuditOutcome.REFUSED));
    }

    @Test
    void GivenAFaultRatherThanARefusal_WhenItIsRecorded_ThenTheAttemptIsRecordedAsFailed() {
        assertThatThrownBy(() -> trail.recording(AuditAction.UPGRADE_UPDATE, actor, resource, () -> {
                    throw new IllegalStateException("connection pool exhausted");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(trail.recorded(AuditAction.UPGRADE_UPDATE, AuditOutcome.FAILED))
                .isTrue();
    }

    @Test
    void GivenAnOperationThatThrowsAnError_WhenItIsRecorded_ThenTheAttemptIsRecordedAsFailedAndTheErrorRethrown() {
        // D1.4, NFR-23. An Error is no RuntimeException, so a catch of only that let the attempt pass
        // unrecorded: the one entry that says something broke was the one missing.
        StackOverflowError broke = new StackOverflowError();

        assertThatThrownBy(() -> trail.recording(AuditAction.UPGRADE_UPDATE, actor, resource, () -> {
                    throw broke;
                }))
                .isSameAs(broke);

        assertThat(trail.only(AuditAction.UPGRADE_UPDATE))
                .hasValue(new AuditEvent(AuditAction.UPGRADE_UPDATE, actor, resource, AuditOutcome.FAILED));
    }

    @Test
    void GivenACreationThatThrowsAnError_WhenItIsRecorded_ThenTheAttemptIsRecordedAsFailedAndTheErrorRethrown() {
        StackOverflowError broke = new StackOverflowError();

        assertThatThrownBy(() -> trail.recordingCreation(
                        AuditAction.UPGRADE_CREATE,
                        actor,
                        () -> {
                            throw broke;
                        },
                        created -> resource))
                .isSameAs(broke);

        assertThat(trail.only(AuditAction.UPGRADE_CREATE))
                .hasValue(new AuditEvent(AuditAction.UPGRADE_CREATE, actor, null, AuditOutcome.FAILED));
    }

    @Test
    void GivenAnOperationThatReturnsNothing_WhenItIsRecorded_ThenItStillRunsAndIsRecorded() {
        StringBuilder ran = new StringBuilder();

        trail.recording(AuditAction.UPGRADE_DELETE, actor, resource, () -> ran.append("deleted"));

        assertThat(ran).hasToString("deleted");
        assertThat(trail.recorded(AuditAction.UPGRADE_DELETE, AuditOutcome.ALLOWED))
                .isTrue();
    }

    @Test
    void GivenAVoidOperationThatRefuses_WhenItIsRecorded_ThenTheRefusalIsKept() {
        assertThatThrownBy(() -> trail.recording(AuditAction.AREA_DELETE, actor, resource, () -> {
                    throw new BusinessRuleException("nope");
                }))
                .isInstanceOf(BusinessRuleException.class);

        assertThat(trail.recorded(AuditAction.AREA_DELETE, AuditOutcome.REFUSED))
                .isTrue();
    }
}
