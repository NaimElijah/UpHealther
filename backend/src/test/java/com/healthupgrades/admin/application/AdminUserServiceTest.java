package com.healthupgrades.admin.application;

import com.healthupgrades.auth.application.port.in.SessionCommand;
import com.healthupgrades.common.domain.audit.AuditAction;
import com.healthupgrades.common.domain.audit.AuditOutcome;
import com.healthupgrades.common.domain.exception.BusinessRuleException;
import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.support.AUser;
import com.healthupgrades.support.RecordingAuditTrail;
import com.healthupgrades.user.application.port.in.UserCommand;
import com.healthupgrades.user.application.port.in.UserQuery;
import com.healthupgrades.user.domain.model.Role;
import com.healthupgrades.user.domain.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What an administrator may do to another account, and the two things they may not do at all.
 *
 * <p>The refusals are the interesting half. An administrator who disables their own account has locked
 * the installation's last way in, and one who revokes their own role cannot give it back — both are
 * unrecoverable without a database console, so both are refused before anything is written.
 *
 * <p>The other property worth pinning is that disabling ends the account's sessions. Without it,
 * "disabled" would mean "cannot sign in again", and an account already signed in on three devices would
 * stay signed in on all three until its tokens expired.
 */
@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    private static final UUID ADMIN_ID = UUID.fromString("0f2c8f5a-2a4e-4a1d-8f0a-3c5b9d1e77a1");
    private static final UUID TARGET_ID = UUID.fromString("6b1f0f4c-9a2d-4c3e-9b7a-1d2e3f4a5b6c");

    @Mock UserQuery userQuery;
    @Mock UserCommand userCommand;
    @Mock SessionCommand sessions;

    private final RecordingAuditTrail auditTrail = new RecordingAuditTrail();

    private AdminUserService service;

    @BeforeEach
    void setUp() {
        service = new AdminUserService(userQuery, userCommand, sessions, auditTrail);
        // Saving answers with what it was handed, as the repository does. Lenient because the refusal
        // tests never reach it - which is the point of them.
        lenient().when(userCommand.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void GivenAnEnabledAccount_WhenAnAdministratorDisablesIt_ThenItIsSwitchedOffAndItsSessionsEnd() {
        User target = registered();

        service.disable(ADMIN_ID, TARGET_ID);

        assertThat(target.isEnabled()).isFalse();
        verify(userCommand).save(target);
        verify(sessions)
                .revokeAllForUser(TARGET_ID);
        assertThat(auditTrail.recorded(AuditAction.ADMIN_DISABLE, AuditOutcome.ALLOWED)).isTrue();
    }

    @Test
    void GivenADisabledAccount_WhenAnAdministratorEnablesIt_ThenItWorksAgainWithEverythingItOwned() {
        User target = registered();
        target.disable();

        service.enable(ADMIN_ID, TARGET_ID);

        assertThat(target.isEnabled()).isTrue();
        assertThat(auditTrail.recorded(AuditAction.ADMIN_ENABLE, AuditOutcome.ALLOWED)).isTrue();
    }

    @Test
    void GivenAnOrdinaryAccount_WhenAnAdministratorPromotesIt_ThenItHoldsTheAdminRole() {
        User target = registered();

        service.changeRole(ADMIN_ID, TARGET_ID, Role.ADMIN);

        assertThat(target.getRole()).isEqualTo(Role.ADMIN);
        assertThat(auditTrail.recorded(AuditAction.ADMIN_ROLE, AuditOutcome.ALLOWED)).isTrue();
    }

    @Test
    void GivenARoleChange_WhenItIsApplied_ThenTheAccountIsNotSignedOut() {
        // A demoted administrator is still a user, and their role is read from the row on every request,
        // so the change lands on their next call without taking their session away.
        registered();

        service.changeRole(ADMIN_ID, TARGET_ID, Role.USER);

        verify(sessions, never()).revokeAllForUser(any());
    }

    @Test
    void GivenTheirOwnAccount_WhenAnAdministratorDisablesIt_ThenItIsRefusedAndNothingIsWritten() {
        assertThatThrownBy(() -> service.disable(ADMIN_ID, ADMIN_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage(AdminUserService.NOT_YOUR_OWN_ACCOUNT);

        verify(userCommand, never()).save(any());
        verify(sessions, never()).revokeAllForUser(any());
        assertThat(auditTrail.recorded(AuditAction.ADMIN_DISABLE, AuditOutcome.REFUSED)).isTrue();
    }

    @Test
    void GivenTheirOwnAccount_WhenAnAdministratorRevokesTheirRole_ThenItIsRefused() {
        // The one that cannot be undone from inside the application: no administrator, no way to make
        // one, and the bootstrap only acts when there are none at all.
        assertThatThrownBy(() -> service.changeRole(ADMIN_ID, ADMIN_ID, Role.USER))
                .isInstanceOf(BusinessRuleException.class);

        verify(userCommand, never()).save(any());
    }

    @Test
    void GivenTheirOwnAccount_WhenAnAdministratorEnablesIt_ThenItIsRefusedToo() {
        // Harmless in itself, and refused anyway: the rule is "not your own account", not "not the
        // dangerous half of your own account". A rule with an exception is a rule nobody can state.
        assertThatThrownBy(() -> service.enable(ADMIN_ID, ADMIN_ID))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void GivenAnAccountThatDoesNotExist_WhenAnAdministratorDisablesIt_ThenItIsNotFound() {
        when(userQuery.findById(TARGET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.disable(ADMIN_ID, TARGET_ID))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(sessions, never()).revokeAllForUser(any());
    }

    @Test
    void GivenAccountsExist_WhenTheyAreListed_ThenThePageCarriesTheTotalBehindIt() {
        // Without the total a caller cannot tell twenty-five of twenty-six from twenty-five of a
        // thousand, and has to page until a short one comes back to find out.
        List<User> firstPage = List.of(AUser.withId(UUID.randomUUID()), AUser.withId(UUID.randomUUID()));
        when(userQuery.findAll(0, 25)).thenReturn(firstPage);
        when(userQuery.count()).thenReturn(137L);

        AccountPage page = service.list(0, 25);

        assertThat(page.accounts()).isEqualTo(firstPage);
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(25);
        assertThat(page.total()).isEqualTo(137L);
    }

    /** An account for the administrator to act on, found by the id the tests use. */
    private User registered() {
        User target = AUser.withId(TARGET_ID);
        when(userQuery.findById(TARGET_ID)).thenReturn(Optional.of(target));
        return target;
    }
}
