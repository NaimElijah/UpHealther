package com.healthupgrades.tracking.domain.service;

import com.healthupgrades.tracking.domain.model.ProgressEntry;
import com.healthupgrades.tracking.domain.model.TrackingConfig;
import java.util.Locale;
import java.util.Objects;

/**
 * Pure domain service that decides whether a progress entry meets its tracking config's target.
 *
 * <p>Framework-free and stateless: the application layer wires it as a Spring bean, keeping the
 * framework out of the domain.
 */
public class ProgressEvaluationService {

    /** The lowest rating that counts as a success: 3, the midpoint of the one-to-five scale (BR-21). */
    static final int RATING_SUCCESS_THRESHOLD = 3;

    /**
     * Returns whether the entry counts as a success for its configured tracking type: boolean
     * completion, numeric value meeting the target in a comparable unit, a rating of at least 3, or
     * non-blank text.
     *
     * <p>Only for an upgrade that has a configuration: without one there is nothing to judge against,
     * and BR-7 has the entry keep the value its caller sent, which the caller decides before asking here.
     *
     * @param entry  the entry to judge
     * @param config the upgrade's tracking configuration
     * @return whether the entry meets the configuration's target
     * @throws NullPointerException if either is null
     */
    public boolean isSuccessful(ProgressEntry entry, TrackingConfig config) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(config, "config");

        return switch (config.getTrackingType()) {
            case BOOLEAN -> Boolean.TRUE.equals(entry.getCompleted());
            case NUMERIC ->
                entry.getNumericValue() != null
                        && config.getTargetNumericValue() != null
                        && unitsAreComparable(entry.getUnit(), config.getTargetUnit())
                        && entry.getNumericValue() >= config.getTargetNumericValue();
            // The midpoint of the scale: an average day counts, a bad one does not.
            case RATING -> entry.getRating() != null && entry.getRating() >= RATING_SUCCESS_THRESHOLD;
            case TEXT -> entry.getNote() != null && !entry.getNote().isBlank();
        };
    }

    /**
     * Whether a logged value may be compared against the target at all. Comparing raw numbers across
     * different units silently scores 45 minutes as clearing a 30 km target, so two units that are both
     * stated must name the same thing. An entry that states no unit is taken to have been logged in the
     * configured one, and a config with no target unit accepts whatever was logged.
     */
    private boolean unitsAreComparable(String loggedUnit, String targetUnit) {
        String logged = normalize(loggedUnit);
        String target = normalize(targetUnit);
        return logged == null || target == null || logged.equals(target);
    }

    /** Reduces a unit to its comparable form; blank and absent are both "unstated". */
    private String normalize(String unit) {
        if (unit == null) return null;
        String trimmed = unit.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }
}
