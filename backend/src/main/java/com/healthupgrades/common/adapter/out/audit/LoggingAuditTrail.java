package com.healthupgrades.common.adapter.out.audit;

import com.healthupgrades.common.domain.audit.AuditEvent;
import com.healthupgrades.common.domain.port.out.AuditTrail;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.keyValue;

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
 */
@Component
public class LoggingAuditTrail implements AuditTrail {

    /** Not this class's name: the stream is the thing being addressed, not the writer. */
    private static final Logger log = LoggerFactory.getLogger("AUDIT");

    private static final String COUNTER = "audit.events";
    private static final String UNKNOWN = "none";

    private final MeterRegistry meterRegistry;

    public LoggingAuditTrail(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * {@inheritDoc}
     *
     * <p>INFO, because an audit entry is a state transition rather than a fault — a refusal is the
     * system working. The level policy is in
     * {@code docs/ADRs/ADR-010-structured-logging-and-a-level-policy.md}.
     *
     * <p>The counter is derived here rather than incremented separately at the call site so the two can
     * never disagree about what happened. Its tags are the action key and the outcome, both closed
     * enums: the actor and the resource are deliberately not tags, because a user id as a label is an
     * unbounded cardinality and a metrics backend is the wrong place to look one up anyway.
     */
    @Override
    public void record(AuditEvent event) {
        log.info("{} {} {} {} {}",
                keyValue("action", event.action().key()),
                keyValue("outcome", event.outcome()),
                keyValue("resource", event.action().resource()),
                keyValue("actorId", idOrNone(event.actorUserId())),
                keyValue("resourceId", idOrNone(event.resourceId())));

        Counter.builder(COUNTER)
                .description("Audited attempts, by what was attempted and how it ended")
                .tag("action", event.action().key())
                .tag("outcome", event.outcome().name())
                .register(meterRegistry)
                .increment();
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
}
