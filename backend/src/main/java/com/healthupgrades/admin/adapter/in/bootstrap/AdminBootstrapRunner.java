package com.healthupgrades.admin.adapter.in.bootstrap;

import com.healthupgrades.user.application.port.in.UserCommand;
import com.healthupgrades.user.application.port.in.UserQuery;
import com.healthupgrades.user.domain.model.Role;
import com.healthupgrades.user.domain.model.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.keyValue;

/**
 * Promotes one account to ADMIN at startup, so a fresh installation has a way in.
 *
 * <p>Without this there is no route to the first administrator: only an administrator can grant the
 * role, and a new database has none. The alternative is editing a row by hand, which is a support
 * procedure nobody writes down.
 *
 * <p><strong>By account id, never by email.</strong> Registration is open, so naming an address here
 * would mean anybody who registered it first became the installation's administrator — a race decided
 * by whoever read the deployment configuration. An id names an account that already exists.
 *
 * <p><strong>Only while there is no administrator at all.</strong> Left unbounded, an environment
 * variable set once would silently re-promote an account every restart, quietly undoing a deliberate
 * demotion. It is a bootstrap, not a policy: once an administrator exists, this does nothing, and the
 * variable can be left in place or removed without effect.
 */
@Component
@Slf4j
public class AdminBootstrapRunner implements ApplicationRunner {

    private final UserQuery userQuery;
    private final UserCommand userCommand;
    private final String bootstrapUserId;

    /**
     * @param bootstrapUserId the account to promote, or blank — the normal case — to do nothing
     */
    public AdminBootstrapRunner(UserQuery userQuery, UserCommand userCommand,
                                @Value("${app.admin.bootstrap-user-id:}") String bootstrapUserId) {
        this.userQuery = userQuery;
        this.userCommand = userCommand;
        this.bootstrapUserId = bootstrapUserId;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Runs after the context is up and migrations have applied, so the account it names exists if it
     * is ever going to. A misconfiguration is logged and the application starts anyway: refusing to boot
     * because an optional bootstrap could not find its account would turn a typo into an outage.
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (bootstrapUserId == null || bootstrapUserId.isBlank()) {
            return;
        }

        Optional<UUID> id = parse(bootstrapUserId);
        if (id.isEmpty()) {
            log.warn("app.admin.bootstrap-user-id is set but is not a valid account id; ignoring it");
            return;
        }

        // Enabled administrators, not merely accounts holding the role. Two administrators can disable
        // each other - neither is acting on their own account, so nothing refuses it - and the rows
        // then still say ADMIN while nobody can actually administer anything. Counting the disabled
        // ones would leave that installation recoverable only from a database console.
        if (userQuery.existsEnabledWithRole(Role.ADMIN)) {
            log.debug("An enabled administrator already exists; the bootstrap id was ignored");
            return;
        }

        Optional<User> account = userQuery.findById(id.get());
        if (account.isEmpty()) {
            log.warn("app.admin.bootstrap-user-id names no account; no administrator was created {}",
                    keyValue("userId", id.get()));
            return;
        }

        User promoted = account.get();
        promoted.changeRole(Role.ADMIN);
        userCommand.save(promoted);
        // INFO and loud: somebody gained the ability to disable every other account on this
        // installation, and the only other record of it is the row itself.
        log.info("Promoted the bootstrap account to administrator {}", keyValue("userId", promoted.getId()));
    }

    private static Optional<UUID> parse(String value) {
        try {
            return Optional.of(UUID.fromString(value.trim()));
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
    }
}
