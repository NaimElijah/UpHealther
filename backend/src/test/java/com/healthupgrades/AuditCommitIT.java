package com.healthupgrades;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.healthupgrades.support.PostgresIT;
import com.healthupgrades.upgrade.application.UpgradeService;
import com.healthupgrades.upgrade.application.port.in.UpgradeDetails;
import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import com.healthupgrades.upgrade.domain.model.UpgradeType;
import com.healthupgrades.upgrade.domain.port.out.UpgradeRepositoryPort;
import com.healthupgrades.user.application.port.in.UserCommand;
import com.healthupgrades.user.domain.model.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins <em>when</em> an {@code ALLOWED} audit entry is written, which turns out to be the whole of
 * whether the trail can be believed.
 *
 * <p>Every audited use case is an {@code @Transactional} service method, and the recording happens
 * inside the method body — so it runs while the transaction is still open, and the commit happens
 * afterwards in the proxy. No repository adapter flushes, so a {@code @Version} clash or a unique
 * constraint losing a race is decided <em>at commit</em>, after the body has returned. Writing the
 * entry there would have the trail and the {@code audit.events} counter both report an edit that was
 * then rolled back and answered to the caller as a 409.
 *
 * <p>That is not a failure any unit test can see: mocked repositories commit nothing, so the bug is
 * invisible everywhere except against a real transaction manager. This class supplies one and forces
 * the rollback deterministically, rather than racing two threads for an optimistic-lock clash and
 * hoping.
 */
@SpringBootTest
class AuditCommitIT extends PostgresIT {

    private static final String AUDIT_LOGGER = "AUDIT";

    @Autowired UpgradeService upgradeService;
    @Autowired UpgradeRepositoryPort upgradeRepository;
    @Autowired UserCommand userCommand;
    @Autowired TransactionTemplate transactionTemplate;

    private Logger auditLogger;
    private ListAppender<ILoggingEvent> appender;
    private UUID userId;

    @BeforeEach
    void captureTheAuditStream() {
        userId = userCommand.save(User.builder()
                .name("Someone")
                .email(UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$abcdefghijklmnopqrstuvwxyz012345678901234567890123456")
                .build()).getId();

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
    void GivenWorkThatCommits_WhenItIsAudited_ThenTheEntryIsWrittenAndSaysAllowed() {
        HealthUpgrade created = upgradeService.create(userId, details("Committed upgrade"));

        assertThat(linesFor("upgrade.create"))
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("outcome=ALLOWED")
                // The created id is on the entry: an entry that says somebody created something without
                // saying what is the one question a creation entry exists to answer.
                .contains("resourceId=" + created.getId());
    }

    @Test
    void GivenTheTransactionRollsBackAfterTheWork_WhenItIsAudited_ThenNothingClaimsItWasAllowed() {
        // The defect this class exists for. Before the entry was deferred to the commit, the body
        // returning was enough to write ALLOWED and increment the counter — so a 409 from an optimistic
        // lock, or a unique constraint lost at commit, left a trail saying the edit had succeeded.
        UUID upgradeId = upgradeService.create(userId, details("Doomed upgrade")).getId();
        appender.list.clear();

        transactionTemplate.execute(status -> {
            upgradeService.update(userId, upgradeId, details("Renamed inside a doomed transaction"));
            status.setRollbackOnly();
            return null;
        });

        assertThat(linesFor("upgrade.update"))
                .as("an entry is still written — the attempt happened and should be recorded")
                .isNotEmpty()
                .as("but it must not claim the edit landed, because it did not")
                .allSatisfy(line -> assertThat(line).doesNotContain("outcome=ALLOWED"));
    }

    @Test
    void GivenTheTransactionRollsBack_WhenItIsAudited_ThenTheRecordIsUnchangedToMatch() {
        // The other half of the same claim: the trail and the database agree about what happened.
        UUID upgradeId = upgradeService.create(userId, details("Original title")).getId();

        transactionTemplate.execute(status -> {
            upgradeService.update(userId, upgradeId, details("Never persisted"));
            status.setRollbackOnly();
            return null;
        });

        assertThat(upgradeRepository.findByIdAndUserId(upgradeId, userId).orElseThrow().getTitle())
                .isEqualTo("Original title");
    }

    @Test
    void GivenARefusalInsideATransaction_WhenItIsAudited_ThenItIsWrittenWithoutWaitingForACommit() {
        // Refusals are not deferred: they already describe an attempt that did not land, they are the
        // security-relevant half, and a process that dies before commit should not take them with it.
        UUID missing = UUID.randomUUID();

        assertThat(catchThrowable(() -> upgradeService.pause(userId, missing))).isNotNull();

        assertThat(linesFor("upgrade.pause"))
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("outcome=REFUSED");
    }

    private static Throwable catchThrowable(Runnable work) {
        try {
            work.run();
            return null;
        } catch (RuntimeException thrown) {
            return thrown;
        }
    }

    private List<String> linesFor(String action) {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("action=" + action + " "))
                .toList();
    }

    private static UpgradeDetails details(String title) {
        return new UpgradeDetails(null, title, null, UpgradeType.HABIT, null, null, null, null, null);
    }
}
