package com.healthupgrades.tracking.application;

import com.healthupgrades.common.domain.event.DomainEventPublisher;
import com.healthupgrades.common.domain.event.ProgressEntryRecorded;
import com.healthupgrades.common.domain.event.StreakAchieved;
import com.healthupgrades.common.domain.exception.DuplicateProgressException;
import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.tracking.adapter.in.web.ProgressDto;
import com.healthupgrades.tracking.adapter.in.web.ProgressRequest;
import com.healthupgrades.tracking.adapter.in.web.StreakDto;
import com.healthupgrades.tracking.adapter.in.web.TrackingConfigDto;
import com.healthupgrades.tracking.adapter.in.web.TrackingConfigRequest;
import com.healthupgrades.tracking.application.port.in.ProgressQuery;
import com.healthupgrades.tracking.application.port.in.StreakQuery;
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

    /** Creates or updates the tracking config for an owned upgrade. */
    @Transactional
    public TrackingConfigDto saveConfig(UUID userId, UUID upgradeId, TrackingConfigRequest req) {
        upgradeQuery.getOwnedUpgrade(userId, upgradeId); // ownership check (throws if not owned)
        TrackingConfig config = configRepository.findByUpgradeId(upgradeId).orElse(
                TrackingConfig.builder().upgradeId(upgradeId).build()
        );
        config.setTrackingType(req.trackingType());
        config.setFrequency(req.frequency());
        config.setTargetNumericValue(req.targetNumericValue());
        config.setTargetUnit(req.targetUnit());
        config.setRequiredDaily(req.requiredDaily());
        return toConfigDto(configRepository.save(config));
    }

    /** Returns the tracking config for an owned upgrade. */
    public TrackingConfigDto getConfig(UUID userId, UUID upgradeId) {
        upgradeQuery.getOwnedUpgrade(userId, upgradeId);
        return configRepository.findByUpgradeId(upgradeId)
                .map(this::toConfigDto)
                .orElseThrow(() -> new ResourceNotFoundException("Tracking config not found for upgrade: " + upgradeId));
    }

    /** Records a progress entry for an owned upgrade, deriving completion from the config when present. */
    @Transactional
    public ProgressDto recordProgress(UUID userId, UUID upgradeId, ProgressRequest req) {
        upgradeQuery.getOwnedUpgrade(userId, upgradeId);
        LocalDate date = req.date() != null ? req.date() : LocalDate.now(clock);

        if (progressRepository.existsByUpgradeIdAndDate(upgradeId, date)) {
            throw new DuplicateProgressException("Progress already recorded for date: " + date);
        }

        ProgressEntry entry = ProgressEntry.builder()
                .upgradeId(upgradeId)
                .userId(userId)
                .date(date)
                .completed(req.completed())
                .numericValue(req.numericValue())
                .unit(req.unit())
                .rating(req.rating())
                .note(req.note())
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
        if (streak > 0 && streak % 7 == 0) { // celebrate every 7-day milestone
            eventPublisher.publish(new StreakAchieved(upgradeId, userId, streak, LocalDateTime.now()));
        }

        return toDto(entry);
    }

    /** Lists progress entries for an owned upgrade, newest first. */
    public List<ProgressDto> getProgress(UUID userId, UUID upgradeId) {
        upgradeQuery.getOwnedUpgrade(userId, upgradeId);
        return progressRepository.findByUpgradeIdOrderByDateDesc(upgradeId).stream().map(this::toDto).toList();
    }

    /** Today's progress entries for the caller across all upgrades. */
    public List<ProgressDto> getTodayProgress(UUID userId) {
        return progressRepository.findByUserIdAndDate(userId, LocalDate.now(clock)).stream().map(this::toDto).toList();
    }

    /** Current and longest streak for an owned upgrade. */
    public StreakDto getStreakSummary(UUID userId, UUID upgradeId) {
        upgradeQuery.getOwnedUpgrade(userId, upgradeId);
        List<ProgressEntry> entries = progressRepository.findByUpgradeIdOrderByDateDesc(upgradeId);
        return new StreakDto(
                streakCalculator.calculateCurrentStreak(entries, LocalDate.now(clock)),
                streakCalculator.calculateLongestStreak(entries));
    }

    /** The caller's progress entries over the last 7 days. */
    public List<ProgressDto> getWeekProgress(UUID userId) {
        LocalDate today = LocalDate.now(clock);
        LocalDate weekAgo = today.minusDays(6);
        return progressRepository.findByUserIdAndDateBetween(userId, weekAgo, today).stream().map(this::toDto).toList();
    }

    // ---- TrackingConfigQuery (inbound port) ----

    /** {@inheritDoc} */
    @Override
    public Optional<TrackingConfig> findByUpgradeId(UUID upgradeId) {
        return configRepository.findByUpgradeId(upgradeId);
    }

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

    /** {@inheritDoc} */
    @Override
    public int currentStreak(UUID upgradeId) {
        // Load the upgrade's entries and delegate to the pure domain streak calculator.
        return streakCalculator.calculateCurrentStreak(
                progressRepository.findByUpgradeIdOrderByDateDesc(upgradeId), LocalDate.now(clock));
    }

    /** Maps a tracking-config domain object to its web DTO. */
    private TrackingConfigDto toConfigDto(TrackingConfig c) {
        return new TrackingConfigDto(c.getId(), c.getUpgradeId(), c.getTrackingType(),
                c.getFrequency(), c.getTargetNumericValue(), c.getTargetUnit(), c.getRequiredDaily());
    }

    /** Maps a progress-entry domain object to its web DTO. */
    private ProgressDto toDto(ProgressEntry e) {
        return new ProgressDto(e.getId(), e.getUpgradeId(), e.getUserId(), e.getDate(),
                e.getCompleted(), e.getNumericValue(), e.getUnit(), e.getRating(), e.getNote(), e.getCreatedAt());
    }
}
