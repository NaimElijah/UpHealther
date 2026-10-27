package com.healthupgrades.tracking.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.healthupgrades.support.ATrackingConfig;
import com.healthupgrades.support.AUser;
import com.healthupgrades.support.AnUpgrade;
import com.healthupgrades.support.PostgresIT;
import com.healthupgrades.tracking.domain.model.TrackingConfig;
import com.healthupgrades.tracking.domain.port.out.TrackingConfigRepositoryPort;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * FR-16's "at most one configuration per upgrade" and NFR-14's batch read, proved against the schema
 * that enforces them.
 *
 * <p>{@code TrackingService.saveConfig} replaces an existing configuration rather than adding one, but
 * that is a read followed by a write, and two saves can both read "none". The unique {@code upgrade_id}
 * column is the backstop, and the service test mocks the repository, so only a real table can show it
 * exists. Who may configure an upgrade is the service's check against the upgrade's owner; what the
 * table itself guarantees is that a configuration belongs to an upgrade that exists.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TrackingConfigRepositoryAdapter.class)
class TrackingConfigPersistenceIT extends PostgresIT {

    /**
     * For the NOT NULL timestamp columns of rows built here. The entity's {@code @PrePersist} stamps its own
     * value over it, so any fixed value serves, and a fixed one keeps the wall clock out of the test.
     */
    private static final LocalDateTime STAMP = LocalDateTime.of(2026, 3, 15, 9, 0);

    @Autowired
    TrackingConfigRepositoryPort repository;

    @Autowired
    TestEntityManager entityManager;

    private UUID userId;
    private UUID upgradeId;

    @BeforeEach
    void createAnUpgradeToConfigure() {
        userId = entityManager
                .persistAndFlush(AUser.aUser()
                        .id(null)
                        .email("tracking-" + UUID.randomUUID() + "@example.com")
                        .build())
                .getId();
        upgradeId = anotherUpgrade();
    }

    /** An unsaved configuration: the id is left null so the database generates it, as production does. */
    private static TrackingConfig configFor(UUID upgradeId) {
        return ATrackingConfig.forUpgrade(upgradeId).id(null).build();
    }

    @Test
    void GivenAnUpgradeAlreadyConfigured_WhenASecondConfigurationIsInserted_ThenTheDatabaseRefusesIt() {
        repository.save(configFor(upgradeId));
        entityManager.flush();

        assertThatThrownBy(() -> {
                    repository.save(configFor(upgradeId));
                    entityManager.flush();
                })
                .isInstanceOfAny(DataIntegrityViolationException.class, ConstraintViolationException.class)
                // Named rather than typed, as in ProgressEntryPersistenceIT: the claim is that this
                // constraint fires, not that some constraint did.
                .hasMessageContaining("tracking_configs_upgrade_id_key");
    }

    @Test
    void GivenSeveralConfiguredUpgrades_WhenSomeAreReadInOneBatch_ThenExactlyThoseComeBack() {
        UUID secondUpgradeId = anotherUpgrade();
        UUID notAskedFor = anotherUpgrade();
        repository.save(configFor(upgradeId));
        repository.save(configFor(secondUpgradeId));
        repository.save(configFor(notAskedFor));
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findByUpgradeIdIn(List.of(upgradeId, secondUpgradeId)))
                .extracting(TrackingConfig::getUpgradeId)
                .containsExactlyInAnyOrder(upgradeId, secondUpgradeId);
    }

    @Test
    void GivenNoSuchUpgrade_WhenAConfigurationIsInsertedForIt_ThenTheDatabaseRefusesIt() {
        UUID noSuchUpgrade = UUID.randomUUID();

        assertThatThrownBy(() -> {
                    repository.save(configFor(noSuchUpgrade));
                    entityManager.flush();
                })
                .isInstanceOfAny(DataIntegrityViolationException.class, ConstraintViolationException.class)
                .hasMessageContaining("tracking_configs_upgrade_id_fkey");
    }

    private UUID anotherUpgrade() {
        return entityManager
                .persistAndFlush(AnUpgrade.ownedBy(userId)
                        .id(null)
                        .status(UpgradeStatus.ACTIVE)
                        .createdAt(STAMP)
                        .updatedAt(STAMP)
                        .build())
                .getId();
    }
}
