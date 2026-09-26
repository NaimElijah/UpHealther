package com.healthupgrades.reflection.adapter.out.persistence;

import com.healthupgrades.reflection.domain.model.Reflection;
import com.healthupgrades.reflection.domain.port.out.ReflectionRepositoryPort;
import com.healthupgrades.support.AUser;
import com.healthupgrades.support.AnUpgrade;
import com.healthupgrades.support.PostgresIT;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-24 — an upgrade's reflections read newest first — proved against the query that orders them.
 *
 * <p>The ordering lives in the repository method's name rather than in any Java the service runs, so
 * the service test, which mocks the port, cannot see it. Unlike progress entries (BR-6), several
 * reflections may share a date, and a sort on the date alone leaves those in whatever order the
 * database happens to scan them.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ReflectionRepositoryAdapter.class)
class ReflectionPersistenceIT extends PostgresIT {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 15);

    @Autowired ReflectionRepositoryPort repository;
    @Autowired TestEntityManager entityManager;

    private UUID userId;
    private UUID upgradeId;

    @BeforeEach
    void createAnUpgradeToReflectOn() {
        userId = entityManager.persistAndFlush(AUser.aUser().id(null).build()).getId();
        upgradeId = entityManager.persistAndFlush(
                AnUpgrade.ownedBy(userId).id(null).status(UpgradeStatus.ACTIVE)
                        .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build()).getId();
    }

    /** Saves a reflection on {@code date}; {@code whatWorked} is what the assertions read it back by. */
    private UUID reflect(LocalDate date, String whatWorked) {
        return repository.save(Reflection.builder()
                .upgradeId(upgradeId).userId(userId).date(date).whatWorked(whatWorked).build()).getId();
    }

    /**
     * Pins when a reflection was written. {@code @PrePersist} stamps the wall clock, so two saves in a
     * row could in principle share a timestamp; setting it outright keeps the test deterministic.
     */
    private void writtenAt(UUID reflectionId, LocalDateTime at) {
        entityManager.getEntityManager()
                .createNativeQuery("UPDATE reflections SET created_at = :at WHERE id = :id")
                .setParameter("at", at)
                .setParameter("id", reflectionId)
                .executeUpdate();
    }

    @Test
    void GivenReflectionsOnSeveralDates_WhenAnUpgradesReflectionsAreRead_ThenTheNewestDateComesFirst() {
        reflect(DAY.minusDays(2), "oldest");
        reflect(DAY, "newest");
        reflect(DAY.minusDays(1), "middle");
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findByUpgradeIdOrderByDateDescCreatedAtDesc(upgradeId))
                .extracting(Reflection::getWhatWorked)
                .containsExactly("newest", "middle", "oldest");
    }

    @Test
    void GivenTwoReflectionsOnTheSameDate_WhenAnUpgradesReflectionsAreRead_ThenTheLaterWrittenComesFirst() {
        // Inserted earlier-first, so the scan order a date-only sort falls back on is the wrong one.
        UUID morning = reflect(DAY, "written in the morning");
        UUID evening = reflect(DAY, "written in the evening");
        entityManager.flush();
        writtenAt(morning, DAY.atTime(8, 0));
        writtenAt(evening, DAY.atTime(20, 0));
        entityManager.clear();

        assertThat(repository.findByUpgradeIdOrderByDateDescCreatedAtDesc(upgradeId))
                .extracting(Reflection::getWhatWorked)
                .containsExactly("written in the evening", "written in the morning");
    }
}
