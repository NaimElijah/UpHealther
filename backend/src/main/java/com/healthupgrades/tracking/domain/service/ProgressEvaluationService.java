package com.healthupgrades.tracking.domain.service;
import com.healthupgrades.tracking.domain.model.ProgressEntry;
import com.healthupgrades.tracking.domain.model.TrackingConfig;

import java.util.Locale;

/**
 * Pure domain service that decides whether a progress entry meets its tracking config's target.
 *
 * <p>Framework-free and stateless: the application layer wires it as a Spring bean, keeping the
 * framework out of the domain.
 */
public class ProgressEvaluationService {

    /**
     * Returns whether the entry counts as a success for its configured tracking type: boolean
     * completion, numeric value meeting the target in a comparable unit, a rating of at least 3, or
     * non-blank text.
     */
    public boolean isSuccessful(ProgressEntry entry, TrackingConfig config) {
        if (entry == null || config == null) return false; // nothing to evaluate against

        // The success rule depends on how this upgrade is tracked.
        return switch (config.getTrackingType()) {
            case BOOLEAN -> Boolean.TRUE.equals(entry.getCompleted()); // did-it / didn't
            case NUMERIC -> entry.getNumericValue() != null
                    && config.getTargetNumericValue() != null
                    && unitsAreComparable(entry.getUnit(), config.getTargetUnit())
                    && entry.getNumericValue() >= config.getTargetNumericValue(); // met the numeric target
            case RATING -> entry.getRating() != null && entry.getRating() >= 3; // rated 3+ out of 5
            case TEXT -> entry.getNote() != null && !entry.getNote().isBlank(); // wrote something
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
