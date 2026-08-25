package com.healthupgrades.common.adapter.out.audit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.healthupgrades.common.domain.audit.AuditAction;
import com.healthupgrades.common.domain.audit.AuditEvent;
import com.healthupgrades.common.domain.audit.AuditOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.logstash.logback.argument.StructuredArgument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins what the audit stream actually emits: the fields, the counter derived from the same call, and
 * the absence of anything personal.
 *
 * <p>Asserted through a {@link ListAppender} on the {@code AUDIT} logger, which is the name the stream
 * is addressed by — so this also fails if somebody renames the logger to the class, which would make
 * every existing routing rule and saved query stop matching.
 */
class LoggingAuditTrailTest {

    private static final String AUDIT_LOGGER = "AUDIT";

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final LoggingAuditTrail trail = new LoggingAuditTrail(meterRegistry);

    private Logger auditLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureTheAuditStream() {
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
    void GivenAnAllowedAttempt_WhenItIsRecorded_ThenTheStreamCarriesTheActorTheResourceAndTheOutcome() {
        UUID actor = UUID.randomUUID();
        UUID resource = UUID.randomUUID();

        trail.record(AuditEvent.allowed(AuditAction.UPGRADE_ACTIVATE, actor, resource));

        ILoggingEvent line = single();
        // INFO, not DEBUG: an audit entry is a state transition, and a deployment runs at INFO.
        assertThat(line.getLevel()).isEqualTo(Level.INFO);
        assertThat(fieldsOf(line))
                .containsEntry("action", "upgrade.activate")
                .containsEntry("outcome", "ALLOWED")
                .containsEntry("actorId", actor.toString())
                .containsEntry("resourceId", resource.toString());
    }

    @Test
    void GivenARefusedLoginWithNoSubject_WhenItIsRecorded_ThenTheFieldsAreStillPresentAndSayNone() {
        trail.record(new AuditEvent(AuditAction.AUTH_LOGIN, null, null, AuditOutcome.REFUSED));

        // "There is no actor" is a fact worth recording, and a stable field set is what keeps the
        // stream queryable — so the fields are present and say so rather than being dropped.
        assertThat(fieldsOf(single()))
                .containsEntry("action", "auth.login")
                .containsEntry("outcome", "REFUSED")
                .containsEntry("actorId", "none")
                .containsEntry("resourceId", "none");
    }

    @Test
    void GivenAnAttempt_WhenItIsRecorded_ThenTheRenderedLineHoldsNothingButIdentifiersAndEnums() {
        UUID actor = UUID.randomUUID();

        trail.record(AuditEvent.allowed(AuditAction.REFLECTION_CREATE, actor, UUID.randomUUID()));

        // A reflection's body is the most personal thing this application stores. It cannot reach the
        // line because AuditEvent has nowhere to hold it (AuditEventTest), and this checks the other
        // end: what is rendered is only what was passed in.
        assertThat(single().getFormattedMessage())
                .contains("action=reflection.create", "actorId=" + actor)
                .doesNotContain("@", "null");
    }

    @Test
    void GivenTwoAttemptsOnTheSameAction_WhenTheyAreRecorded_ThenTheCounterSeparatesThemByOutcome() {
        UUID actor = UUID.randomUUID();
        trail.record(AuditEvent.allowed(AuditAction.PROGRESS_RECORD, actor, UUID.randomUUID()));
        trail.record(new AuditEvent(AuditAction.PROGRESS_RECORD, actor, UUID.randomUUID(), AuditOutcome.REFUSED));

        assertThat(counted("progress.record", "ALLOWED")).isEqualTo(1);
        assertThat(counted("progress.record", "REFUSED")).isEqualTo(1);
    }

    @Test
    void GivenAnyAttempt_WhenItIsCounted_ThenNeitherTheActorNorTheResourceIsATag() {
        trail.record(AuditEvent.allowed(AuditAction.AREA_CREATE, UUID.randomUUID(), UUID.randomUUID()));

        // A user id as a label is an unbounded cardinality, and a metrics backend is the wrong place to
        // look one up anyway — that is what the audit line is for.
        assertThat(meterRegistry.find("audit.events").counter().getId().getTags())
                .extracting("key")
                .containsExactlyInAnyOrder("action", "outcome");
    }

    private ILoggingEvent single() {
        assertThat(appender.list).hasSize(1);
        return appender.list.get(0);
    }

    private double counted(String action, String outcome) {
        return meterRegistry.get("audit.events").tag("action", action).tag("outcome", outcome).counter().count();
    }

    /** The structured fields the logstash encoder would turn into JSON keys. */
    private static java.util.Map<String, Object> fieldsOf(ILoggingEvent event) {
        java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
        Arrays.stream(event.getArgumentArray())
                .filter(StructuredArgument.class::isInstance)
                .forEach(argument -> {
                    String rendered = argument.toString(); // "key=value"
                    int split = rendered.indexOf('=');
                    fields.put(rendered.substring(0, split), rendered.substring(split + 1));
                });
        return fields;
    }
}
