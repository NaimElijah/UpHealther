package com.healthupgrades.admin.application;

import static net.logstash.logback.argument.StructuredArguments.keyValue;

import com.healthupgrades.common.domain.audit.AuditAction;
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
 * The administrator bootstrap: the one use case that grants ADMIN with no administrator asking.
 *
 * <p>A service of its own rather than a method on {@code AdminUserService}, because that service is behind
 * the administration endpoints. There, any class of this context could call it, and its only guard — that
 * no enabled administrator exists yet — would make any signed-in user the first administrator of a fresh
 * installation. Only {@code AdminBootstrapRunner} holds this one, which {@code HexagonalArchitectureTest}
 * enforces.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrapService {

    private final UserQuery userQuery;
    private final UserCommand userCommand;
    private final AuditTrail auditTrail; // records the promotion, the one thing this changes

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
     * @throws com.healthupgrades.user.domain.model.AccountModifiedConcurrentlyException if another write
     *         to the account, a second instance's bootstrap most likely, committed after it was read. The
     *         save flushes, so this is raised inside the audited body and recorded {@code REFUSED}
     * @throws org.springframework.dao.DataAccessException if the promotion cannot be written. Recorded
     *         {@code FAILED} when the save raises it; one raised at commit, after this method has returned,
     *         propagates from the transaction proxy, and {@code LoggingAuditTrail} records the attempt
     *         {@code REFUSED} when the transaction rolls back
     * @throws org.springframework.transaction.TransactionException on the same terms as
     *         {@link org.springframework.dao.DataAccessException}
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
}
