package com.healthupgrades.tracking.application;

import com.healthupgrades.common.events.DomainEventPublisher;
import com.healthupgrades.common.events.ProgressEntryRecorded;
import com.healthupgrades.common.events.StreakAchieved;
import com.healthupgrades.common.exception.DuplicateProgressException;
import com.healthupgrades.common.exception.ResourceNotFoundException;
import com.healthupgrades.tracking.api.ProgressDto;
import com.healthupgrades.tracking.api.ProgressRequest;
import com.healthupgrades.tracking.api.TrackingConfigDto;
import com.healthupgrades.tracking.api.TrackingConfigRequest;
import com.healthupgrades.tracking.domain.ProgressEntry;
import com.healthupgrades.tracking.domain.StreakCalculator;
import com.healthupgrades.tracking.domain.TrackingConfig;
import com.healthupgrades.tracking.infrastructure.ProgressEntryRepository;
import com.healthupgrades.tracking.infrastructure.TrackingConfigRepository;
import com.healthupgrades.upgrade.application.UpgradeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TrackingService {

    private final TrackingConfigRepository configRepository;
    private final ProgressEntryRepository progressRepository;
    private final UpgradeService upgradeService;
    private final StreakCalculator streakCalculator;
    private final DomainEventPublisher eventPublisher;

    @Transactional
    public TrackingConfigDto saveConfig(UUID userId, UUID upgradeId, TrackingConfigRequest req) {
        upgradeService.getUpgrade(userId, upgradeId);
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

    public TrackingConfigDto getConfig(UUID userId, UUID upgradeId) {
        upgradeService.getUpgrade(userId, upgradeId);
        return configRepository.findByUpgradeId(upgradeId)
                .map(this::toConfigDto)
                .orElseThrow(() -> new ResourceNotFoundException("Tracking config not found for upgrade: " + upgradeId));
    }

    @Transactional
    public ProgressDto recordProgress(UUID userId, UUID upgradeId, ProgressRequest req) {
        upgradeService.getUpgrade(userId, upgradeId);
        LocalDate date = req.date() != null ? req.date() : LocalDate.now();

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
        entry = progressRepository.save(entry);
        eventPublisher.publish(new ProgressEntryRecorded(entry.getId(), upgradeId, userId, date, LocalDateTime.now()));

        List<ProgressEntry> allEntries = progressRepository.findByUpgradeIdOrderByDateDesc(upgradeId);
        int streak = streakCalculator.calculateCurrentStreak(allEntries);
        if (streak > 0 && streak % 7 == 0) {
            eventPublisher.publish(new StreakAchieved(upgradeId, userId, streak, LocalDateTime.now()));
        }

        return toDto(entry);
    }

    public List<ProgressDto> getProgress(UUID userId, UUID upgradeId) {
        upgradeService.getUpgrade(userId, upgradeId);
        return progressRepository.findByUpgradeIdOrderByDateDesc(upgradeId).stream().map(this::toDto).toList();
    }

    public List<ProgressDto> getTodayProgress(UUID userId) {
        return progressRepository.findByUserIdAndDate(userId, LocalDate.now()).stream().map(this::toDto).toList();
    }

    public List<ProgressDto> getWeekProgress(UUID userId) {
        LocalDate today = LocalDate.now();
        LocalDate weekAgo = today.minusDays(6);
        return progressRepository.findByUserIdAndDateBetween(userId, weekAgo, today).stream().map(this::toDto).toList();
    }

    private TrackingConfigDto toConfigDto(TrackingConfig c) {
        return new TrackingConfigDto(c.getId(), c.getUpgradeId(), c.getTrackingType(),
                c.getFrequency(), c.getTargetNumericValue(), c.getTargetUnit(), c.getRequiredDaily());
    }

    private ProgressDto toDto(ProgressEntry e) {
        return new ProgressDto(e.getId(), e.getUpgradeId(), e.getUserId(), e.getDate(),
                e.getCompleted(), e.getNumericValue(), e.getUnit(), e.getRating(), e.getNote(), e.getCreatedAt());
    }
}
