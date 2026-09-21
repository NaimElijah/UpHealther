package com.healthupgrades.admin.adapter.in.bootstrap;

import com.healthupgrades.support.AUser;
import com.healthupgrades.user.application.port.in.UserCommand;
import com.healthupgrades.user.application.port.in.UserQuery;
import com.healthupgrades.user.domain.model.Role;
import com.healthupgrades.user.domain.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
 */
@ExtendWith(MockitoExtension.class)
class AdminBootstrapRunnerTest {

    private static final UUID BOOTSTRAP_ID = UUID.fromString("0f2c8f5a-2a4e-4a1d-8f0a-3c5b9d1e77a1");

    @Mock UserQuery userQuery;
    @Mock UserCommand userCommand;

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
    void GivenAnAdministratorAlreadyExists_WhenTheRunnerRuns_ThenNobodyIsPromoted() {
        // The property that makes it safe to leave the variable set: a deliberate demotion is not undone
        // by the next restart.
        when(userQuery.existsEnabledWithRole(Role.ADMIN)).thenReturn(true);

        run(BOOTSTRAP_ID.toString());

        verify(userCommand, never()).save(any());
    }

    @Test
    void GivenEveryAdministratorIsDisabled_WhenTheRunnerRuns_ThenItPromotesTheBootstrapAccount() {
        // Two administrators can disable each other - neither is acting on their own account, so nothing
        // refuses it - leaving rows that say ADMIN and an installation nobody can administer. Counting
        // those would leave it recoverable only from a database console.
        User account = AUser.withId(BOOTSTRAP_ID);
        when(userQuery.existsEnabledWithRole(Role.ADMIN)).thenReturn(false);
        when(userQuery.findById(BOOTSTRAP_ID)).thenReturn(Optional.of(account));

        run(BOOTSTRAP_ID.toString());

        assertThat(account.getRole()).isEqualTo(Role.ADMIN);
        verify(userCommand).save(account);
    }

    @Test
    void GivenNoBootstrapIdIsConfigured_WhenTheRunnerRuns_ThenNothingIsReadOrWritten() {
        // The normal case, on every start of every installation that already has an administrator.
        run("");

        verify(userQuery, never()).existsEnabledWithRole(any());
        verify(userCommand, never()).save(any());
    }

    @Test
    void GivenAnIdThatNamesNoAccount_WhenTheRunnerRuns_ThenItWarnsAndTheApplicationStillStarts() {
        when(userQuery.existsEnabledWithRole(Role.ADMIN)).thenReturn(false);
        when(userQuery.findById(BOOTSTRAP_ID)).thenReturn(Optional.empty());

        assertThatCode(() -> run(BOOTSTRAP_ID.toString())).doesNotThrowAnyException();

        verify(userCommand, never()).save(any());
    }

    @Test
    void GivenSomethingThatIsNotAnId_WhenTheRunnerRuns_ThenItIsIgnoredWithoutFailingTheBoot() {
        // An email is the mistake this is most likely to be, and the one it must not act on: registration
        // is open, so an address names whoever got there first rather than a particular account.
        assertThatCode(() -> run("someone@example.com")).doesNotThrowAnyException();

        verify(userQuery, never()).findById(any());
        verify(userCommand, never()).save(any());
    }

    private void run(String bootstrapUserId) {
        new AdminBootstrapRunner(userQuery, userCommand, bootstrapUserId).run(null);
    }
}
