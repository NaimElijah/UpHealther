package com.healthupgrades.admin.application;

import static net.logstash.logback.argument.StructuredArguments.keyValue;

import com.healthupgrades.admin.application.port.in.AccountPage;
import com.healthupgrades.admin.application.port.in.AdminUserCommand;
import com.healthupgrades.admin.application.port.in.AdminUserQuery;
import com.healthupgrades.auth.application.port.in.SessionCommand;
import com.healthupgrades.common.domain.audit.AuditAction;
import com.healthupgrades.common.domain.exception.BusinessRuleException;
import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.common.domain.port.out.AuditTrail;
import com.healthupgrades.user.application.port.in.UserCommand;
import com.healthupgrades.user.application.port.in.UserQuery;
import com.healthupgrades.user.domain.model.Role;
import com.healthupgrades.user.domain.model.User;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for account administration.
 *
 * <p>Owns no aggregate. It orchestrates over the {@code user} context's inbound ports for the account
 * itself and the {@code auth} context's for the sessions that account holds — the same two-layer shape
 * {@code auth} uses over {@code user}, and the reason this is a context of its own rather than a
 * handful of methods bolted onto {@code user}: the rules below are about administration, and none of
 * them is a rule about what a user <em>is</em>.
 *
 * <p>The one rule worth stating twice: <strong>an administrator may not act on their own account.</strong>
 * Not squeamishness — an administrator who disables themselves has locked the installation's last way
 * in, and one who revokes their own role cannot give it back. Both are unrecoverable without a database
 * console, which is not a support procedure.
 *
 * <p>Nothing here reads a user's health data, and there is no port through which it could. That is the
 * point of ADR-016: an administrator gains paths, not rows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminUserService implements AdminUserQuery, AdminUserCommand {

    /** The wording every self-directed change is refused with. */
    static final String NOT_YOUR_OWN_ACCOUNT = "An administrator cannot change their own account";

    private final UserQuery userQuery; // inbound read port of the user context
    private final UserCommand userCommand; // inbound write port of the user context
    private final SessionCommand sessions; // inbound write port of the auth context
    private final AuditTrail auditTrail; // records who changed whose account

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public AccountPage list(int page, int size) {
        return new AccountPage(userQuery.findAll(page, size), page, size, userQuery.count());
    }

    /**
     * {@inheritDoc}
     *
     * <p>The sessions are revoked inside the same transaction as the flag. Disabling an account whose
     * sessions survived would leave it signed in everywhere it already was — which is most of them.
     */
    @Override
    @Transactional
    public User disable(UUID actingAdminId, UUID targetUserId) {
        return auditTrail.recording(AuditAction.ADMIN_DISABLE, actingAdminId, targetUserId, () -> {
            User target = targetOf(actingAdminId, targetUserId);
            target.disable();
            User saved = userCommand.save(target);
            sessions.revokeAllForUser(targetUserId);
            return saved;
        });
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public User enable(UUID actingAdminId, UUID targetUserId) {
        return auditTrail.recording(AuditAction.ADMIN_ENABLE, actingAdminId, targetUserId, () -> {
            User target = targetOf(actingAdminId, targetUserId);
            target.enable();
            return userCommand.save(target);
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sessions are deliberately left alone. A demoted administrator is still a user, and their role
     * is read from the row on every request, so the change takes effect on their next call without
     * signing them out of anything.
     */
    @Override
    @Transactional
    public User changeRole(UUID actingAdminId, UUID targetUserId, Role role) {
        return auditTrail.recording(AuditAction.ADMIN_ROLE, actingAdminId, targetUserId, () -> {
            User target = targetOf(actingAdminId, targetUserId);
            target.changeRole(role);
            return userCommand.save(target);
        });
    }

    /**
     * Promotes one account to ADMIN, so a fresh installation has a way in.
     *
     * <p>Without this there is no route to the first administrator: only an administrator can grant the
     * role, and a new database has none. It is a bootstrap, not a policy, so it refuses everything but the
     * one case it exists for, and logs why rather than failing: refusing to start because an optional
     * bootstrap could not find its account would turn a typo into an outage.
     *
     * <p><strong>Only while there is no enabled administrator at all.</strong> Left unbounded, a setting
     * left in place would silently re-promote an account every restart, quietly undoing a deliberate
     * demotion. Once an administrator exists this does nothing.
     *
     * <p><strong>Only an enabled account.</strong> A disabled one would hold the role and still administer
     * nothing, and while no enabled administrator existed every start would promote it again.
     *
     * <p>The account is named by id, never by email, and the caller is the one that must keep it that way:
     * registration is open, so an address would go to whoever registered it first.
     *
     * @param accountId the account to promote
     * @throws org.springframework.dao.DataAccessException if the promotion cannot be saved; the attempt is
     *         audited as failed first
     */
    @Transactional
    public void bootstrap(UUID accountId) {
        // Enabled administrators, not merely accounts holding the role. Two administrators can disable
        // each other - neither is acting on their own account, so nothing refuses it - and the rows
        // then still say ADMIN while nobody can actually administer anything. Counting the disabled
        // ones would leave that installation recoverable only from a database console.
        if (userQuery.existsEnabledWithRole(Role.ADMIN)) {
            log.debug("An enabled administrator already exists; the bootstrap id was ignored");
            return;
        }

        Optional<User> account = userQuery.findById(accountId);
        if (account.isEmpty()) {
            log.warn(
                    "app.admin.bootstrap-user-id names no account; no administrator was created {}",
                    keyValue("userId", accountId));
            return;
        }
        // A disabled administrator administers nothing, so promoting this account would recover nothing
        // - and with still no enabled administrator, the next start would promote it all over again.
        if (!account.get().isEnabled()) {
            log.warn(
                    "app.admin.bootstrap-user-id names a disabled account; no administrator was created {}",
                    keyValue("userId", accountId));
            return;
        }

        User promoted = account.get();
        // Audited rather than logged (NFR-23): somebody gained the ability to disable every other account
        // on this installation. No person granted it - the deployment did - so the entry names no actor.
        auditTrail.recording(AuditAction.ADMIN_BOOTSTRAP, null, promoted.getId(), () -> {
            promoted.changeRole(Role.ADMIN);
            userCommand.save(promoted);
        });
    }

    /**
     * Finds the account an administrator is acting on, refusing the two cases that are not a change.
     *
     * @throws BusinessRuleException     if it is the administrator's own account (422)
     * @throws ResourceNotFoundException if no such account exists (404)
     */
    private User targetOf(UUID actingAdminId, UUID targetUserId) {
        if (actingAdminId.equals(targetUserId)) {
            throw new BusinessRuleException(NOT_YOUR_OWN_ACCOUNT);
        }
        return userQuery.findById(targetUserId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
