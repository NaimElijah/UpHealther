package com.healthupgrades.admin.adapter.in.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.healthupgrades.admin.application.AdminBootstrapService;
import com.healthupgrades.common.domain.audit.AuditAction;
import com.healthupgrades.common.domain.audit.AuditEvent;
import com.healthupgrades.common.domain.audit.AuditOutcome;
import com.healthupgrades.support.AUser;
import com.healthupgrades.support.RecordingAuditTrail;
import com.healthupgrades.user.application.port.in.UserCommand;
import com.healthupgrades.user.application.port.in.UserQuery;
import com.healthupgrades.user.domain.model.AccountModifiedConcurrentlyException;
import com.healthupgrades.user.domain.model.Role;
import com.healthupgrades.user.domain.model.User;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * The way a fresh installation gets its first administrator, and the three ways it deliberately does
 * not.
 *
 * <p>Only an administrator can grant the role, and a new database has none, so without this there is no
 * route to the first one short of editing a row by hand. That makes it a privilege escalation path by
 * design — which is exactly why its limits are worth pinning:
 *
 * <ul>
 *   <li>it names an <strong>account id</strong>, never an email, because registration is open and an
 *       address would go to whoever claimed it first;</li>
 *   <li>it acts <strong>only while no administrator exists</strong>, so a variable left set does not
 *       quietly re-promote somebody after a deliberate demotion;</li>
 *   <li>it <strong>never fails the boot</strong>, so a typo in a deployment variable is a warning
 *       rather than an outage.</li>
 * </ul>
 *
 * <p>Driven through the runner, over the real {@link AdminBootstrapService} that holds these rules since
 * #338, so the parsing the runner keeps and the rules behind it are covered together; only the
 * ports are mocked.
 */
@ExtendWith(MockitoExtension.class)
class AdminBootstrapRunnerTest {

    private static final UUID BOOTSTRAP_ID = UUID.fromString("0f2c8f5a-2a4e-4a1d-8f0a-3c5b9d1e77a1");

    @Mock
    UserQuery userQuery;

    @Mock
    UserCommand userCommand;

    /** A real one, not a mock: AuditTrail.recording is a default method, and a mock would skip the work. */
    private final RecordingAuditTrail auditTrail = new RecordingAuditTrail();

    @Test
    void GivenAnInstallationWithNoAdministrator_WhenTheNamedAccountExists_ThenItIsPromoted() {
        User account = AUser.withId(BOOTSTRAP_ID);
        when(userQuery.existsEnabledWithRole(Role.ADMIN)).thenReturn(false);
        when(userQuery.findById(BOOTSTRAP_ID)).thenReturn(Optional.of(account));

        run(BOOTSTRAP_ID.toString());

        assertThat(account.getRole()).isEqualTo(Role.ADMIN);
        verify(userCommand).save(account);
    }

    @Test
    void GivenAnInstallationWithNoAdministrator_WhenTheNamedAccountIsPromoted_ThenThePromotionIsAuditedWithNoActor() {
        // NFR-23. Somebody just gained the power to disable every other account, and until now the only
        // record of it was the row itself. No person granted it - the deployment did - so no actor is named.
        when(userQuery.existsEnabledWithRole(Role.ADMIN)).thenReturn(false);
        when(userQuery.findById(BOOTSTRAP_ID)).thenReturn(Optional.of(AUser.withId(BOOTSTRAP_ID)));

        run(BOOTSTRAP_ID.toString());

        assertThat(auditTrail.only(AuditAction.ADMIN_BOOTSTRAP))
                .hasValue(AuditEvent.allowed(AuditAction.ADMIN_BOOTSTRAP, null, BOOTSTRAP_ID));
    }

    @Test
    void GivenThePromotionCannotBeSaved_WhenTheRunnerRuns_ThenTheAttemptIsAuditedAsFailed() {
        User account = AUser.withId(BOOTSTRAP_ID);
        when(userQuery.existsEnabledWithRole(Role.ADMIN)).thenReturn(false);
        when(userQuery.findById(BOOTSTRAP_ID)).thenReturn(Optional.of(account));
        when(userCommand.save(account))
                .thenThrow(new DataAccessResourceFailureException("the database is unreachable"));

        assertThatThrownBy(() -> run(BOOTSTRAP_ID.toString())).isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(auditTrail.only(AuditAction.ADMIN_BOOTSTRAP))
                .hasValue(new AuditEvent(AuditAction.ADMIN_BOOTSTRAP, null, BOOTSTRAP_ID, AuditOutcome.FAILED));
    }

