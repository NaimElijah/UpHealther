package com.healthupgrades.tracking.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.healthupgrades.tracking.domain.model.ProgressEntry;
import com.healthupgrades.tracking.domain.model.TrackingConfig;
import com.healthupgrades.tracking.domain.model.TrackingType;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers what counts as a successful entry for each tracking type.
 *
 * <p>Includes the unit-comparison cases: a value logged in a unit that disagrees with the target's must
 * not be scored, which is the defect that motivated the check.
 */
class ProgressEvaluationServiceTest {

    private ProgressEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new ProgressEvaluationService();
    }

    private ProgressEntry buildEntry() {
        return ProgressEntry.builder()
                .id(UUID.randomUUID())
                .upgradeId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .date(LocalDate.of(2026, 3, 15))
                .build();
    }

    private TrackingConfig buildConfig(TrackingType type) {
        return TrackingConfig.builder()
                .id(UUID.randomUUID())
                .upgradeId(UUID.randomUUID())
                .trackingType(type)
                .build();
    }

    // BOOLEAN tests
    @Test
    void GivenABooleanEntryMarkedComplete_WhenItIsEvaluated_ThenItCounts() {
        ProgressEntry e = buildEntry();
        e.setCompleted(true);
        TrackingConfig c = buildConfig(TrackingType.BOOLEAN);
        assertThat(service.isSuccessful(e, c)).isTrue();
    }

    @Test
    void GivenABooleanEntryMarkedIncomplete_WhenItIsEvaluated_ThenItDoesNotCount() {
        ProgressEntry e = buildEntry();
        e.setCompleted(false);
        TrackingConfig c = buildConfig(TrackingType.BOOLEAN);
        assertThat(service.isSuccessful(e, c)).isFalse();
    }

    @Test
    void GivenABooleanEntryWithNoValue_WhenItIsEvaluated_ThenItDoesNotCount() {
        ProgressEntry e = buildEntry();
        e.setCompleted(null);
        TrackingConfig c = buildConfig(TrackingType.BOOLEAN);
        assertThat(service.isSuccessful(e, c)).isFalse();
    }

    // NUMERIC tests
    @Test
    void GivenANumericValueAboveTheTarget_WhenItIsEvaluated_ThenItCounts() {
        ProgressEntry e = buildEntry();
        e.setNumericValue(2.5);
        TrackingConfig c = buildConfig(TrackingType.NUMERIC);
        c.setTargetNumericValue(2.0);
        assertThat(service.isSuccessful(e, c)).isTrue();
    }

    @Test
    void GivenANumericValueEqualToTheTarget_WhenItIsEvaluated_ThenItCounts() {
        ProgressEntry e = buildEntry();
        e.setNumericValue(2.0);
        TrackingConfig c = buildConfig(TrackingType.NUMERIC);
        c.setTargetNumericValue(2.0);
        assertThat(service.isSuccessful(e, c)).isTrue();
    }

    @Test
    void GivenANumericValueBelowTheTarget_WhenItIsEvaluated_ThenItDoesNotCount() {
        ProgressEntry e = buildEntry();
        e.setNumericValue(1.5);
        TrackingConfig c = buildConfig(TrackingType.NUMERIC);
        c.setTargetNumericValue(2.0);
        assertThat(service.isSuccessful(e, c)).isFalse();
    }

    @Test
    void GivenANumericEntryWithNoValue_WhenItIsEvaluated_ThenItDoesNotCount() {
        ProgressEntry e = buildEntry();
        e.setNumericValue(null);
        TrackingConfig c = buildConfig(TrackingType.NUMERIC);
        c.setTargetNumericValue(2.0);
        assertThat(service.isSuccessful(e, c)).isFalse();
    }

    @Test
    void GivenANumericValueClearingTheTargetInAnotherUnit_WhenItIsEvaluated_ThenItDoesNotCount() {
        // 45 minutes is not 30 kilometres, however favourably the bare numbers compare.
        ProgressEntry e = buildEntry();
        e.setNumericValue(45.0);
        e.setUnit("minutes");
        TrackingConfig c = buildConfig(TrackingType.NUMERIC);
        c.setTargetNumericValue(30.0);
        c.setTargetUnit("km");
        assertThat(service.isSuccessful(e, c)).isFalse();
    }

    @Test
    void GivenANumericValueClearingTheTargetInTheSameUnit_WhenItIsEvaluated_ThenItCounts() {
        ProgressEntry e = buildEntry();
        e.setNumericValue(45.0);
        e.setUnit("minutes");
        TrackingConfig c = buildConfig(TrackingType.NUMERIC);
        c.setTargetNumericValue(30.0);
        c.setTargetUnit("minutes");
        assertThat(service.isSuccessful(e, c)).isTrue();
    }

    @Test
    void GivenANumericEntryThatOmitsItsUnit_WhenItIsEvaluated_ThenTheConfiguredUnitIsAssumed() {
        ProgressEntry e = buildEntry();
        e.setNumericValue(45.0);
        e.setUnit(null);
        TrackingConfig c = buildConfig(TrackingType.NUMERIC);
        c.setTargetNumericValue(30.0);
        c.setTargetUnit("minutes");
        assertThat(service.isSuccessful(e, c)).isTrue();
    }

    @Test
    void GivenUnitsDifferingOnlyInCaseAndPadding_WhenTheEntryIsEvaluated_ThenTheyAreTreatedAsTheSameUnit() {
        ProgressEntry e = buildEntry();
        e.setNumericValue(45.0);
        e.setUnit("  Minutes ");
        TrackingConfig c = buildConfig(TrackingType.NUMERIC);
        c.setTargetNumericValue(30.0);
        c.setTargetUnit("minutes");
        assertThat(service.isSuccessful(e, c)).isTrue();
    }

    @Test
    void GivenAConfigWithNoTargetUnit_WhenAnEntryIsEvaluated_ThenAnyLoggedUnitIsAccepted() {
        ProgressEntry e = buildEntry();
        e.setNumericValue(45.0);
        e.setUnit("minutes");
        TrackingConfig c = buildConfig(TrackingType.NUMERIC);
        c.setTargetNumericValue(30.0);
        assertThat(service.isSuccessful(e, c)).isTrue();
    }

    @Test
    void GivenANumericConfigWithNoTarget_WhenAnEntryIsEvaluated_ThenItDoesNotCount() {
        // BR-21: with no target there is nothing for the value to reach, so no value counts, however large.
        ProgressEntry e = buildEntry();
        e.setNumericValue(1_000_000.0);
        TrackingConfig c = buildConfig(TrackingType.NUMERIC);
        assertThat(service.isSuccessful(e, c)).isFalse();
    }

    // RATING tests
    @Test
    void GivenARatingAboveThree_WhenItIsEvaluated_ThenItCounts() {
        ProgressEntry e = buildEntry();
        e.setRating(4);
        TrackingConfig c = buildConfig(TrackingType.RATING);
        assertThat(service.isSuccessful(e, c)).isTrue();
    }

    @Test
    void GivenARatingOfExactlyThree_WhenItIsEvaluated_ThenItCounts() {
        ProgressEntry e = buildEntry();
        e.setRating(3);
        TrackingConfig c = buildConfig(TrackingType.RATING);
        assertThat(service.isSuccessful(e, c)).isTrue();
    }

    @Test
    void GivenARatingBelowThree_WhenItIsEvaluated_ThenItDoesNotCount() {
        ProgressEntry e = buildEntry();
        e.setRating(2);
        TrackingConfig c = buildConfig(TrackingType.RATING);
        assertThat(service.isSuccessful(e, c)).isFalse();
    }

    // TEXT tests
    @Test
    void GivenATextEntryWithANote_WhenItIsEvaluated_ThenItCounts() {
        ProgressEntry e = buildEntry();
        e.setNote("Felt great today!");
        TrackingConfig c = buildConfig(TrackingType.TEXT);
        assertThat(service.isSuccessful(e, c)).isTrue();
    }

    @Test
    void GivenATextEntryWithABlankNote_WhenItIsEvaluated_ThenItDoesNotCount() {
        ProgressEntry e = buildEntry();
        e.setNote("   ");
        TrackingConfig c = buildConfig(TrackingType.TEXT);
        assertThat(service.isSuccessful(e, c)).isFalse();
    }

    @Test
    void GivenATextEntryWithNoNote_WhenItIsEvaluated_ThenItDoesNotCount() {
        ProgressEntry e = buildEntry();
        e.setNote(null);
        TrackingConfig c = buildConfig(TrackingType.TEXT);
        assertThat(service.isSuccessful(e, c)).isFalse();
    }

    // Null arguments are a caller's bug. These two tests used to pin that a missing configuration scores
    // the entry as a failure, which is the opposite of BR-7: without a configuration the entry keeps the
    // completed value its caller sent, and TrackingService decides that before it ever asks here (D3.4).
    @Test
    void GivenNoEntry_WhenEvaluationIsAsked_ThenItIsRefused() {
        TrackingConfig c = buildConfig(TrackingType.BOOLEAN);

        assertThatThrownBy(() -> service.isSuccessful(null, c)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void GivenNoTrackingConfig_WhenEvaluationIsAsked_ThenItIsRefused() {
        ProgressEntry e = buildEntry();

        assertThatThrownBy(() -> service.isSuccessful(e, null)).isInstanceOf(NullPointerException.class);
    }
}
