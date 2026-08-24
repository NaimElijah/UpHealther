package com.healthupgrades.tracking.application;

import com.healthupgrades.common.domain.port.out.DomainEventPublisher;
import com.healthupgrades.common.domain.exception.DuplicateProgressException;
import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.support.ATrackingConfig;
import com.healthupgrades.support.AProgressEntry;
import com.healthupgrades.tracking.application.port.in.ProgressEntryDetails;
import com.healthupgrades.tracking.application.port.in.StreakSummary;
import com.healthupgrades.tracking.application.port.in.TrackingConfigDetails;
import com.healthupgrades.tracking.domain.event.ProgressEntryRecorded;
import com.healthupgrades.tracking.domain.event.StreakAchieved;
import com.healthupgrades.tracking.domain.model.Frequency;
import com.healthupgrades.tracking.domain.model.ProgressEntry;
import com.healthupgrades.tracking.domain.service.ProgressEvaluationService;
import com.healthupgrades.tracking.domain.service.StreakCalculator;
import com.healthupgrades.tracking.domain.model.TrackingConfig;
import com.healthupgrades.tracking.domain.model.TrackingType;
import com.healthupgrades.tracking.domain.port.out.ProgressEntryRepositoryPort;
import com.healthupgrades.tracking.domain.port.out.TrackingConfigRepositoryPort;
import com.healthupgrades.upgrade.application.port.in.UpgradeQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * Covers the tracking use cases: FR-16 and FR-17 (how an upgrade is measured), FR-18 (log a day),
 * FR-20 (read the history), FR-21 (today and the last seven days), FR-22 (streaks), BR-6 (one entry per
 * upgrade per date), BR-7 (the server decides whether an entry counts) and BR-10 (a milestone every
 * seventh day).
 *
 * <p>{@link StreakCalculator} and {@link ProgressEvaluationService} are mocked here even though they are
 * pure domain services with real tests of their own. That is deliberate: this class is about what the
 * service does <em>with</em> their verdicts, and stubbing the verdict is what lets BR-10 be exercised at
 * a streak of exactly 7 without constructing seven days of history that would then be testing
 * {@code StreakCalculator} a second time.
 */
@ExtendWith(MockitoExtension.class)
class TrackingServiceTest {

    @Mock TrackingConfigRepositoryPort configRepository;
    @Mock ProgressEntryRepositoryPort progressRepository;
    @Mock UpgradeQuery upgradeQuery;
    @Mock StreakCalculator streakCalculator;
    @Mock ProgressEvaluationService evaluationService;
    @Mock DomainEventPublisher eventPublisher;

    /** Fixed so "today" is decided here rather than by whenever the suite happens to run. */
    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-03-15T09:00:00Z"), ZoneOffset.UTC);
    private final LocalDate today = LocalDate.of(2026, 3, 15);

