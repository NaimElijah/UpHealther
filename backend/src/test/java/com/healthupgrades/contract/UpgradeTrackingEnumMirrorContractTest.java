package com.healthupgrades.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.tracking.domain.model.Frequency;
import com.healthupgrades.tracking.domain.model.TrackingType;
import com.healthupgrades.upgrade.domain.model.UpgradeTrackingFrequency;
import com.healthupgrades.upgrade.domain.model.UpgradeTrackingType;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Holds each enum the {@code upgrade} context mirrors equal to the {@code tracking} enum it restates
 * (ADR-077).
 *
 * <p>The upgrade response embeds its tracking configuration typed by the mirrors, because {@code upgrade}
 * may not import {@code tracking}. Nothing else connects the two sets. A constant {@code tracking} gains
 * and the mirror lacks fails {@code UpgradeTrackingSummaryAdapter}'s {@code valueOf} on the first upgrade
 * configured with it, as a 500; a constant only the mirror has is a value the frontend's type allows and
 * the API never sends. The order is held too, so the two generated unions read the same.
 */
class UpgradeTrackingEnumMirrorContractTest {

    /** Each mirror, beside the enum it restates. */
    static Stream<Arguments> mirrors() {
        return Stream.of(
                Arguments.of(UpgradeTrackingType.class, TrackingType.class),
                Arguments.of(UpgradeTrackingFrequency.class, Frequency.class));
    }

    @ParameterizedTest(name = "{0} mirrors {1}")
    @MethodSource("mirrors")
    void GivenAMirroredEnum_WhenItsConstantsAreComparedWithTheOriginals_ThenTheyAreTheSameInTheSameOrder(
            Class<? extends Enum<?>> mirror, Class<? extends Enum<?>> original) {
        assertThat(names(mirror))
                .as(
                        "%s must list exactly %s's constants, in order: tracking's own enum cannot be imported"
                                + " by the upgrade context, so this copy is all that keeps the wire type honest",
                        mirror.getSimpleName(), original.getSimpleName())
                .containsExactlyElementsOf(names(original));
    }

    private static List<String> names(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
    }
}
