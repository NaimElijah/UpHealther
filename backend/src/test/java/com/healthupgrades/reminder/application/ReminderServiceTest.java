package com.healthupgrades.reminder.application;

import com.healthupgrades.common.domain.exception.BusinessRuleException;
import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.reminder.application.port.in.ReminderSchedule;
import com.healthupgrades.reminder.domain.model.Reminder;
import com.healthupgrades.reminder.domain.model.ReminderDays;
import com.healthupgrades.reminder.domain.port.out.ReminderRepositoryPort;
import com.healthupgrades.support.AnUpgrade;
import com.healthupgrades.upgrade.application.port.in.UpgradeQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import com.healthupgrades.support.RecordingAuditTrail;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
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
 * Covers FR-25 (attach a reminder with a time and a day filter) and FR-26 (reschedule, enable, disable,
 * delete).
 *
 * <p>The ownership story here is the one worth pinning: a reminder has no owner column. It is the
 * caller's only because the upgrade it hangs off is, so every path resolves that upgrade through
 * {@link UpgradeQuery} first. A reminder id belonging to another user's upgrade must come back as absent
 * (BR-15) rather than being changed — and it is a two-step lookup, so the guard is easy to drop.
 */
@ExtendWith(MockitoExtension.class)
class ReminderServiceTest {

    private static final LocalTime NINE_AM = LocalTime.of(9, 0);
    private static final LocalTime SIX_PM = LocalTime.of(18, 0);

    private final UUID userId = UUID.randomUUID();
    private final UUID upgradeId = UUID.randomUUID();
    private final UUID reminderId = UUID.randomUUID();

    @Mock ReminderRepositoryPort repository;
    @Mock UpgradeQuery upgradeQuery;
    /** A real implementation, not a mock: AuditTrail.recording is a default method. */
    @Spy RecordingAuditTrail auditTrail = new RecordingAuditTrail();

    @InjectMocks ReminderService service;

    @Test
    void GivenAnOwnedUpgrade_WhenAReminderIsAttached_ThenItIsStoredWithItsTimeAndDays() {
        when(upgradeQuery.getOwnedUpgrade(userId, upgradeId)).thenReturn(AnUpgrade.active(userId));
        when(repository.save(any(Reminder.class))).thenAnswer(call -> call.getArgument(0));

        service.create(userId, upgradeId, new ReminderSchedule(NINE_AM, List.of("MON", "WED"), true));

        Reminder saved = capturedSave();
        assertThat(saved.getUpgradeId()).isEqualTo(upgradeId);
        assertThat(saved.getReminderTime()).isEqualTo(NINE_AM);
        assertThat(saved.days().toTokens()).containsExactly("MON", "WED");
        assertThat(saved.isEnabled()).isTrue();
    }

    @Test
    void GivenNoEnabledFlag_WhenAReminderIsAttached_ThenItIsCreatedEnabled() {
        // A reminder nobody asked to switch off should fire; created disabled it would look broken.
        when(upgradeQuery.getOwnedUpgrade(userId, upgradeId)).thenReturn(AnUpgrade.active(userId));
        when(repository.save(any(Reminder.class))).thenAnswer(call -> call.getArgument(0));

        service.create(userId, upgradeId, new ReminderSchedule(NINE_AM, List.of("MON"), null));

        assertThat(capturedSave().isEnabled()).isTrue();
    }

    @Test
    void GivenNoDayFilter_WhenAReminderIsAttached_ThenItIsStoredAsFiringEveryDay() {
        when(upgradeQuery.getOwnedUpgrade(userId, upgradeId)).thenReturn(AnUpgrade.active(userId));
        when(repository.save(any(Reminder.class))).thenAnswer(call -> call.getArgument(0));

        service.create(userId, upgradeId, new ReminderSchedule(NINE_AM, null, true));

        ReminderDays days = capturedSave().days();
        assertThat(java.time.DayOfWeek.values()).allMatch(days::includes);
    }

