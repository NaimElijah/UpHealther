package com.healthupgrades.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.healthupgrades.admin.application.AdminUserService;
import com.healthupgrades.common.domain.exception.ConflictException;
import com.healthupgrades.support.AUser;
import com.healthupgrades.support.PostgresIT;
import com.healthupgrades.user.application.port.in.UserCommand;
import com.healthupgrades.user.application.port.in.UserQuery;
import com.healthupgrades.user.domain.model.AccountModifiedConcurrentlyException;
import com.healthupgrades.user.domain.model.Role;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * NFR-34 and ADR-073 through the use case: an administrator's disable overtaken by another write to the
 * same account is refused, audited as a refusal, and leaves the other write standing.
 *
 * <p>The disable revokes the account's sessions after its save, through a {@code @Modifying} query that
 * flushes first. Before the account save flushed on its own, that query was where the version check ran,
 * inside the audited body, and the clash reached {@code AuditOutcome.of} as Spring's optimistic-lock
 * exception: recorded {@code FAILED}, the signal that something broke, for two administrators acting at
 * once. Only a real transaction manager and a real version column show which way it goes.
 *
 * <p>The interleaving is forced in one thread, as {@code UserConcurrentWritesIT} forces it: an outer
 * transaction loads the account, a {@code REQUIRES_NEW} one changes its role through the same service and
 * commits, and only then does the disable run, joining the outer transaction and finding the copy loaded
 * before the role change. That is the state a disable is in when another administrator's write commits
 * between its read and its write.
 */
@SpringBootTest
class AdminDisableRaceIT extends PostgresIT {

    private static final String AUDIT_LOGGER = "AUDIT";

    @Autowired
    AdminUserService adminUserService;

    @Autowired
    UserCommand userCommand;

    @Autowired
    UserQuery userQuery;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    DataSource dataSource;

    private final UUID disablingAdminId = UUID.randomUUID();
    private final UUID promotingAdminId = UUID.randomUUID();
    private Logger auditLogger;
    private ListAppender<ILoggingEvent> appender;
    private UUID targetId;

    @BeforeEach
    void anEnabledAccountAndTheAuditStream() {
        targetId = userCommand
                .save(AUser.aUser()
                        .id(null)
                        .email("admin-race-" + UUID.randomUUID() + "@example.com")
                        .build())
                .getId();

        auditLogger = (Logger) LoggerFactory.getLogger(AUDIT_LOGGER);
        appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
    }

    @AfterEach
    void releaseTheAuditStream() {
        auditLogger.detachAppender(appender);
    }

    @Test
    void
            GivenARoleChangeCommittedAfterTheDisableLoadedTheAccount_WhenTheDisableSaves_ThenItIsRefusedAndAuditedAsARefusal() {
        assertThatThrownBy(this::disableOvertakenByARoleChange)
                .as("a ConflictException, which GlobalExceptionHandler answers 409")
                .isInstanceOf(ConflictException.class)
                .isInstanceOf(AccountModifiedConcurrentlyException.class);

        assertThat(linesFor("admin.disable"))
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("outcome=REFUSED");

        Map<String, Object> row = storedRow();
        assertThat(row.get("enabled")).as("the overtaken disable did not land").isEqualTo(true);
        assertThat(row.get("role")).as("the overtaking role change stands").isEqualTo(Role.ADMIN.name());
    }

    /**
     * Loads the account in an outer transaction, commits another administrator's role change in a
     * transaction of its own, then disables the account inside the outer one.
     */
    private void disableOvertakenByARoleChange() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        TransactionTemplate overtaking = new TransactionTemplate(transactionManager);
        overtaking.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        outer.executeWithoutResult(status -> {
            userQuery.findById(targetId).orElseThrow();
            overtaking.executeWithoutResult(
                    nested -> adminUserService.changeRole(promotingAdminId, targetId, Role.ADMIN));
            adminUserService.disable(disablingAdminId, targetId);
        });
    }

    private List<String> linesFor(String action) {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("action=" + action + " "))
                .toList();
    }

    private Map<String, Object> storedRow() {
        return new JdbcTemplate(dataSource).queryForMap("SELECT role, enabled FROM users WHERE id = ?", targetId);
    }
}
