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

/**
 * Use cases for health areas: create, read, update and delete, all scoped to one owner.
 *
 * <p>Every method takes the owner's id explicitly — there is no ambient "current user" in this layer —
 * and every lookup goes through {@code getOwnedArea}, so ownership is checked in exactly one place.
 */
@Service
@RequiredArgsConstructor
public class HealthAreaService implements HealthAreaQuery {

    private final HealthAreaRepositoryPort repository;

    /**
     * Creates a health area owned by the given user.
     *
     * @param userId  the owner
     * @param details the attributes to store
     * @return the persisted area, with its generated id and timestamps
     */
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

    /**
     * Reads one of the user's areas.
     *
     * @param userId the owner
     * @param id     the area's identifier
     * @return the owned area
     * @throws ResourceNotFoundException if the area does not exist or belongs to somebody else
     */
    public HealthArea findById(UUID userId, UUID id) {
        return getOwnedArea(userId, id);
    }

    /**
     * Replaces the editable attributes of an owned area.
     *
     * @param userId  the owner
     * @param id      the area's identifier
     * @param details the replacement attributes; nulls overwrite previous values
     * @return the saved area
     * @throws ResourceNotFoundException if the area does not exist or belongs to somebody else
     */
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

    /**
     * Deletes an owned area.
     *
     * <p>Upgrades filed under it are left untouched and keep the now-dangling area id.
     *
     * @param userId the owner
     * @param id     the area's identifier
     * @throws ResourceNotFoundException if the area does not exist or belongs to somebody else
     */
    @Transactional
    public void delete(UUID userId, UUID id) {
        repository.delete(getOwnedArea(userId, id));
    }

    /**
     * {@inheritDoc} Also serves this context's own list endpoint — the query is the same one, so it has
     * one implementation rather than a second that must be kept in step with it.
     */
    @Override
    public List<HealthArea> listByUser(UUID userId) {
        return repository.findByUserId(userId);
    }

    /** Single ownership guard: a foreign or missing area is indistinguishable, both are "not found". */
    private HealthArea getOwnedArea(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("HealthArea not found: " + id)); // ownership guard
    }
}
