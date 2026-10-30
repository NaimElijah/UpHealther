package com.healthupgrades.tracking.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.common.domain.exception.RetryableConflictException;
import com.healthupgrades.support.ATrackingConfig;
import com.healthupgrades.support.AUser;
import com.healthupgrades.support.AnUpgrade;
import com.healthupgrades.support.PostgresIT;
import com.healthupgrades.tracking.domain.model.TrackingConfig;
import com.healthupgrades.tracking.domain.port.out.TrackingConfigRepositoryPort;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
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
        // Inserted around the adapter, which translates this refusal: the claim here is the schema's.
        entityManager.persistAndFlush(configFor(upgradeId));

        assertThatThrownBy(() -> entityManager.persistAndFlush(configFor(upgradeId)))
                .isInstanceOfAny(DataIntegrityViolationException.class, ConstraintViolationException.class)
                // Named rather than typed, as in ProgressEntryPersistenceIT: the claim is that this
                // constraint fires, not that some constraint did.
                .hasMessageContaining("tracking_configs_upgrade_id_key");
    }

    @Test
    void
            GivenAnUpgradeConfiguredConcurrently_WhenASecondFirstConfigurationIsSaved_ThenItIsReportedAsRetryableInsideTheCall() {
        // The flush is the point: without it the violation is raised at commit, after the service has
        // returned, where nothing can translate it and the caller receives a 500 (NFR-7, ADR-074).
        repository.save(configFor(upgradeId));

        assertThatThrownBy(() -> repository.save(configFor(upgradeId)))
                .isInstanceOf(RetryableConflictException.class)
                .hasMessage(TrackingConfigRepositoryAdapter.CONFIGURED_CONCURRENTLY);
    }

    @Test
    void
            GivenAnUpgradeDeletedAfterItsOwnershipWasChecked_WhenItsFirstConfigurationIsSaved_ThenItIsReportedAsNotFound() {
        // Another tab deletes the upgrade between the service's ownership check and this insert. The
        // foreign key refuses it, and the caller is told what the ownership check would now say (ADR-074).
        UUID deletedUpgrade = UUID.randomUUID();

        assertThatThrownBy(() -> repository.save(configFor(deletedUpgrade)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Upgrade not found: " + deletedUpgrade);
    }

    @Test
    void GivenAViolationOfNeitherTranslatedConstraint_WhenAConfigurationIsSaved_ThenItIsRethrownUnchanged() {
        // Only the unique upgrade_id and the upgrade foreign key have a meaning the client can act on. A
        // missing tracking type is a defect upstream: translating it would answer a 500's cause with a
        // 409 or a 404 that misleads.
        TrackingConfig withoutAType = ATrackingConfig.forUpgrade(upgradeId)
                .id(null)
                .trackingType(null)
                .build();

        assertThatThrownBy(() -> repository.save(withoutAType))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(RetryableConflictException.class)
                .isNotInstanceOf(ResourceNotFoundException.class);
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
        // Inserted around the adapter, which translates this refusal: the claim here is the schema's.
        UUID noSuchUpgrade = UUID.randomUUID();

        assertThatThrownBy(() -> entityManager.persistAndFlush(configFor(noSuchUpgrade)))
                .isInstanceOfAny(DataIntegrityViolationException.class, ConstraintViolationException.class)
                .hasMessageContaining("tracking_configs_upgrade_id_fkey");
    }

    private UUID anotherUpgrade() {
        return entityManager
                .persistAndFlush(AnUpgrade.ownedBy(userId)
                        .id(null)
                        .status(UpgradeStatus.ACTIVE)
                        .build())
                .getId();
    }
}
