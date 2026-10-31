package com.healthupgrades.admin.adapter.in.bootstrap;

import static net.logstash.logback.argument.StructuredArguments.keyValue;

import com.healthupgrades.admin.application.AdminBootstrapService;
import com.healthupgrades.user.domain.model.AccountModifiedConcurrentlyException;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Promotes one account to ADMIN at startup, so a fresh installation has a way in.
 *
 * <p>A trigger: it reads {@code app.admin.bootstrap-user-id}, and hands a valid account id to
 * {@link AdminBootstrapService#bootstrap}, which decides whether the account is promoted and records it. The
 * rules live there, so a second trigger would apply the same ones.
 *
 * <p><strong>By account id, never by email.</strong> Registration is open, so naming an address here
 * would mean anybody who registered it first became the installation's administrator — a race decided
 * by whoever read the deployment configuration. An id names an account that already exists, and a value
 * that is not one is ignored here, before the use case is asked.
 */
@Component
@Slf4j
public class AdminBootstrapRunner implements ApplicationRunner {

    private final AdminBootstrapService bootstrap;
    private final String bootstrapUserId;

    /**
     * Creates the runner with the use case it triggers and the configured account.
     *
     * @param bootstrap the use case that decides whether the account is promoted
     * @param bootstrapUserId the account to promote, or blank — the normal case — to do nothing
     */
    public AdminBootstrapRunner(
            AdminBootstrapService bootstrap, @Value("${app.admin.bootstrap-user-id:}") String bootstrapUserId) {
        this.bootstrap = bootstrap;
        this.bootstrapUserId = bootstrapUserId;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Runs after the context is up and migrations have applied, so the account it names exists if it
     * is ever going to. A misconfiguration is logged and the application starts anyway: refusing to boot
     * because an optional bootstrap could not find its account would turn a typo into an outage.
     *
     * <p>A promotion that another write overtook is also logged, not fatal. Two instances starting together
     * both find no administrator and both promote the account; the account's version refuses the second
     * save (ADR-073), and that instance has nothing left to do. Failing its start would only have it
     * restart and find the administrator the first one made.
     */
    @Override
    public void run(ApplicationArguments args) {
        if (bootstrapUserId == null || bootstrapUserId.isBlank()) {
            return;
        }

        Optional<UUID> id = parse(bootstrapUserId);
        if (id.isEmpty()) {
            log.warn("app.admin.bootstrap-user-id is set but is not a valid account id; ignoring it");
            return;
        }

        try {
            bootstrap.bootstrap(id.get());
        } catch (AccountModifiedConcurrentlyException overtaken) {
            log.info(
                    "Another write to the bootstrap account, most likely another instance's bootstrap, committed"
                            + " first; this instance did not promote it {}",
                    keyValue("userId", id.get()));
        }
    }

    private static Optional<UUID> parse(String value) {
        try {
            return Optional.of(UUID.fromString(value.trim()));
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
    }
}
