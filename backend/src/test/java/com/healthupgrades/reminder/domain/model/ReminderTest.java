package com.healthupgrades.reminder.domain.model;

import com.healthupgrades.common.domain.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReminderTest {

    private static final LocalTime NINE_AM = LocalTime.of(9, 0);

    private Reminder reminderAt(LocalTime time, List<String> days, boolean enabled) {
        return Reminder.create(UUID.randomUUID(), time, ReminderDays.of(days), enabled);
    }

    // ---- ReminderDays ----

    @Test
    void days_noFilter_meansEveryDay() {
        ReminderDays days = ReminderDays.of(null);
        assertThat(days.includes(DayOfWeek.MONDAY)).isTrue();
        assertThat(days.includes(DayOfWeek.SUNDAY)).isTrue();
        assertThat(days.toStorageValue()).isNull();
    }

    @Test
    void days_emptyList_meansEveryDay() {
        assertThat(ReminderDays.of(List.of()).includes(DayOfWeek.WEDNESDAY)).isTrue();
    }

    @Test
    void days_namedSubset_includesOnlyThoseDays() {
        ReminderDays days = ReminderDays.of(List.of("MON", "WED", "FRI"));
        assertThat(days.includes(DayOfWeek.MONDAY)).isTrue();
        assertThat(days.includes(DayOfWeek.WEDNESDAY)).isTrue();
        assertThat(days.includes(DayOfWeek.TUESDAY)).isFalse();
    }

    @Test
    void days_roundTripThroughStorage_preservesTheSet() {
        ReminderDays original = ReminderDays.of(List.of("TUE", "SAT"));
        ReminderDays reloaded = ReminderDays.fromStorageValue(original.toStorageValue());
        assertThat(reloaded.includes(DayOfWeek.TUESDAY)).isTrue();
        assertThat(reloaded.includes(DayOfWeek.SATURDAY)).isTrue();
        assertThat(reloaded.includes(DayOfWeek.MONDAY)).isFalse();
    }

    @Test
    void days_areListedInCalendarOrderRegardlessOfInputOrder() {
        assertThat(ReminderDays.of(List.of("fri", "MON", "Wed")).toTokens())
                .containsExactly("MON", "WED", "FRI");
    }

    @Test
    void days_acceptFullNamesAndOddCasing() {
        // The boundary used to store whatever it was handed, so a full day name was silently never matched.
        ReminderDays days = ReminderDays.of(List.of(" monday ", "Tuesday"));
        assertThat(days.includes(DayOfWeek.MONDAY)).isTrue();
        assertThat(days.includes(DayOfWeek.TUESDAY)).isTrue();
        assertThat(days.toTokens()).containsExactly("MON", "TUE");
    }

    @Test
    void days_ignoreBlankEntries() {
        ReminderDays days = ReminderDays.of(List.of("MON", "", "  "));
        assertThat(days.toTokens()).containsExactly("MON");
    }

    @Test
    void days_rejectAnUnrecognisedToken() {
        assertThatThrownBy(() -> ReminderDays.of(List.of("MON", "NOTADAY")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("NOTADAY");
    }

    @Test
    void days_allTokensUnrecognised_mustNotSilentlyBecomeEveryDay() {
        // Skipping unparseable tokens would leave an empty set, and an empty set means every day — so a
        // typo would turn a twice-weekly reminder into a daily one. Failing is the safer answer.
        assertThatThrownBy(() -> ReminderDays.of(List.of("Mondays", "Fridays")))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void days_persistedTokensThatCannotBeReadAreDroppedRatherThanRejected() {
        // Rows may predate this type. Refusing to load them would make the reminder unfetchable, which
        // is worse than loading it with the days that are still readable.
        ReminderDays days = ReminderDays.fromStorageValue("MON,GARBAGE,FRI");
        assertThat(days.toTokens()).containsExactly("MON", "FRI");
    }

    @Test
    void days_withTheSameDaysAreEqual() {
        assertThat(ReminderDays.of(List.of("MON", "WED")))
                .isEqualTo(ReminderDays.of(List.of("wednesday", "mon")))
                .hasSameHashCodeAs(ReminderDays.of(List.of("WED", "MON")));
    }

    // ---- isDueAt ----

    @Test
    void isDueAt_matchingDayAndTime_isDue() {
        Reminder reminder = reminderAt(NINE_AM, List.of("MON"), true);
        assertThat(reminder.isDueAt(DayOfWeek.MONDAY, NINE_AM)).isTrue();
    }

    @Test
    void isDueAt_matchingTimeOnDifferentDay_isNotDue() {
        Reminder reminder = reminderAt(NINE_AM, List.of("MON"), true);
        assertThat(reminder.isDueAt(DayOfWeek.TUESDAY, NINE_AM)).isFalse();
    }

    @Test
    void isDueAt_matchingDayAtAnotherTime_isNotDue() {
        Reminder reminder = reminderAt(NINE_AM, List.of("MON"), true);
        assertThat(reminder.isDueAt(DayOfWeek.MONDAY, LocalTime.of(9, 1))).isFalse();
    }

    @Test
    void isDueAt_ignoresSeconds() {
        // The dispatch job runs once a minute, so matching to the minute is the intended granularity.
        Reminder reminder = reminderAt(NINE_AM, List.of("MON"), true);
        assertThat(reminder.isDueAt(DayOfWeek.MONDAY, LocalTime.of(9, 0, 45))).isTrue();
    }

    @Test
    void isDueAt_withNoDayFilter_isDueOnAnyDay() {
        Reminder reminder = reminderAt(NINE_AM, null, true);
        assertThat(reminder.isDueAt(DayOfWeek.SUNDAY, NINE_AM)).isTrue();
    }

    @Test
    void isDueAt_whenDisabled_isNeverDue() {
        Reminder reminder = reminderAt(NINE_AM, List.of("MON"), false);
        assertThat(reminder.isDueAt(DayOfWeek.MONDAY, NINE_AM)).isFalse();
    }

    @Test
    void isDueAt_withNoTimeSet_isNeverDue() {
        Reminder reminder = reminderAt(null, List.of("MON"), true);
        assertThat(reminder.isDueAt(DayOfWeek.MONDAY, NINE_AM)).isFalse();
    }

    // ---- rescheduling ----

    @Test
    void reschedule_replacesTimeAndDays() {
        Reminder reminder = reminderAt(NINE_AM, List.of("MON"), true);

        reminder.reschedule(LocalTime.of(18, 30), ReminderDays.of(List.of("SAT", "SUN")));

        assertThat(reminder.isDueAt(DayOfWeek.SATURDAY, LocalTime.of(18, 30))).isTrue();
        assertThat(reminder.isDueAt(DayOfWeek.MONDAY, NINE_AM)).isFalse();
    }

    @Test
    void changeEnabled_togglesWithoutTouchingTheSchedule() {
        Reminder reminder = reminderAt(NINE_AM, List.of("MON"), true);

        reminder.changeEnabled(false);
        assertThat(reminder.isDueAt(DayOfWeek.MONDAY, NINE_AM)).isFalse();

        reminder.changeEnabled(true);
        assertThat(reminder.isDueAt(DayOfWeek.MONDAY, NINE_AM)).isTrue();
    }
}
