package com.healthupgrades.healtharea.adapter.in.web;

import com.healthupgrades.healtharea.application.port.in.HealthAreaDetails;
import com.healthupgrades.healtharea.domain.model.HealthArea;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Translates between this context's HTTP shapes and the shapes its use cases speak.
 *
 * <p>Both directions live in the web adapter so the wire format is free to change without touching the
 * application layer, which is the direction the dependency is supposed to run.
 */
@Component
public class HealthAreaWebMapper {

    /** Request record to the use-case input shape. */
    public HealthAreaDetails toDetails(HealthAreaRequest req) {
        return new HealthAreaDetails(req.name(), req.description(), req.priority(), req.icon(), req.color());
    }

    /** Domain object to its response record. */
    public HealthAreaDto toDto(HealthArea a) {
        return new HealthAreaDto(a.getId(), a.getUserId(), a.getName(), a.getDescription(),
                a.getPriority(), a.getIcon(), a.getColor(), a.getCreatedAt(), a.getUpdatedAt());
    }

    /** Batch variant of {@link #toDto(HealthArea)}. */
    public List<HealthAreaDto> toDtos(List<HealthArea> areas) {
        return areas.stream().map(this::toDto).toList();
    }
}