    @Test
    void GivenAnUnrecognisableDay_WhenAReminderIsAttached_ThenItIsRefusedRatherThanStored() {
        // BR-12. Silently dropping the token would leave a reminder firing on days the user never chose.
        when(upgradeQuery.getOwnedUpgrade(userId, upgradeId)).thenReturn(AnUpgrade.active(userId));

        assertThatThrownBy(() -> service.create(userId, upgradeId,
                new ReminderSchedule(NINE_AM, List.of("MON", "FUNDAY"), true)))
                .isInstanceOf(BusinessRuleException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void GivenAnUpgradeOwnedBySomebodyElse_WhenAReminderIsAttached_ThenNothingIsStored() {
        when(upgradeQuery.getOwnedUpgrade(userId, upgradeId))
                .thenThrow(new ResourceNotFoundException("Upgrade not found: " + upgradeId));

        assertThatThrownBy(() -> service.create(userId, upgradeId,
                new ReminderSchedule(NINE_AM, List.of("MON"), true)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void GivenAnOwnedUpgrade_WhenItsRemindersAreRead_ThenBothEnabledAndDisabledOnesComeBack() {
        List<Reminder> reminders = List.of(
                Reminder.create(upgradeId, NINE_AM, ReminderDays.of(List.of("MON")), true),
                Reminder.create(upgradeId, SIX_PM, ReminderDays.of(List.of("TUE")), false));
        when(upgradeQuery.getOwnedUpgrade(userId, upgradeId)).thenReturn(AnUpgrade.active(userId));
        when(repository.findByUpgradeId(upgradeId)).thenReturn(reminders);

        assertThat(service.getForUpgrade(userId, upgradeId)).isEqualTo(reminders);
    }

    @Test
    void GivenAnOwnedReminder_WhenItIsRescheduled_ThenItsTimeAndDaysAreReplaced() {
        Reminder reminder = aReminder(true);
        when(repository.findById(reminderId)).thenReturn(Optional.of(reminder));
        when(upgradeQuery.getOwnedUpgrade(userId, upgradeId)).thenReturn(AnUpgrade.active(userId));
        when(repository.save(reminder)).thenReturn(reminder);

        service.update(userId, reminderId, new ReminderSchedule(SIX_PM, List.of("SAT", "SUN"), null));

        assertThat(reminder.getReminderTime()).isEqualTo(SIX_PM);
        assertThat(reminder.days().toTokens()).containsExactly("SAT", "SUN");
    }

    @Test
    void GivenNoEnabledFlagOnTheUpdate_WhenAReminderIsRescheduled_ThenItsCurrentStateIsLeftAlone() {
        // Rescheduling a switched-off reminder must not switch it back on behind the user's back.
        Reminder reminder = aReminder(false);
        when(repository.findById(reminderId)).thenReturn(Optional.of(reminder));
        when(upgradeQuery.getOwnedUpgrade(userId, upgradeId)).thenReturn(AnUpgrade.active(userId));
        when(repository.save(reminder)).thenReturn(reminder);

        service.update(userId, reminderId, new ReminderSchedule(SIX_PM, List.of("SAT"), null));

        assertThat(reminder.isEnabled()).isFalse();
    }

    @Test
    void GivenADisabledReminder_WhenTheUpdateEnablesIt_ThenItIsSwitchedOn() {
        Reminder reminder = aReminder(false);
        when(repository.findById(reminderId)).thenReturn(Optional.of(reminder));
        when(upgradeQuery.getOwnedUpgrade(userId, upgradeId)).thenReturn(AnUpgrade.active(userId));
        when(repository.save(reminder)).thenReturn(reminder);

        service.update(userId, reminderId, new ReminderSchedule(NINE_AM, List.of("MON"), true));

        assertThat(reminder.isEnabled()).isTrue();
    }

    @Test
    void GivenAnEnabledReminder_WhenTheUpdateDisablesIt_ThenItIsSwitchedOff() {
        Reminder reminder = aReminder(true);
        when(repository.findById(reminderId)).thenReturn(Optional.of(reminder));
        when(upgradeQuery.getOwnedUpgrade(userId, upgradeId)).thenReturn(AnUpgrade.active(userId));
        when(repository.save(reminder)).thenReturn(reminder);

        service.update(userId, reminderId, new ReminderSchedule(NINE_AM, List.of("MON"), false));

        assertThat(reminder.isEnabled()).isFalse();
    }

    @Test
    void GivenAReminderOnAnotherUsersUpgrade_WhenItIsRescheduled_ThenItIsReportedAsAbsentAndNotChanged() {
        // The reminder itself is found by id alone — the guard is the upgrade lookup that follows it.
        Reminder reminder = aReminder(true);
        when(repository.findById(reminderId)).thenReturn(Optional.of(reminder));
        when(upgradeQuery.getOwnedUpgrade(userId, upgradeId))
                .thenThrow(new ResourceNotFoundException("Upgrade not found: " + upgradeId));

        assertThatThrownBy(() -> service.update(userId, reminderId,
                new ReminderSchedule(SIX_PM, List.of("SAT"), true)))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(reminder.getReminderTime()).isEqualTo(NINE_AM);
        verify(repository, never()).save(any());
    }

    @Test
    void GivenAnUnknownReminderId_WhenItIsRescheduled_ThenItIsReportedAsAbsent() {
        when(repository.findById(reminderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(userId, reminderId,
                new ReminderSchedule(SIX_PM, List.of("SAT"), true)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void GivenAnOwnedReminder_WhenItIsDeleted_ThenItIsRemoved() {
        Reminder reminder = aReminder(true);
        when(repository.findById(reminderId)).thenReturn(Optional.of(reminder));
        when(upgradeQuery.getOwnedUpgrade(userId, upgradeId)).thenReturn(AnUpgrade.active(userId));

        service.delete(userId, reminderId);

        verify(repository).delete(reminder);
    }

    @Test
    void GivenAReminderOnAnotherUsersUpgrade_WhenItIsDeleted_ThenNothingIsRemoved() {
        when(repository.findById(reminderId)).thenReturn(Optional.of(aReminder(true)));
        when(upgradeQuery.getOwnedUpgrade(userId, upgradeId))
                .thenThrow(new ResourceNotFoundException("Upgrade not found: " + upgradeId));

        assertThatThrownBy(() -> service.delete(userId, reminderId))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(repository, never()).delete(any());
    }

    @Test
    void GivenRemindersAcrossUsers_WhenTheDispatcherAsksForWork_ThenOnlyTheEnabledOnesAreReturned() {
        // The one method that is deliberately not user-scoped: the scheduler sweeps every user's
        // reminders, and filters by enabled rather than by owner.
        List<Reminder> enabled = List.of(aReminder(true));
        when(repository.findByEnabledTrue()).thenReturn(enabled);

        assertThat(service.findEnabled()).isEqualTo(enabled);
    }

    private Reminder aReminder(boolean enabled) {
        return Reminder.create(upgradeId, NINE_AM, ReminderDays.of(List.of("MON")), enabled);
    }

    private Reminder capturedSave() {
        ArgumentCaptor<Reminder> saved = ArgumentCaptor.forClass(Reminder.class);
        verify(repository).save(saved.capture());
        return saved.getValue();
    }
}