    private TrackingService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID upgradeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TrackingService(configRepository, progressRepository, upgradeQuery,
                streakCalculator, evaluationService, eventPublisher, fixedClock);
    }

    // ---- Recording progress ----

    @Test
    void GivenATrackingConfig_WhenProgressIsRecorded_ThenCompletionIsDecidedByTheEvaluation() {
        // The client did not supply `completed`; a NUMERIC config exists and the evaluator says "met".
        ProgressEntryDetails req = new ProgressEntryDetails(today, null, 2.5, "liters", null, null);
        TrackingConfig config = TrackingConfig.builder()
                .upgradeId(upgradeId).trackingType(TrackingType.NUMERIC).targetNumericValue(2.0).build();

        when(progressRepository.existsByUpgradeIdAndDate(any(), any())).thenReturn(false);
        when(configRepository.findByUpgradeId(upgradeId)).thenReturn(Optional.of(config));
        when(evaluationService.isSuccessful(any(), any())).thenReturn(true);
        when(progressRepository.save(any(ProgressEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(progressRepository.findByUpgradeIdOrderByDateDesc(upgradeId)).thenReturn(List.of());
        when(streakCalculator.calculateCurrentStreak(any(), any())).thenReturn(3);

        ProgressEntry saved = service.recordProgress(userId, upgradeId, req);

        assertThat(saved.getCompleted()).isTrue();
        verify(evaluationService).isSuccessful(any(), any());
    }

    @Test
    void GivenAnEntryAlreadyExistsForTheDate_WhenProgressIsRecordedAgain_ThenItIsRefusedAndNothingIsSaved() {
        ProgressEntryDetails req = new ProgressEntryDetails(today, true, null, null, null, null);
        when(progressRepository.existsByUpgradeIdAndDate(any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.recordProgress(userId, upgradeId, req))
                .isInstanceOf(DuplicateProgressException.class);

        verify(progressRepository, never()).save(any());
    }

    @Test
    void GivenTheClientClaimsSuccess_WhenTheEvaluationDisagrees_ThenTheServersVerdictIsStored() {
        // BR-7 from the other side. The client says it counted; the configured target says otherwise,
        // and the stored row must carry the server's answer or streaks stop meaning anything.
        ProgressEntryDetails req = new ProgressEntryDetails(today, true, 1.0, "liters", null, null);

        when(progressRepository.existsByUpgradeIdAndDate(any(), any())).thenReturn(false);
        when(configRepository.findByUpgradeId(upgradeId))
                .thenReturn(Optional.of(ATrackingConfig.numeric(upgradeId, 2.0, "liters")));
        when(evaluationService.isSuccessful(any(), any())).thenReturn(false);
        when(progressRepository.save(any(ProgressEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(progressRepository.findByUpgradeIdOrderByDateDesc(upgradeId)).thenReturn(List.of());
        when(streakCalculator.calculateCurrentStreak(any(), any())).thenReturn(1);

        assertThat(service.recordProgress(userId, upgradeId, req).getCompleted()).isFalse();
    }

    @Test
    void GivenNoTrackingConfig_WhenProgressIsRecorded_ThenTheEntryIsStoredWithoutBeingScored() {
        // An upgrade with no configuration cannot be scored, so the value is left as supplied rather
        // than guessed at.
        ProgressEntryDetails req = new ProgressEntryDetails(today, true, null, null, null, null);

        when(progressRepository.existsByUpgradeIdAndDate(any(), any())).thenReturn(false);
        when(configRepository.findByUpgradeId(upgradeId)).thenReturn(Optional.empty());
        when(progressRepository.save(any(ProgressEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(progressRepository.findByUpgradeIdOrderByDateDesc(upgradeId)).thenReturn(List.of());
        when(streakCalculator.calculateCurrentStreak(any(), any())).thenReturn(1);

        assertThat(service.recordProgress(userId, upgradeId, req).getCompleted()).isTrue();
        verify(evaluationService, never()).isSuccessful(any(), any());
    }

    @Test
    void GivenNoDateOnTheEntry_WhenProgressIsRecorded_ThenItIsDatedFromTheInjectedClock() {
        ProgressEntryDetails req = new ProgressEntryDetails(null, true, null, null, null, null);

        when(progressRepository.existsByUpgradeIdAndDate(upgradeId, today)).thenReturn(false);
        when(configRepository.findByUpgradeId(upgradeId)).thenReturn(Optional.empty());
        when(progressRepository.save(any(ProgressEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(progressRepository.findByUpgradeIdOrderByDateDesc(upgradeId)).thenReturn(List.of());
        when(streakCalculator.calculateCurrentStreak(any(), any())).thenReturn(1);

        assertThat(service.recordProgress(userId, upgradeId, req).getDate()).isEqualTo(today);
    }

    @Test
    void GivenProgressIsRecorded_WhenItIsStored_ThenItIsAnnounced() {
        UUID entryId = UUID.randomUUID();
        ProgressEntryDetails req = new ProgressEntryDetails(today, true, null, null, null, null);

        when(progressRepository.existsByUpgradeIdAndDate(any(), any())).thenReturn(false);
        when(configRepository.findByUpgradeId(upgradeId)).thenReturn(Optional.empty());
        when(progressRepository.save(any(ProgressEntry.class))).thenAnswer(inv -> {
            ProgressEntry e = inv.getArgument(0);
            e.setId(entryId);
            return e;
        });
        when(progressRepository.findByUpgradeIdOrderByDateDesc(upgradeId)).thenReturn(List.of());
        when(streakCalculator.calculateCurrentStreak(any(), any())).thenReturn(1);

        service.recordProgress(userId, upgradeId, req);

        ArgumentCaptor<ProgressEntryRecorded> event = ArgumentCaptor.forClass(ProgressEntryRecorded.class);
        verify(eventPublisher).publish(event.capture());
        assertThat(event.getValue().progressId()).isEqualTo(entryId);
        assertThat(event.getValue().upgradeId()).isEqualTo(upgradeId);
        assertThat(event.getValue().userId()).isEqualTo(userId);
        assertThat(event.getValue().date()).isEqualTo(today);
    }

    @Test
    void GivenAnUpgradeOwnedBySomebodyElse_WhenProgressIsRecorded_ThenNothingIsStoredOrAnnounced() {
        when(upgradeQuery.getOwnedUpgrade(userId, upgradeId))
                .thenThrow(new ResourceNotFoundException("Upgrade not found: " + upgradeId));

        assertThatThrownBy(() -> service.recordProgress(userId, upgradeId,
                new ProgressEntryDetails(today, true, null, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(progressRepository, never()).save(any());
        verify(eventPublisher, never()).publish(any());
    }

    // ---- BR-10: a milestone every seventh day ----

    @ParameterizedTest
    @ValueSource(ints = {7, 14, 21, 70})
    void GivenTheStreakLandsOnASeventhDay_WhenProgressIsRecorded_ThenTheMilestoneIsAnnounced(int streak) {
        recordWithStreak(streak);

        ArgumentCaptor<StreakAchieved> event = ArgumentCaptor.forClass(StreakAchieved.class);
        verify(eventPublisher).publish(event.capture());
        assertThat(event.getValue().streakDays()).isEqualTo(streak);
        assertThat(event.getValue().upgradeId()).isEqualTo(upgradeId);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 6, 8, 13, 69})
    void GivenTheStreakIsNotASeventhDay_WhenProgressIsRecorded_ThenNoMilestoneIsAnnounced(int streak) {
        // Announcing every consecutive day would put a notification in the list once a day per tracked
        // upgrade, which is what makes the milestone worth nothing.
        recordWithStreak(streak);

        verify(eventPublisher, never()).publish(any(StreakAchieved.class));
    }

    @Test
    void GivenNoStreakAtAll_WhenProgressIsRecorded_ThenNoMilestoneIsAnnounced() {
        // Zero is divisible by seven. Without the `streak > 0` guard, logging a missed day would
        // announce a milestone.
        recordWithStreak(0);

        verify(eventPublisher, never()).publish(any(StreakAchieved.class));
    }

    // ---- Reading progress ----

    @Test
    void GivenAnOwnedUpgrade_WhenItsProgressIsRead_ThenItComesBackNewestFirst() {
        List<ProgressEntry> newestFirst = List.of(
                AProgressEntry.completedOn(upgradeId, userId, today, true),
                AProgressEntry.completedOn(upgradeId, userId, today.minusDays(1), true));
        when(progressRepository.findByUpgradeIdOrderByDateDesc(upgradeId)).thenReturn(newestFirst);

        assertThat(service.getProgress(userId, upgradeId)).isEqualTo(newestFirst);
    }

    @Test
    void GivenAnUpgradeOwnedBySomebodyElse_WhenItsProgressIsRead_ThenItIsNotDisclosed() {
        when(upgradeQuery.getOwnedUpgrade(userId, upgradeId))
                .thenThrow(new ResourceNotFoundException("Upgrade not found: " + upgradeId));

        assertThatThrownBy(() -> service.getProgress(userId, upgradeId))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(progressRepository, never()).findByUpgradeIdOrderByDateDesc(any());
    }

    @Test
    void GivenAUser_WhenTodaysProgressIsRead_ThenTodayIsTakenFromTheInjectedClock() {
        when(progressRepository.findByUserIdAndDate(userId, today)).thenReturn(List.of());

        service.getTodayProgress(userId);

        verify(progressRepository).findByUserIdAndDate(userId, today);
    }

    @Test
    void GivenAUser_WhenTheWeeksProgressIsRead_ThenItSpansSevenDaysEndingToday() {
        // Six days back, today inclusive — seven days. An off-by-one here silently changes the
        // dashboard's weekly completion rate.
        service.getWeekProgress(userId);

        verify(progressRepository).findByUserIdAndDateBetween(userId, today.minusDays(6), today);
    }

    @Test
    void GivenAnOwnedUpgrade_WhenItsStreaksAreRead_ThenBothTheCurrentAndTheLongestAreReturned() {
        List<ProgressEntry> entries = List.of(AProgressEntry.completedOn(upgradeId, userId, today, true));
        when(progressRepository.findByUpgradeIdOrderByDateDesc(upgradeId)).thenReturn(entries);
        when(streakCalculator.calculateCurrentStreak(entries, today)).thenReturn(4);
        when(streakCalculator.calculateLongestStreak(entries)).thenReturn(11);

        StreakSummary summary = service.getStreakSummary(userId, upgradeId);

        assertThat(summary.current()).isEqualTo(4);
        assertThat(summary.longest()).isEqualTo(11);
    }

    @Test
    void GivenAnUpgradeOwnedBySomebodyElse_WhenItsStreaksAreRead_ThenTheyAreNotDisclosed() {
        when(upgradeQuery.getOwnedUpgrade(userId, upgradeId))
                .thenThrow(new ResourceNotFoundException("Upgrade not found: " + upgradeId));

        assertThatThrownBy(() -> service.getStreakSummary(userId, upgradeId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- Tracking configuration ----

    @Test
    void GivenAnUpgradeWithNoConfiguration_WhenOneIsSaved_ThenItIsCreatedAgainstThatUpgrade() {
        when(configRepository.findByUpgradeId(upgradeId)).thenReturn(Optional.empty());
        when(configRepository.save(any(TrackingConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        service.saveConfig(userId, upgradeId, new TrackingConfigDetails(
                TrackingType.NUMERIC, Frequency.DAILY, 10_000.0, "steps", true));

        ArgumentCaptor<TrackingConfig> saved = ArgumentCaptor.forClass(TrackingConfig.class);
        verify(configRepository).save(saved.capture());
        TrackingConfig config = saved.getValue();
        assertThat(config.getUpgradeId()).isEqualTo(upgradeId);
        assertThat(config.getTrackingType()).isEqualTo(TrackingType.NUMERIC);
        assertThat(config.getFrequency()).isEqualTo(Frequency.DAILY);
        assertThat(config.getTargetNumericValue()).isEqualTo(10_000.0);
        assertThat(config.getTargetUnit()).isEqualTo("steps");
        assertThat(config.getRequiredDaily()).isTrue();
    }

    @Test
    void GivenAnExistingConfiguration_WhenOneIsSaved_ThenTheSameRowIsReplacedRatherThanASecondCreated() {
        // One configuration per upgrade. Creating a second would violate the unique constraint on
        // upgrade_id and fail at flush time instead of here.
        TrackingConfig existing = ATrackingConfig.booleanTracking(upgradeId);
        when(configRepository.findByUpgradeId(upgradeId)).thenReturn(Optional.of(existing));
        when(configRepository.save(existing)).thenReturn(existing);

        service.saveConfig(userId, upgradeId, new TrackingConfigDetails(
                TrackingType.RATING, Frequency.WEEKLY, null, null, false));

        verify(configRepository).save(existing);
        assertThat(existing.getTrackingType()).isEqualTo(TrackingType.RATING);
        assertThat(existing.getFrequency()).isEqualTo(Frequency.WEEKLY);
    }

    @Test
    void GivenAConfigurationChanges_WhenItIsSaved_ThenAlreadyLoggedEntriesAreNotRescored() {
        // A past day was judged by the rule in force when it was logged. Rescoring would move streaks
        // the user has already been shown.
        TrackingConfig existing = ATrackingConfig.booleanTracking(upgradeId);
        when(configRepository.findByUpgradeId(upgradeId)).thenReturn(Optional.of(existing));
        when(configRepository.save(existing)).thenReturn(existing);

        service.saveConfig(userId, upgradeId, new TrackingConfigDetails(
                TrackingType.NUMERIC, Frequency.DAILY, 5.0, "km", false));

        verify(progressRepository, never()).save(any());
        verify(evaluationService, never()).isSuccessful(any(), any());
    }

    @Test
    void GivenAnUpgradeOwnedBySomebodyElse_WhenAConfigurationIsSaved_ThenNothingIsStored() {
        when(upgradeQuery.getOwnedUpgrade(userId, upgradeId))
                .thenThrow(new ResourceNotFoundException("Upgrade not found: " + upgradeId));

        assertThatThrownBy(() -> service.saveConfig(userId, upgradeId, new TrackingConfigDetails(
                TrackingType.BOOLEAN, Frequency.DAILY, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(configRepository, never()).save(any());
    }

    @Test
    void GivenAnUpgradeWithNoConfiguration_WhenItsConfigurationIsRead_ThenItIsReportedAsAbsent() {
        when(configRepository.findByUpgradeId(upgradeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getConfig(userId, upgradeId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(upgradeId.toString());
    }

    // ---- Inbound ports used by other contexts ----

    @Test
    void GivenSeveralUpgrades_WhenTheirConfigurationsAreResolved_ThenTheyAreFetchedInOneBatch() {
        // NFR-14. The upgrade list endpoint calls this once for every row it is about to render; a
        // per-id lookup here is the N+1 that made the list slow.
        List<UUID> ids = List.of(upgradeId, UUID.randomUUID(), UUID.randomUUID());
        when(configRepository.findByUpgradeIdIn(ids)).thenReturn(List.of());

        service.findByUpgradeIds(ids);

        verify(configRepository).findByUpgradeIdIn(ids);
        verify(configRepository, never()).findByUpgradeId(any());
    }

    @Test
    void GivenAnUpgrade_WhenTheDashboardAsksForItsStreak_ThenItIsMeasuredFromTheInjectedClock() {
        List<ProgressEntry> entries = List.of(AProgressEntry.completedOn(upgradeId, userId, today, true));
        when(progressRepository.findByUpgradeIdOrderByDateDesc(upgradeId)).thenReturn(entries);
        when(streakCalculator.calculateCurrentStreak(entries, today)).thenReturn(5);

        assertThat(service.currentStreak(upgradeId)).isEqualTo(5);
    }

    /** Records one entry against a stubbed streak length, which is what the BR-10 cases vary. */
    private void recordWithStreak(int streak) {
        when(progressRepository.existsByUpgradeIdAndDate(any(), any())).thenReturn(false);
        when(configRepository.findByUpgradeId(upgradeId)).thenReturn(Optional.empty());
        when(progressRepository.save(any(ProgressEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(progressRepository.findByUpgradeIdOrderByDateDesc(upgradeId)).thenReturn(List.of());
        when(streakCalculator.calculateCurrentStreak(any(), any())).thenReturn(streak);

        service.recordProgress(userId, upgradeId,
                new ProgressEntryDetails(today, true, null, null, null, null));
    }
}
