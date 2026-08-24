package com.healthupgrades.healtharea.application;

import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.healtharea.application.port.in.HealthAreaDetails;
import com.healthupgrades.healtharea.domain.model.HealthArea;
import com.healthupgrades.healtharea.domain.port.out.HealthAreaRepositoryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers FR-6 (create, read, update and delete an area), FR-7 (the attributes an area carries) and BR-15
 * (another user's area is reported as absent, never as forbidden).
 *
 * <p>FR-8 — that deleting an area leaves upgrades filed under it intact — is only half testable here.
 * This class pins the half it can: the delete path touches the area repository and nothing else, because
 * the service holds no port through which it could cascade. What actually happens to those upgrades is
 * the schema's decision, not this service's, and is asserted in {@code HealthAreaPersistenceIT}:
 * {@code area_id ... ON DELETE SET NULL} clears the reference, so an upgrade survives and becomes
 * unfiled rather than pointing at an area that is gone.
 */
@ExtendWith(MockitoExtension.class)
class HealthAreaServiceTest {

    private static final HealthAreaDetails DETAILS =
            new HealthAreaDetails("Sleep", "Going to bed on time", 1, "🌙", "#4B6BFB");

    private final UUID userId = UUID.randomUUID();
    private final UUID areaId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();

    @Mock HealthAreaRepositoryPort repository;

    @InjectMocks HealthAreaService service;

    @Test
    void GivenAreaDetails_WhenAnAreaIsCreated_ThenItIsStoredAgainstTheOwnerWithEveryAttribute() {
        when(repository.save(any(HealthArea.class))).thenAnswer(call -> call.getArgument(0));

        service.create(userId, DETAILS);

        ArgumentCaptor<HealthArea> saved = ArgumentCaptor.forClass(HealthArea.class);
        verify(repository).save(saved.capture());
        HealthArea area = saved.getValue();
        assertThat(area.getUserId()).isEqualTo(userId);
        assertThat(area.getName()).isEqualTo("Sleep");
        assertThat(area.getDescription()).isEqualTo("Going to bed on time");
        assertThat(area.getPriority()).isEqualTo(1);
        assertThat(area.getIcon()).isEqualTo("🌙");
        assertThat(area.getColor()).isEqualTo("#4B6BFB");
    }

    @Test
    void GivenAnOwnedArea_WhenItIsRead_ThenItIsReturned() {
        HealthArea area = anArea(userId);
        when(repository.findByIdAndUserId(areaId, userId)).thenReturn(Optional.of(area));

        assertThat(service.findById(userId, areaId)).isSameAs(area);
    }

    @Test
    void GivenAnAreaOwnedBySomebodyElse_WhenItIsRead_ThenItIsReportedAsAbsentRatherThanForbidden() {
        // BR-15. The repository query is scoped by user, so a foreign row and a missing row are
        // indistinguishable from here — which is the point: a 404 leaks nothing about what exists.
        when(repository.findByIdAndUserId(areaId, otherUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(otherUserId, areaId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(areaId.toString());
    }

    @Test
    void GivenAnOwnedArea_WhenItIsUpdated_ThenItsEditableAttributesAreReplaced() {
        HealthArea area = anArea(userId);
        when(repository.findByIdAndUserId(areaId, userId)).thenReturn(Optional.of(area));
        when(repository.save(area)).thenReturn(area);

        service.update(userId, areaId, new HealthAreaDetails("Movement", "Daily walk", 2, "🚶", "#22C55E"));

        assertThat(area.getName()).isEqualTo("Movement");
        assertThat(area.getDescription()).isEqualTo("Daily walk");
        assertThat(area.getPriority()).isEqualTo(2);
        assertThat(area.getIcon()).isEqualTo("🚶");
        assertThat(area.getColor()).isEqualTo("#22C55E");
    }

    @Test
    void GivenDetailsThatOmitTheOptionalFields_WhenAnAreaIsUpdated_ThenTheOmittedOnesAreCleared() {
        // Update replaces rather than patches: an omitted field is a cleared field, not an untouched
        // one. Stated here because "nulls overwrite" is the kind of contract a caller assumes the other
        // way round.
        HealthArea area = anArea(userId);
        when(repository.findByIdAndUserId(areaId, userId)).thenReturn(Optional.of(area));
        when(repository.save(area)).thenReturn(area);

        service.update(userId, areaId, new HealthAreaDetails("Sleep", null, null, null, null));

        assertThat(area.getName()).isEqualTo("Sleep");
        assertThat(area.getDescription()).isNull();
        assertThat(area.getPriority()).isNull();
        assertThat(area.getIcon()).isNull();
        assertThat(area.getColor()).isNull();
    }

    @Test
    void GivenAnAreaOwnedBySomebodyElse_WhenItIsUpdated_ThenNothingIsSaved() {
        when(repository.findByIdAndUserId(areaId, otherUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(otherUserId, areaId, DETAILS))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void GivenAnOwnedArea_WhenItIsDeleted_ThenOnlyTheAreaIsTouched() {
        // FR-8's testable half: there is no port here through which a delete could reach an upgrade.
        HealthArea area = anArea(userId);
        when(repository.findByIdAndUserId(areaId, userId)).thenReturn(Optional.of(area));

        service.delete(userId, areaId);

        verify(repository).delete(area);
    }

    @Test
    void GivenAnAreaOwnedBySomebodyElse_WhenItIsDeleted_ThenNothingIsDeleted() {
        when(repository.findByIdAndUserId(areaId, otherUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(otherUserId, areaId))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(repository, never()).delete(any());
    }

    @Test
    void GivenAUserWithAreas_WhenTheyAreListed_ThenOnlyThatUsersAreasAreReturned() {
        List<HealthArea> areas = List.of(anArea(userId), anArea(userId));
        when(repository.findByUserId(userId)).thenReturn(areas);

        assertThat(service.listByUser(userId)).isEqualTo(areas);
    }

    @Test
    void GivenAUserWithNoAreas_WhenTheyAreListed_ThenTheListIsEmptyRatherThanNull() {
        when(repository.findByUserId(userId)).thenReturn(List.of());

        assertThat(service.listByUser(userId)).isEmpty();
    }

    private HealthArea anArea(UUID owner) {
        return HealthArea.builder()
                .id(areaId)
                .userId(owner)
                .name("Sleep")
                .description("Going to bed on time")
                .priority(1)
                .icon("🌙")
                .color("#4B6BFB")
                .build();
    }
}
