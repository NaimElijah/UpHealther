package com.healthupgrades.tracking.domain.service;
import com.healthupgrades.tracking.domain.model.ProgressEntry;
import com.healthupgrades.tracking.domain.model.TrackingConfig;

/**
 * Pure domain service that decides whether a progress entry meets its tracking config's target.
 *
 * <p>Framework-free and stateless: the application layer wires it as a Spring bean, keeping the
 * framework out of the domain.
 */
public class ProgressEvaluationService {

    /**
     * Returns whether the entry counts as a success for its configured tracking type: boolean
     * completion, numeric value meeting the target, a rating of at least 3, or non-blank text.
     */
    public boolean isSuccessful(ProgressEntry entry, TrackingConfig config) {
        if (entry == null || config == null) return false; // nothing to evaluate against

        // The success rule depends on how this upgrade is tracked.
        return switch (config.getTrackingType()) {
            case BOOLEAN -> Boolean.TRUE.equals(entry.getCompleted()); // did-it / didn't
            case NUMERIC -> entry.getNumericValue() != null
                    && config.getTargetNumericValue() != null
                    && entry.getNumericValue() >= config.getTargetNumericValue(); // met the numeric target
            case RATING -> entry.getRating() != null && entry.getRating() >= 3; // rated 3+ out of 5
            case TEXT -> entry.getNote() != null && !entry.getNote().isBlank(); // wrote something
        };
    }
}
