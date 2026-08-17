package com.healthupgrades.reflection.adapter.in.web;

import com.healthupgrades.reflection.application.port.in.ReflectionDetails;
import com.healthupgrades.reflection.domain.model.Reflection;
import org.springframework.stereotype.Component;

import java.util.List;

/** Translates between this context's HTTP shapes and the shapes its use cases speak. */
@Component
public class ReflectionWebMapper {

    /** Request record to the use-case input shape. */
    public ReflectionDetails toDetails(ReflectionRequest req) {
        return new ReflectionDetails(req.date(), req.difficultyRating(), req.benefitRating(),
                req.whatWorked(), req.whatDidNotWork(), req.nextAdjustment());
    }

    /** Domain object to its response record. */
    public ReflectionDto toDto(Reflection r) {
        return new ReflectionDto(r.getId(), r.getUpgradeId(), r.getUserId(), r.getDate(),
                r.getDifficultyRating(), r.getBenefitRating(), r.getWhatWorked(),
                r.getWhatDidNotWork(), r.getNextAdjustment(), r.getCreatedAt());
    }

    /** Batch variant of {@link #toDto(Reflection)}. */
    public List<ReflectionDto> toDtos(List<Reflection> reflections) {
        return reflections.stream().map(this::toDto).toList();
    }
}
