package com.healthupgrades.healtharea.adapter.out.persistence;

import com.healthupgrades.healtharea.domain.model.HealthArea;
import com.healthupgrades.healtharea.domain.port.out.HealthAreaRepositoryPort;
import com.healthupgrades.support.AUser;
import com.healthupgrades.support.AnUpgrade;
import com.healthupgrades.support.PostgresIT;
import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-8 — deleting a health area leaves the upgrades filed under it intact — which is a claim about the
 * schema, not about any Java that runs.
 *
 * <p>{@code HealthAreaService.delete} calls the area repository and nothing else, so what happens to
 * those upgrades is decided entirely by the foreign key. The service test can show the service does not
 * cascade; only this can show the database does not either.
 *
 * <p>What it turns out to do is <em>set the reference to null</em>
 * ({@code area_id ... ON DELETE SET NULL}) rather than leave a dangling id, so an upgrade survives and
 * becomes unfiled. That is the behaviour FR-8 asks for, and it is asserted here rather than assumed.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(HealthAreaRepositoryAdapter.class)
class HealthAreaPersistenceIT extends PostgresIT {

    @Autowired HealthAreaRepositoryPort repository;
    @Autowired TestEntityManager entityManager;

    private UUID ownerId;
    private UUID strangerId;

    @BeforeEach
    void createTwoUsers() {
        ownerId = entityManager.persistAndFlush(AUser.aUser().id(null).build()).getId();
        strangerId = entityManager.persistAndFlush(
                AUser.aUser().id(null).email("stranger@example.com").build()).getId();
    }

    @Test
    void GivenAnAreaWithUpgradesFiledUnderIt_WhenTheAreaIsDeleted_ThenThoseUpgradesSurvive() {
        HealthArea area = persistedArea(ownerId);
        HealthUpgrade upgrade = entityManager.persistAndFlush(
                AnUpgrade.ownedBy(ownerId).id(null).areaId(area.getId()).status(UpgradeStatus.ACTIVE)
                        .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        UUID upgradeId = upgrade.getId();

        repository.delete(area);
        entityManager.flush();
        entityManager.clear();

        HealthUpgrade survivor = entityManager.find(HealthUpgrade.class, upgradeId);
        assertThat(survivor).as("deleting an area must not delete what was filed under it").isNotNull();
        assertThat(survivor.getAreaId())
                .as("the schema sets the reference to null rather than leaving it dangling")
                .isNull();
    }

    @Test
    void GivenAnAreaOwnedByOneUser_WhenAnotherUserLooksItUpById_ThenNothingComesBack() {
        UUID areaId = persistedArea(ownerId).getId();
        entityManager.clear();

        assertThat(repository.findByIdAndUserId(areaId, strangerId)).isEmpty();
        assertThat(repository.findByIdAndUserId(areaId, ownerId)).isPresent();
    }

    @Test
    void GivenAreasBelongingToTwoUsers_WhenOneListsTheirs_ThenTheOthersAreNotIncluded() {
        persistedArea(ownerId);
        persistedArea(strangerId);
        entityManager.clear();

        assertThat(repository.findByUserId(ownerId)).hasSize(1);
    }

    private HealthArea persistedArea(UUID userId) {
        return entityManager.persistAndFlush(HealthArea.builder()
                .userId(userId).name("Sleep").priority(1).icon("🌙").color("#4B6BFB")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build());
    }
}
