package com.healthupgrades.tracking.domain;

import org.springframework.stereotype.Service;

@Service
public class ProgressEvaluationService {

    public boolean isSuccessful(ProgressEntry entry, TrackingConfig config) {
        if (entry == null || config == null) return false;

        return switch (config.getTrackingType()) {
            case BOOLEAN -> Boolean.TRUE.equals(entry.getCompleted());
            case NUMERIC -> entry.getNumericValue() != null
                    && config.getTargetNumericValue() != null
                    && entry.getNumericValue() >= config.getTargetNumericValue();
            case RATING -> entry.getRating() != null && entry.getRating() >= 3;
            case TEXT -> entry.getNote() != null && !entry.getNote().isBlank();
        };
    }
}
