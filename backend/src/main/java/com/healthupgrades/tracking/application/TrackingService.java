package com.healthupgrades.tracking.application;

import com.healthupgrades.common.domain.port.out.DomainEventPublisher;
import com.healthupgrades.tracking.domain.event.ProgressEntryRecorded;
import com.healthupgrades.tracking.domain.event.StreakAchieved;
import com.healthupgrades.common.domain.exception.DuplicateProgressException;
import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.tracking.application.port.in.ProgressEntryDetails;
import com.healthupgrades.tracking.application.port.in.ProgressQuery;
import com.healthupgrades.tracking.application.port.in.StreakQuery;
import com.healthupgrades.tracking.application.port.in.StreakSummary;
import com.healthupgrades.tracking.application.port.in.TrackingConfigDetails;
import com.healthupgrades.tracking.application.port.in.TrackingConfigQuery;
import com.healthupgrades.tracking.domain.model.ProgressEntry;
import com.healthupgrades.tracking.domain.service.ProgressEvaluationService;
import com.healthupgrades.tracking.domain.service.StreakCalculator;
import com.healthupgrades.tracking.domain.model.TrackingConfig;
import com.healthupgrades.tracking.domain.port.out.ProgressEntryRepositoryPort;
import com.healthupgrades.tracking.domain.port.out.TrackingConfigRepositoryPort;
import com.healthupgrades.upgrade.application.port.in.UpgradeQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service for tracking configuration and progress logging.
 *
 * <p>Confirms upgrade ownership through the upgrade context's inbound {@link UpgradeQuery} port, and
 * itself implements the tracking inbound ports ({@link TrackingConfigQuery}, {@link ProgressQuery},
 * {@link StreakQuery}) so other contexts can read tracking data as domain objects.
 */
@Service
@RequiredArgsConstructor
public class TrackingService implements TrackingConfigQuery, ProgressQuery, StreakQuery {

    private final TrackingConfigRepositoryPort configRepository; // outbound: tracking configs
    private final ProgressEntryRepositoryPort progressRepository; // outbound: progress entries
    private final UpgradeQuery upgradeQuery; // inbound port of the upgrade context (ownership checks)
    private final StreakCalculator streakCalculator; // pure domain service
    private final ProgressEvaluationService evaluationService; // pure domain service
    private final DomainEventPublisher eventPublisher; // in-process domain events
    private final Clock clock; // single source of "today" for defaulting and streaks

    /**
     * Creates or replaces the tracking configuration for an owned upgrade.
     *
     * <p>Existing entries are not rescored when the configuration changes: a past day was judged by the
     * rule in force when it was logged, and rewriting history would move streaks the user already saw.
     *
     * @param userId    the owner
     * @param upgradeId the upgrade to configure
     * @param details   the configuration to store
     * @return the saved configuration
     * @throws ResourceNotFoundException if the upgrade does not exist or belongs to somebody else
     */
    @Transactional
    public TrackingConfig saveConfig(UUID userId, UUID upgradeId, TrackingConfigDetails details) {
        upgradeQuery.getOwnedUpgrade(userId, upgradeId); // ownership check (throws if not owned)
        TrackingConfig config = configRepository.findByUpgradeId(upgradeId).orElse(
                TrackingConfig.builder().upgradeId(upgradeId).build()
        );
        config.setTrackingType(details.trackingType());
        config.setFrequency(details.frequency());
        config.setTargetNumericValue(details.targetNumericValue());
        config.setTargetUnit(details.targetUnit());
        config.setRequiredDaily(details.requiredDaily());
        return configRepository.save(config);
    }

    /**
     * Reads the tracking configuration of an owned upgrade.
     *
     * @param userId    the owner
     * @param upgradeId the upgrade to read
     * @return the configuration
     * @throws ResourceNotFoundException if the upgrade is not the caller's, or has no configuration
     */
    public TrackingConfig getConfig(UUID userId, UUID upgradeId) {
        upgradeQuery.getOwnedUpgrade(userId, upgradeId);
        return configRepository.findByUpgradeId(upgradeId)
                .orElseThrow(() -> new ResourceNotFoundException("Tracking config not found for upgrade: " + upgradeId));
    }

