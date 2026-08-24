package com.healthupgrades.reflection.application;

import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.common.domain.port.out.DomainEventPublisher;
import com.healthupgrades.reflection.application.port.in.ReflectionDetails;
import com.healthupgrades.reflection.domain.event.ReflectionAdded;
import com.healthupgrades.reflection.domain.model.Reflection;
import com.healthupgrades.reflection.domain.port.out.ReflectionRepositoryPort;
import com.healthupgrades.support.AnUpgrade;
import com.healthupgrades.upgrade.application.port.in.UpgradeQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers FR-23 (write a reflection), FR-24 (read an upgrade's reflections, newest first) and BR-13
 * (reflections are append-only).
 *
 * <p>BR-13 is a claim about what the service does <em>not</em> offer, which no ordinary test can make by
 * calling something. It is asserted against the public method surface instead: an {@code update} or
 * {@code delete} appearing on this service or on its outbound port is what would break the rule, and
 * that is what the test looks for.
 */
@ExtendWith(MockitoExtension.class)
class ReflectionServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);
    private static final ReflectionDetails DETAILS = new ReflectionDetails(
            null, 4, 5, "Kept the phone out of the bedroom", "Weekends slipped", "Set a weekend alarm too");

    private final UUID userId = UUID.randomUUID();
    private final UUID upgradeId = UUID.randomUUID();

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-03-15T09:00:00Z"), ZoneOffset.UTC);

    @Mock ReflectionRepositoryPort repository;
    @Mock UpgradeQuery upgradeQuery;
    @Mock DomainEventPublisher eventPublisher;

    private ReflectionService service;

    @BeforeEach
    void setUp() {
        service = new ReflectionService(repository, upgradeQuery, eventPublisher, fixedClock);
    }

    @Test
    void GivenAnOwnedUpgrade_WhenAReflectionIsWritten_ThenItIsStoredWithItsRatingsAndNotes() {
        when(upgradeQuery.getOwnedUpgrade(userId, upgradeId)).thenReturn(AnUpgrade.active(userId));
        when(repository.save(any(Reflection.class))).thenAnswer(call -> call.getArgument(0));

        service.create(userId, upgradeId, DETAILS);

        ArgumentCaptor<Reflection> saved = ArgumentCaptor.forClass(Reflection.class);
        verify(repository).save(saved.capture());
        Reflection reflection = saved.getValue();
        assertThat(reflection.getUpgradeId()).isEqualTo(upgradeId);
        assertThat(reflection.getUserId()).isEqualTo(userId);
        assertThat(reflection.getDifficultyRating()).isEqualTo(4);
        assertThat(reflection.getBenefitRating()).isEqualTo(5);
        assertThat(reflection.getWhatWorked()).isEqualTo("Kept the phone out of the bedroom");
        assertThat(reflection.getWhatDidNotWork()).isEqualTo("Weekends slipped");
        assertThat(reflection.getNextAdjustment()).isEqualTo("Set a weekend alarm too");
    }

    @Test
    void GivenNoDateOnTheReflection_WhenItIsWritten_ThenItIsDatedFromTheInjectedClock() {
        // NFR-15. Reading the wall clock here would make the test fail once a day around midnight.
        when(upgradeQuery.getOwnedUpgrade(userId, upgradeId)).thenReturn(AnUpgrade.active(userId));
        when(repository.save(any(Reflection.class))).thenAnswer(call -> call.getArgument(0));

        service.create(userId, upgradeId, DETAILS);

        ArgumentCaptor<Reflection> saved = ArgumentCaptor.forClass(Reflection.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getDate()).isEqualTo(TODAY);
    }

    @Test
    void GivenAnExplicitDate_WhenAReflectionIsWritten_ThenThatDateIsKept() {
        LocalDate backdated = TODAY.minusDays(3);
        when(upgradeQuery.getOwnedUpgrade(userId, upgradeId)).thenReturn(AnUpgrade.active(userId));
        when(repository.save(any(Reflection.class))).thenAnswer(call -> call.getArgument(0));

        service.create(userId, upgradeId, new ReflectionDetails(backdated, 3, 3, null, null, null));

        ArgumentCaptor<Reflection> saved = ArgumentCaptor.forClass(Reflection.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getDate()).isEqualTo(backdated);
    }

    @Test
    void GivenAReflectionIsWritten_WhenItIsStored_ThenItIsAnnounced() {
        UUID reflectionId = UUID.randomUUID();
        when(upgradeQuery.getOwnedUpgrade(userId, upgradeId)).thenReturn(AnUpgrade.active(userId));
        when(repository.save(any(Reflection.class)))
                .thenAnswer(call -> {
                    Reflection r = call.getArgument(0);
                    r.setId(reflectionId);
                    return r;
                });

        service.create(userId, upgradeId, DETAILS);

        ArgumentCaptor<ReflectionAdded> event = ArgumentCaptor.forClass(ReflectionAdded.class);
        verify(eventPublisher).publish(event.capture());
        assertThat(event.getValue().reflectionId()).isEqualTo(reflectionId);
        assertThat(event.getValue().upgradeId()).isEqualTo(upgradeId);
        assertThat(event.getValue().userId()).isEqualTo(userId);
    }

    @Test
    void GivenAnUpgradeOwnedBySomebodyElse_WhenAReflectionIsWritten_ThenNothingIsStoredOrAnnounced() {
        when(upgradeQuery.getOwnedUpgrade(userId, upgradeId))
                .thenThrow(new ResourceNotFoundException("Upgrade not found: " + upgradeId));

        assertThatThrownBy(() -> service.create(userId, upgradeId, DETAILS))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void GivenAnOwnedUpgrade_WhenItsReflectionsAreRead_ThenTheyComeBackNewestFirst() {
        // The ordering is the repository query's, so what this pins is that the service asks for the
        // ordered query rather than the unordered one and hands the result through untouched.
        List<Reflection> newestFirst = List.of(
                Reflection.builder().date(TODAY).build(),
                Reflection.builder().date(TODAY.minusDays(1)).build());
        when(upgradeQuery.getOwnedUpgrade(userId, upgradeId)).thenReturn(AnUpgrade.active(userId));
        when(repository.findByUpgradeIdOrderByDateDesc(upgradeId)).thenReturn(newestFirst);

        assertThat(service.getForUpgrade(userId, upgradeId)).isEqualTo(newestFirst);
    }

    @Test
    void GivenAnUpgradeOwnedBySomebodyElse_WhenItsReflectionsAreRead_ThenTheyAreNotDisclosed() {
        when(upgradeQuery.getOwnedUpgrade(userId, upgradeId))
                .thenThrow(new ResourceNotFoundException("Upgrade not found: " + upgradeId));

        assertThatThrownBy(() -> service.getForUpgrade(userId, upgradeId))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(repository, never()).findByUpgradeIdOrderByDateDesc(any());
    }

    @Test
    void GivenTheReflectionContext_WhenItsSurfaceIsInspected_ThenItOffersNoWayToEditOrDeleteOne() {
        // BR-13, stated as a rule about the API rather than about a call. Adding an edit or delete path
        // to either the service or the port is what would break it, and this is what notices.
        assertThat(publicMethodNames(ReflectionService.class))
                .containsExactlyInAnyOrder("create", "getForUpgrade");
        assertThat(publicMethodNames(ReflectionRepositoryPort.class))
                .containsExactlyInAnyOrder("save", "findByUpgradeIdOrderByDateDesc");
    }

    private static List<String> publicMethodNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .filter(m -> !m.isSynthetic())
                .map(Method::getName)
                .distinct()
                .toList();
    }
}
