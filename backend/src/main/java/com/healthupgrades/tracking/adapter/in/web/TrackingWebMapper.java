package com.healthupgrades.tracking.adapter.in.web;

import com.healthupgrades.tracking.application.port.in.ProgressEntryDetails;
import com.healthupgrades.tracking.application.port.in.StreakSummary;
import com.healthupgrades.tracking.application.port.in.TrackingConfigDetails;
import com.healthupgrades.tracking.domain.model.ProgressEntry;
import com.healthupgrades.tracking.domain.model.TrackingConfig;
import org.springframework.stereotype.Component;

import java.util.List;

/** Translates between this context's HTTP shapes and the shapes its use cases speak. */
@Component
public class TrackingWebMapper {

    /** Progress request record to the use-case input shape. */
    public ProgressEntryDetails toDetails(ProgressRequest req) {
        return new ProgressEntryDetails(req.date(), req.completed(), req.numericValue(),
                req.unit(), req.rating(), req.note());
    }

    /** Tracking-config request record to the use-case input shape. */
    public TrackingConfigDetails toDetails(TrackingConfigRequest req) {
        return new TrackingConfigDetails(req.trackingType(), req.frequency(),
                req.targetNumericValue(), req.targetUnit(), req.requiredDaily());
    }

    /** Progress entry to its response record. */
    public ProgressDto toDto(ProgressEntry e) {
        return new ProgressDto(e.getId(), e.getUpgradeId(), e.getUserId(), e.getDate(),
                e.getCompleted(), e.getNumericValue(), e.getUnit(), e.getRating(), e.getNote(), e.getCreatedAt());
    }

    /** Batch variant of {@link #toDto(ProgressEntry)}. */
    public List<ProgressDto> toDtos(List<ProgressEntry> entries) {
        return entries.stream().map(this::toDto).toList();
    }

    /** Tracking config to its response record. */
    public TrackingConfigDto toDto(TrackingConfig c) {
        return new TrackingConfigDto(c.getId(), c.getUpgradeId(), c.getTrackingType(),
                c.getFrequency(), c.getTargetNumericValue(), c.getTargetUnit(), c.getRequiredDaily());
    }

    /** Streak figures to their response record. */
    public StreakDto toDto(StreakSummary summary) {
        return new StreakDto(summary.current(), summary.longest());
    }
}
