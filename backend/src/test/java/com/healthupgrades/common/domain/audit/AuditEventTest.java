package com.healthupgrades.common.domain.audit;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pins the shape of an audit entry, including the one property that keeps NFR-6 true by construction. */
class AuditEventTest {

    /**
     * The requirement is "the application never logs personal data", and the way this trail meets it is
     * that <em>there is nowhere to put any</em>. Asserting the field types rather than reviewing call
     * sites is what makes that a property of the design: a future field of type {@code String} — a
     * title, a note, an email — fails here, at the point somebody adds it, rather than in a log export
     * six months later.
     */
    @Test
    void GivenAnAuditEvent_WhenItsComponentsAreInspected_ThenNoneCanHoldFreeText() {
        RecordComponent[] components = AuditEvent.class.getRecordComponents();

        assertThat(components).isNotEmpty();
        assertThat(components).allSatisfy(component ->
                assertThat(component.getType().isEnum() || component.getType() == UUID.class)
                        .as("%s is a %s; an audit event may only hold enums and identifiers",
                                component.getName(), component.getType().getSimpleName())
                        .isTrue());
    }

    @Test
    void GivenNoAction_WhenAnEventIsBuilt_ThenItIsRejected() {
        assertThatThrownBy(() -> new AuditEvent(null, UUID.randomUUID(), UUID.randomUUID(), AuditOutcome.ALLOWED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action");
    }

    @Test
    void GivenNoOutcome_WhenAnEventIsBuilt_ThenItIsRejected() {
        assertThatThrownBy(() -> new AuditEvent(AuditAction.AUTH_LOGIN, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outcome");
    }

    /** A refused login has no actor and no resource, deliberately — both have to be representable. */
    @Test
    void GivenNoActorAndNoResource_WhenAnEventIsBuilt_ThenItIsAccepted() {
        AuditEvent event = new AuditEvent(AuditAction.AUTH_LOGIN, null, null, AuditOutcome.REFUSED);

        assertThat(event.actorUserId()).isNull();
        assertThat(event.resourceId()).isNull();
    }

    /** The dotted key is a log-query and metric contract, so it is asserted rather than assumed. */
    @Test
    void GivenAnAction_WhenItsKeyIsRead_ThenItIsTheStableDottedName() {
        assertThat(AuditAction.UPGRADE_ACTIVATE.key()).isEqualTo("upgrade.activate");
        assertThat(AuditAction.UPGRADE_ACTIVATE.resource()).isEqualTo(AuditedResource.HEALTH_UPGRADE);
    }

    @Test
    void GivenEveryAction_WhenItsKeyIsRead_ThenTheKeysAreUniqueAndDotted() {
        assertThat(AuditAction.values())
                .allSatisfy(action -> assertThat(action.key()).matches("[a-z]+\\.[a-z]+"))
                .extracting(AuditAction::key)
                .doesNotHaveDuplicates();
    }
}
