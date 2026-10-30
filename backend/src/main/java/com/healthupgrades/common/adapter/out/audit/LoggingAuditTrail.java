package com.healthupgrades.common.adapter.out.audit;

import static net.logstash.logback.argument.StructuredArguments.keyValue;

import com.healthupgrades.common.domain.audit.AuditEvent;
import com.healthupgrades.common.domain.audit.AuditOutcome;
import com.healthupgrades.common.domain.audit.AuditedResource;
import com.healthupgrades.common.domain.port.out.AuditTrail;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Adapter writing the audit trail to its own log stream, and counting the same events as it goes.
 *
 * <p><b>Why a log stream and not a table.</b> An audit entry has to survive the transaction it observes:
 * a refused transition rolls back, and a row written inside that transaction would roll back with it —
 * exactly the entry somebody was looking for. It also arrives already correlated, because the trace id
 * is on the line, so an entry leads back to the request that produced it without a foreign key.
 * {@code docs/ADRs/ADR-011-audit-as-a-log-stream.md} records the decision and what would reverse it.
 *
 * <p>The logger is named {@code AUDIT} rather than after this class so the stream can be routed or
 * filtered on its own, by a level or by an appender, without knowing where in the package tree it is
 * written from.
 *
 * <p>Fields are emitted through {@code StructuredArguments}, which renders {@code key=value} into the
 * message for a person reading the console and a first-class JSON field for anything querying it — one
 * statement that is legible in both of the formats {@code logback-spring.xml} selects between.
 *
 * <p><b>A session is named by a digest, not by its id.</b> An {@code AUTH_SESSION} entry names the
 * session's owner beside it, and that pair, an account and a live session of it, is the half of a forged
 * token that ADR-049 says a signing key alone lacks (S5.C3). So the session id is written as the first
 * {@value #SESSION_DIGEST_HEX_LENGTH} hex characters of its SHA-256: the same session always gives the
 * same value, so its entries still join up, but the value is no id anybody can put in a token. A reader
 * who holds a session id, from the database or a support case, finds its entries by computing the same
 * digest.
 */
@Component
public class LoggingAuditTrail implements AuditTrail {

    /**
     * The logger the stream is written to. Not this class's name: the stream is the thing being addressed,
     * not the writer. Package-private so {@code ShippedAuditLevelTest} pins the level of this name, and a
     * rename cannot leave the pin on a logger nothing writes to.
     */
    static final String LOGGER_NAME = "AUDIT";

    private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

    private static final String COUNTER = "audit.events";
    private static final String UNKNOWN = "none";

    /**
     * How much of a session id's SHA-256 the stream keeps: sixty-four bits, enough that two live sessions
     * colliding is not a concern, and far too few to invert by any means but guessing the id itself.
     */
    static final int SESSION_DIGEST_HEX_LENGTH = 16;

    private final MeterRegistry meterRegistry;

    public LoggingAuditTrail(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>An {@code ALLOWED} entry waits for the commit.</b> Every caller wraps the body of an
     * {@code @Transactional} service method, so the work returns while its transaction is still open —
     * the commit happens afterwards, in the proxy. Only registration's save flushes (it translates a
     * duplicate address itself), so everywhere else a {@code @Version} clash or a unique constraint
     * losing a race is decided <em>at commit</em>, after the service method
     * has returned. Writing the entry at that point would have the trail and the counter both claim an
     * edit succeeded while the caller was answered 409 and nothing was persisted. Deferring it through
     * the same {@code afterCommit} route {@code NotificationService} already uses is what makes
     * "allowed" mean what {@code ADR-011} says it means.
     *
     * <p>Refusals and faults are written immediately and deliberately: they already describe an attempt
     * that did not land, they are the security-relevant half, and a process that dies before commit
     * should not take them with it.
     */
    @Override
    public void record(AuditEvent event) {
        if (event.outcome() == AuditOutcome.ALLOWED && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    // afterCompletion rather than afterCommit, so a rollback is recorded rather than
                    // leaving the attempt with no entry at all.
                    write(status == STATUS_COMMITTED ? event : event.refused());
                }
            });
            return;
        }
        write(event);
    }

    /**
     * Writes one entry, and never lets a failure to do so fail the work being audited.
     *
     * <p>The port says implementations must not throw, and this is where that is honoured. It matters
     * most on the refusal path: {@code AuditTrail.recording} calls this from inside its catch block, so
     * an exception escaping here would <em>replace</em> the business exception — turning a
     * {@code BusinessRuleException} that should be a 422 into an unhandled 500 with the real cause lost.
     *
     * <p>The two halves are guarded separately so a meter failure still leaves the audit line, which is
     * the half that matters. If the logging subsystem itself is what failed there is nowhere left to
     * report it, and the second write will throw for the same reason the first did.
     */
    private void write(AuditEvent event) {
        try {
            log.info(
                    "{} {} {} {} {}",
                    keyValue("action", event.action().key()),
                    keyValue("outcome", event.outcome()),
                    keyValue("resource", event.action().resource()),
                    keyValue("actorId", idOrNone(event.actorUserId())),
                    keyValue("resourceId", resourceIdOf(event)));
        } catch (RuntimeException unwritable) {
            return; // Nothing can be reported about a broken logger, by a logger.
        }

        try {
            // Derived from the same call as the line above, so the count and the trail cannot disagree.
            // Tagged by action and outcome only: both are closed enums, whereas an actor or a resource
            // id is an unbounded label, and a metrics backend is the wrong place to look one up anyway.
            Counter.builder(COUNTER)
                    .description("Audited attempts, by what was attempted and how it ended")
                    .tag("action", event.action().key())
                    .tag("outcome", event.outcome().name())
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException uncountable) {
            log.error(
                    "{} was recorded but could not be counted",
                    keyValue("action", event.action().key()),
                    uncountable);
        }
    }

    /**
     * Renders an absent identifier as a value rather than as {@code null}.
     *
     * <p>"There is no actor" is a fact worth recording — a refused login has one deliberately, since
     * the submitted email is personal data — and a stable field set is what makes the stream queryable.
     */
    private static String idOrNone(UUID id) {
        return id == null ? UNKNOWN : id.toString();
    }

    /** The resource's id as the stream writes it: a session's as its digest, any other as it is. */
    private static String resourceIdOf(AuditEvent event) {
        UUID id = event.resourceId();
        if (id == null || event.action().resource() != AuditedResource.AUTH_SESSION) {
            return idOrNone(id);
        }
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(id.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, SESSION_DIGEST_HEX_LENGTH);
        } catch (NoSuchAlgorithmException impossible) {
            // Every JVM is required to provide SHA-256, as RefreshToken notes. Rethrown rather than
            // written in clear: write() swallows it, which loses one line instead of leaking a session.
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