    /**
     * Logs a day's progress against an owned upgrade and announces it.
     *
     * <p>Two things happen that the caller does not ask for. Completion is recomputed from the tracking
     * configuration, so the stored verdict is the server's rather than the client's; and the resulting
     * streak is measured, raising {@link StreakAchieved} when it lands on a milestone.
     *
     * @param userId    the owner
     * @param upgradeId the upgrade being logged against
     * @param details   the entry; a null date means today by the injected clock
     * @return the persisted entry, carrying the server's completion verdict
     * @throws ResourceNotFoundException if the upgrade does not exist or belongs to somebody else
     * @throws DuplicateProgressException if an entry already exists for that upgrade and date
     */
    @Transactional
    public ProgressEntry recordProgress(UUID userId, UUID upgradeId, ProgressEntryDetails details) {
        upgradeQuery.getOwnedUpgrade(userId, upgradeId);
        LocalDate date = details.date() != null ? details.date() : LocalDate.now(clock);

        if (progressRepository.existsByUpgradeIdAndDate(upgradeId, date)) {
            throw new DuplicateProgressException("Progress already recorded for date: " + date);
        }

        ProgressEntry entry = ProgressEntry.builder()
                .upgradeId(upgradeId)
                .userId(userId)
                .date(date)
                .completed(details.completed())
                .numericValue(details.numericValue())
                .unit(details.unit())
                .rating(details.rating())
                .note(details.note())
                .build();

        // When a tracking config exists, the server decides whether the entry counts as "completed"
        // by evaluating it against the configured target (e.g. numericValue >= target), instead of
        // trusting the client. This keeps streaks and completion rates honest.
        TrackingConfig config = configRepository.findByUpgradeId(upgradeId).orElse(null);
        if (config != null) {
            entry.setCompleted(evaluationService.isSuccessful(entry, config));
        }

        entry = progressRepository.save(entry);
        eventPublisher.publish(new ProgressEntryRecorded(entry.getId(), upgradeId, userId, date, LocalDateTime.now()));

        List<ProgressEntry> allEntries = progressRepository.findByUpgradeIdOrderByDateDesc(upgradeId);
        int streak = streakCalculator.calculateCurrentStreak(allEntries, LocalDate.now(clock));
        // Every seventh day only. Announcing each consecutive day would make the milestone worthless
        // and would put a notification in the user's list once a day per tracked upgrade.
        if (streak > 0 && streak % 7 == 0) {
            eventPublisher.publish(new StreakAchieved(upgradeId, userId, streak, LocalDateTime.now()));
        }

        return entry;
    }

    /**
     * Lists an owned upgrade's progress entries, newest first.
     *
     * @param userId    the owner
     * @param upgradeId the upgrade to read
     * @return its entries, newest first; empty when nothing has been logged
     * @throws ResourceNotFoundException if the upgrade does not exist or belongs to somebody else
     */
    public List<ProgressEntry> getProgress(UUID userId, UUID upgradeId) {
        upgradeQuery.getOwnedUpgrade(userId, upgradeId);
        return progressRepository.findByUpgradeIdOrderByDateDesc(upgradeId);
    }

    /**
     * Everything the user has logged today, across every upgrade.
     *
     * <p>No ownership check is needed: the query is scoped by user id, so it can only return the
     * caller's own entries.
     *
     * @param userId the owner
     * @return today's entries by the injected clock
     */
    public List<ProgressEntry> getTodayProgress(UUID userId) {
        return progressRepository.findByUserIdAndDate(userId, LocalDate.now(clock));
    }

    /**
     * Computes an owned upgrade's current and longest streaks from its history.
     *
     * <p>Computed on each call rather than stored, so a streak cannot drift out of step with the entries
     * behind it.
     *
     * @param userId    the owner
     * @param upgradeId the upgrade to measure
     * @return the streak figures
     * @throws ResourceNotFoundException if the upgrade does not exist or belongs to somebody else
     */
    public StreakSummary getStreakSummary(UUID userId, UUID upgradeId) {
        upgradeQuery.getOwnedUpgrade(userId, upgradeId);
        List<ProgressEntry> entries = progressRepository.findByUpgradeIdOrderByDateDesc(upgradeId);
        return new StreakSummary(
                streakCalculator.calculateCurrentStreak(entries, LocalDate.now(clock)),
                streakCalculator.calculateLongestStreak(entries));
    }

    /**
     * The user's entries over the last seven days, today inclusive.
     *
     * @param userId the owner
     * @return the week's entries, ordered as the store returns them
     */
    public List<ProgressEntry> getWeekProgress(UUID userId) {
        LocalDate today = LocalDate.now(clock);
        LocalDate weekAgo = today.minusDays(6);
        return progressRepository.findByUserIdAndDateBetween(userId, weekAgo, today);
    }

    // ---- TrackingConfigQuery (inbound port) ----

    /** {@inheritDoc} */
    @Override
    public List<TrackingConfig> findByUpgradeIds(Collection<UUID> upgradeIds) {
        return configRepository.findByUpgradeIdIn(upgradeIds);
    }

    // ---- ProgressQuery (inbound port) ----

    /** {@inheritDoc} */
    @Override
    public List<ProgressEntry> findByUserIdAndDate(UUID userId, LocalDate date) {
        return progressRepository.findByUserIdAndDate(userId, date);
    }

    /** {@inheritDoc} */
    @Override
    public List<ProgressEntry> findByUserIdAndDateBetween(UUID userId, LocalDate start, LocalDate end) {
        return progressRepository.findByUserIdAndDateBetween(userId, start, end);
    }

    // ---- StreakQuery (inbound port) ----

    /**
     * {@inheritDoc}
     *
     * <p>Unscoped by user, unlike the rest of this service: the caller is the dashboard, which has
     * already established ownership of the upgrades it is summarising.
     */
    @Override
    public int currentStreak(UUID upgradeId) {
        return streakCalculator.calculateCurrentStreak(
                progressRepository.findByUpgradeIdOrderByDateDesc(upgradeId), LocalDate.now(clock));
    }

}
