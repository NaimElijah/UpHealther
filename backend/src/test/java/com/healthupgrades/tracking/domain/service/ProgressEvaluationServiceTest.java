package com.healthupgrades.tracking.domain.service;
import com.healthupgrades.tracking.domain.model.ProgressEntry;
import com.healthupgrades.tracking.domain.model.TrackingConfig;
import com.healthupgrades.tracking.domain.model.TrackingType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
                .date(LocalDate.now())
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
    void boolean_completedTrue_returnsTrue() {
        ProgressEntry e = buildEntry();
        e.setCompleted(true);
        TrackingConfig c = buildConfig(TrackingType.BOOLEAN);
        assertThat(service.isSuccessful(e, c)).isTrue();
    }

    @Test
    void boolean_completedFalse_returnsFalse() {
        ProgressEntry e = buildEntry();
        e.setCompleted(false);
        TrackingConfig c = buildConfig(TrackingType.BOOLEAN);
        assertThat(service.isSuccessful(e, c)).isFalse();
    }

    @Test
    void boolean_completedNull_returnsFalse() {
        ProgressEntry e = buildEntry();
        e.setCompleted(null);
        TrackingConfig c = buildConfig(TrackingType.BOOLEAN);
        assertThat(service.isSuccessful(e, c)).isFalse();
    }

    // NUMERIC tests
    @Test
    void numeric_valueAboveTarget_returnsTrue() {
        ProgressEntry e = buildEntry();
        e.setNumericValue(2.5);
        TrackingConfig c = buildConfig(TrackingType.NUMERIC);
        c.setTargetNumericValue(2.0);
        assertThat(service.isSuccessful(e, c)).isTrue();
    }

    @Test
    void numeric_valueEqualsTarget_returnsTrue() {
        ProgressEntry e = buildEntry();
        e.setNumericValue(2.0);
        TrackingConfig c = buildConfig(TrackingType.NUMERIC);
        c.setTargetNumericValue(2.0);
        assertThat(service.isSuccessful(e, c)).isTrue();
    }

    @Test
    void numeric_valueBelowTarget_returnsFalse() {
        ProgressEntry e = buildEntry();
        e.setNumericValue(1.5);
        TrackingConfig c = buildConfig(TrackingType.NUMERIC);
        c.setTargetNumericValue(2.0);
        assertThat(service.isSuccessful(e, c)).isFalse();
    }

    @Test
    void numeric_valueNull_returnsFalse() {
        ProgressEntry e = buildEntry();
        e.setNumericValue(null);
        TrackingConfig c = buildConfig(TrackingType.NUMERIC);
        c.setTargetNumericValue(2.0);
        assertThat(service.isSuccessful(e, c)).isFalse();
    }

    @Test
    void numeric_valueClearsTargetButUnitDiffers_returnsFalse() {
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
    void numeric_valueClearsTargetAndUnitMatches_returnsTrue() {
        ProgressEntry e = buildEntry();
        e.setNumericValue(45.0);
        e.setUnit("minutes");
        TrackingConfig c = buildConfig(TrackingType.NUMERIC);
        c.setTargetNumericValue(30.0);
        c.setTargetUnit("minutes");
        assertThat(service.isSuccessful(e, c)).isTrue();
    }

    @Test
    void numeric_entryOmitsUnit_isTakenToUseTheConfiguredOne() {
        ProgressEntry e = buildEntry();
        e.setNumericValue(45.0);
        e.setUnit(null);
        TrackingConfig c = buildConfig(TrackingType.NUMERIC);
        c.setTargetNumericValue(30.0);
        c.setTargetUnit("minutes");
        assertThat(service.isSuccessful(e, c)).isTrue();
    }

    @Test
    void numeric_unitsDifferOnlyInCaseAndPadding_returnsTrue() {
        ProgressEntry e = buildEntry();
        e.setNumericValue(45.0);
        e.setUnit("  Minutes ");
        TrackingConfig c = buildConfig(TrackingType.NUMERIC);
        c.setTargetNumericValue(30.0);
        c.setTargetUnit("minutes");
        assertThat(service.isSuccessful(e, c)).isTrue();
    }

    @Test
    void numeric_configHasNoTargetUnit_acceptsAnyLoggedUnit() {
        ProgressEntry e = buildEntry();
        e.setNumericValue(45.0);
        e.setUnit("minutes");
        TrackingConfig c = buildConfig(TrackingType.NUMERIC);
        c.setTargetNumericValue(30.0);
        assertThat(service.isSuccessful(e, c)).isTrue();
    }

    // RATING tests
    @Test
    void rating_threeOrAbove_returnsTrue() {
        ProgressEntry e = buildEntry();
        e.setRating(4);
        TrackingConfig c = buildConfig(TrackingType.RATING);
        assertThat(service.isSuccessful(e, c)).isTrue();
    }

    @Test
    void rating_exactlyThree_returnsTrue() {
        ProgressEntry e = buildEntry();
        e.setRating(3);
        TrackingConfig c = buildConfig(TrackingType.RATING);
        assertThat(service.isSuccessful(e, c)).isTrue();
    }

    @Test
    void rating_belowThree_returnsFalse() {
        ProgressEntry e = buildEntry();
        e.setRating(2);
        TrackingConfig c = buildConfig(TrackingType.RATING);
        assertThat(service.isSuccessful(e, c)).isFalse();
    }

    // TEXT tests
    @Test
    void text_notePresent_returnsTrue() {
        ProgressEntry e = buildEntry();
        e.setNote("Felt great today!");
        TrackingConfig c = buildConfig(TrackingType.TEXT);
        assertThat(service.isSuccessful(e, c)).isTrue();
    }

    @Test
    void text_noteBlank_returnsFalse() {
        ProgressEntry e = buildEntry();
        e.setNote("   ");
        TrackingConfig c = buildConfig(TrackingType.TEXT);
        assertThat(service.isSuccessful(e, c)).isFalse();
    }

    @Test
    void text_noteNull_returnsFalse() {
        ProgressEntry e = buildEntry();
        e.setNote(null);
        TrackingConfig c = buildConfig(TrackingType.TEXT);
        assertThat(service.isSuccessful(e, c)).isFalse();
    }

    // Null safety
    @Test
    void nullEntry_returnsFalse() {
        TrackingConfig c = buildConfig(TrackingType.BOOLEAN);
        assertThat(service.isSuccessful(null, c)).isFalse();
    }

    @Test
    void nullConfig_returnsFalse() {
        ProgressEntry e = buildEntry();
        assertThat(service.isSuccessful(e, null)).isFalse();
    }
}
