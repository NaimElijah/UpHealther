package com.healthupgrades.healtharea.application;

import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.healtharea.application.port.in.HealthAreaDetails;
import com.healthupgrades.healtharea.application.port.in.HealthAreaQuery;
import com.healthupgrades.healtharea.domain.model.HealthArea;
import com.healthupgrades.healtharea.domain.port.out.HealthAreaRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class HealthAreaService implements HealthAreaQuery {

    private final HealthAreaRepositoryPort repository;

    /** Creates a health area owned by the given user. */
    @Transactional
    public HealthArea create(UUID userId, HealthAreaDetails details) {
        HealthArea area = HealthArea.builder()
                .userId(userId)
                .name(details.name())
                .description(details.description())
                .priority(details.priority())
                .icon(details.icon())
                .color(details.color())
                .build();
        return repository.save(area);
    }

    /** All areas owned by the user. */
    public List<HealthArea> findAll(UUID userId) {
        return repository.findByUserId(userId);
    }

    /** A single owned area. */
    public HealthArea findById(UUID userId, UUID id) {
        return getOwnedArea(userId, id);
    }

    /** Replaces the editable attributes of an owned area. */
    @Transactional
    public HealthArea update(UUID userId, UUID id, HealthAreaDetails details) {
        HealthArea area = getOwnedArea(userId, id);
        area.setName(details.name());
        area.setDescription(details.description());
        area.setPriority(details.priority());
        area.setIcon(details.icon());
        area.setColor(details.color());
        return repository.save(area);
    }

    /** Deletes an owned area. */
    @Transactional
    public void delete(UUID userId, UUID id) {
        repository.delete(getOwnedArea(userId, id));
    }

    /** {@inheritDoc} Exposes a user's areas as domain objects for the dashboard context. */
    @Override
    public List<HealthArea> listByUser(UUID userId) {
        return repository.findByUserId(userId);
    }

    private HealthArea getOwnedArea(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("HealthArea not found: " + id)); // ownership guard
    }
}