    @Test
    void GivenAnotherInstancePromotedTheAccountFirst_WhenTheRunnerRuns_ThenItLogsAtInfoAndTheApplicationStillStarts() {
        // Two replicas starting together both find no administrator and both promote the account. The
        // version refuses the second save (ADR-073); that instance has nothing left to do, so the refusal
        // is a fact to note, not a reason to fail its start.
        User account = AUser.withId(BOOTSTRAP_ID);
        when(userQuery.existsEnabledWithRole(Role.ADMIN)).thenReturn(false);
        when(userQuery.findById(BOOTSTRAP_ID)).thenReturn(Optional.of(account));
        when(userCommand.save(account)).thenThrow(new AccountModifiedConcurrentlyException());
        Logger runnerLogger = (Logger) LoggerFactory.getLogger(AdminBootstrapRunner.class);
        ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        runnerLogger.addAppender(lines);

        try {
            assertThatCode(() -> run(BOOTSTRAP_ID.toString())).doesNotThrowAnyException();
        } finally {
            runnerLogger.detachAppender(lines);
        }

        assertThat(lines.list)
                .singleElement()
                .extracting(ILoggingEvent::getLevel)
                .isEqualTo(Level.INFO);
        assertThat(auditTrail.only(AuditAction.ADMIN_BOOTSTRAP))
                .hasValue(new AuditEvent(AuditAction.ADMIN_BOOTSTRAP, null, BOOTSTRAP_ID, AuditOutcome.REFUSED));
    }

    @Test
    void GivenAnAdministratorAlreadyExists_WhenTheRunnerRuns_ThenNobodyIsPromoted() {
        // The property that makes it safe to leave the variable set: a deliberate demotion is not undone
        // by the next restart.
        when(userQuery.existsEnabledWithRole(Role.ADMIN)).thenReturn(true);

        run(BOOTSTRAP_ID.toString());

        verify(userCommand, never()).save(any());
        assertThat(auditTrail.recorded()).isEmpty();
    }

    @Test
    void GivenTheNamedAccountIsDisabled_WhenTheRunnerRuns_ThenItIsNotPromotedAndNothingIsAudited() {
        // A disabled administrator administers nothing, so promoting one recovers nothing - and while no
        // enabled administrator exists the runner would do it again on every restart, each time recording
        // a grant that changed nothing. Found in the review of #132.
        User disabled = AUser.aUser().id(BOOTSTRAP_ID).enabled(false).build();
        when(userQuery.existsEnabledWithRole(Role.ADMIN)).thenReturn(false);
        when(userQuery.findById(BOOTSTRAP_ID)).thenReturn(Optional.of(disabled));

        assertThatCode(() -> run(BOOTSTRAP_ID.toString())).doesNotThrowAnyException();

        assertThat(disabled.getRole()).isNotEqualTo(Role.ADMIN);
        verify(userCommand, never()).save(any());
        assertThat(auditTrail.recorded()).isEmpty();
    }

    @Test
    void GivenNoBootstrapIdIsConfigured_WhenTheRunnerRuns_ThenNothingIsReadOrWritten() {
        // The normal case, on every start of every installation that already has an administrator.
        run("");

        verify(userQuery, never()).existsEnabledWithRole(any());
        verify(userCommand, never()).save(any());
        assertThat(auditTrail.recorded()).isEmpty();
    }

    @Test
    void GivenAnIdThatNamesNoAccount_WhenTheRunnerRuns_ThenItWarnsAndTheApplicationStillStarts() {
        when(userQuery.existsEnabledWithRole(Role.ADMIN)).thenReturn(false);
        when(userQuery.findById(BOOTSTRAP_ID)).thenReturn(Optional.empty());

        assertThatCode(() -> run(BOOTSTRAP_ID.toString())).doesNotThrowAnyException();

        verify(userCommand, never()).save(any());
        assertThat(auditTrail.recorded()).isEmpty();
    }

    @Test
    void GivenSomethingThatIsNotAnId_WhenTheRunnerRuns_ThenItIsIgnoredWithoutFailingTheBoot() {
        // An email is the mistake this is most likely to be, and the one it must not act on: registration
        // is open, so an address names whoever got there first rather than a particular account.
        assertThatCode(() -> run("someone@example.com")).doesNotThrowAnyException();

        verify(userQuery, never()).findById(any());
        verify(userCommand, never()).save(any());
        assertThat(auditTrail.recorded()).isEmpty();
    }

    private void run(String bootstrapUserId) {
        new AdminBootstrapRunner(new AdminBootstrapService(userQuery, userCommand, auditTrail), bootstrapUserId)
                .run(null);
    }
}
