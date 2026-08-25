package com.healthupgrades.reminder.domain.model;

import com.healthupgrades.common.domain.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the reminder aggregate's due-ness rule: the day filter, the time match, and the enabled flag.
 *
 * <p>These conditions used to be evaluated by the notification scheduler against a raw CSV column, so
 * this test is also what keeps that logic from drifting back out of the aggregate.
 */
class ReminderTest {

    private static final LocalTime NINE_AM = LocalTime.of(9, 0);

    private Reminder reminderAt(LocalTime time, List<String> days, boolean enabled) {
        return Reminder.create(UUID.randomUUID(), time, ReminderDays.of(days), enabled);
    }

    // ---- ReminderDays ----

    @Test
    void GivenNoDayFilter_WhenTheDaysAreRead_ThenTheyMeanEveryDay() {
        ReminderDays days = ReminderDays.of(null);
        assertThat(days.includes(DayOfWeek.MONDAY)).isTrue();
        assertThat(days.includes(DayOfWeek.SUNDAY)).isTrue();
        assertThat(days.toStorageValue()).isNull();
    }

    @Test
    void GivenAnEmptyDayList_WhenTheDaysAreRead_ThenTheyMeanEveryDay() {
        assertThat(ReminderDays.of(List.of()).includes(DayOfWeek.WEDNESDAY)).isTrue();
    }

    @Test
    void GivenANamedSubsetOfDays_WhenTheDaysAreRead_ThenOnlyThoseAreIncluded() {
        ReminderDays days = ReminderDays.of(List.of("MON", "WED", "FRI"));
        assertThat(days.includes(DayOfWeek.MONDAY)).isTrue();
        assertThat(days.includes(DayOfWeek.WEDNESDAY)).isTrue();
        assertThat(days.includes(DayOfWeek.TUESDAY)).isFalse();
    }

    @Test
    void GivenASetOfDays_WhenItRoundTripsThroughStorage_ThenTheSetIsPreserved() {
        ReminderDays original = ReminderDays.of(List.of("TUE", "SAT"));
        ReminderDays reloaded = ReminderDays.fromStorageValue(original.toStorageValue());
        assertThat(reloaded.includes(DayOfWeek.TUESDAY)).isTrue();
        assertThat(reloaded.includes(DayOfWeek.SATURDAY)).isTrue();
        assertThat(reloaded.includes(DayOfWeek.MONDAY)).isFalse();
    }

    @Test
    void GivenDaysInAnyInputOrder_WhenTheyAreListed_ThenTheyAreInCalendarOrder() {
        assertThat(ReminderDays.of(List.of("fri", "MON", "Wed")).toTokens())
                .containsExactly("MON", "WED", "FRI");
    }

    @Test
    void GivenFullDayNamesInOddCasing_WhenTheyAreParsed_ThenTheyAreAccepted() {
        // The boundary used to store whatever it was handed, so a full day name was silently never matched.
        ReminderDays days = ReminderDays.of(List.of(" monday ", "Tuesday"));
        assertThat(days.includes(DayOfWeek.MONDAY)).isTrue();
        assertThat(days.includes(DayOfWeek.TUESDAY)).isTrue();
        assertThat(days.toTokens()).containsExactly("MON", "TUE");
    }

    @Test
    void GivenBlankEntriesAmongTheDays_WhenTheyAreParsed_ThenTheyAreIgnored() {
        ReminderDays days = ReminderDays.of(List.of("MON", "", "  "));
        assertThat(days.toTokens()).containsExactly("MON");
    }

    @Test
    void GivenAnUnrecognisedDayToken_WhenItIsParsed_ThenItIsRejected() {
        assertThatThrownBy(() -> ReminderDays.of(List.of("MON", "NOTADAY")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("NOTADAY");
    }

    @Test
    void GivenEveryDayTokenIsUnrecognised_WhenTheyAreParsed_ThenTheyAreRejectedRatherThanBecomingEveryDay() {
        // Skipping unparseable tokens would leave an empty set, and an empty set means every day — so a
        // typo would turn a twice-weekly reminder into a daily one. Failing is the safer answer.
        assertThatThrownBy(() -> ReminderDays.of(List.of("Mondays", "Fridays")))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void GivenPersistedDayTokensThatCannotBeRead_WhenTheyAreLoaded_ThenTheyAreDroppedRatherThanRejected() {
        // Rows may predate this type. Refusing to load them would make the reminder unfetchable, which
        // is worse than loading it with the days that are still readable.
        ReminderDays days = ReminderDays.fromStorageValue("MON,GARBAGE,FRI");
        assertThat(days.toTokens()).containsExactly("MON", "FRI");
    }

    @Test
    void GivenTwoDaySetsWithTheSameDays_WhenTheyAreCompared_ThenTheyAreEqual() {
        assertThat(ReminderDays.of(List.of("MON", "WED")))
                .isEqualTo(ReminderDays.of(List.of("wednesday", "mon")))
                .hasSameHashCodeAs(ReminderDays.of(List.of("WED", "MON")));
    }

    // ---- isDueAt ----

    @Test
    void GivenAReminderOnThisDayAtThisTime_WhenDueIsChecked_ThenItIsDue() {
        Reminder reminder = reminderAt(NINE_AM, List.of("MON"), true);
        assertThat(reminder.isDueAt(DayOfWeek.MONDAY, NINE_AM)).isTrue();
    }

    @Test
    void GivenAReminderAtThisTimeOnAnotherDay_WhenDueIsChecked_ThenItIsNotDue() {
        Reminder reminder = reminderAt(NINE_AM, List.of("MON"), true);
        assertThat(reminder.isDueAt(DayOfWeek.TUESDAY, NINE_AM)).isFalse();
    }

    @Test
    void GivenAReminderOnThisDayAtAnotherTime_WhenDueIsChecked_ThenItIsNotDue() {
        Reminder reminder = reminderAt(NINE_AM, List.of("MON"), true);
        assertThat(reminder.isDueAt(DayOfWeek.MONDAY, LocalTime.of(9, 1))).isFalse();
    }

    @Test
    void GivenAReminderDueThisMinute_WhenDueIsCheckedPartWayThroughIt_ThenTheSecondsAreIgnored() {
        // The dispatch job runs once a minute, so matching to the minute is the intended granularity.
        Reminder reminder = reminderAt(NINE_AM, List.of("MON"), true);
        assertThat(reminder.isDueAt(DayOfWeek.MONDAY, LocalTime.of(9, 0, 45))).isTrue();
    }

    @Test
    void GivenAReminderWithNoDayFilter_WhenDueIsChecked_ThenItIsDueOnAnyDay() {
        Reminder reminder = reminderAt(NINE_AM, null, true);
        assertThat(reminder.isDueAt(DayOfWeek.SUNDAY, NINE_AM)).isTrue();
    }

    @Test
    void GivenADisabledReminder_WhenDueIsChecked_ThenItIsNeverDue() {
        Reminder reminder = reminderAt(NINE_AM, List.of("MON"), false);
        assertThat(reminder.isDueAt(DayOfWeek.MONDAY, NINE_AM)).isFalse();
    }

    @Test
    void GivenAReminderWithNoTimeSet_WhenDueIsChecked_ThenItIsNeverDue() {
        Reminder reminder = reminderAt(null, List.of("MON"), true);
        assertThat(reminder.isDueAt(DayOfWeek.MONDAY, NINE_AM)).isFalse();
    }

    // ---- rescheduling ----

    @Test
    void GivenAReminder_WhenItIsRescheduled_ThenItsTimeAndDaysAreReplaced() {
        Reminder reminder = reminderAt(NINE_AM, List.of("MON"), true);

        reminder.reschedule(LocalTime.of(18, 30), ReminderDays.of(List.of("SAT", "SUN")));

        assertThat(reminder.isDueAt(DayOfWeek.SATURDAY, LocalTime.of(18, 30))).isTrue();
        assertThat(reminder.isDueAt(DayOfWeek.MONDAY, NINE_AM)).isFalse();
    }

    @Test
    void GivenAReminder_WhenItIsEnabledOrDisabled_ThenItsScheduleIsUntouched() {
        Reminder reminder = reminderAt(NINE_AM, List.of("MON"), true);

        reminder.changeEnabled(false);
        assertThat(reminder.isDueAt(DayOfWeek.MONDAY, NINE_AM)).isFalse();

        reminder.changeEnabled(true);
        assertThat(reminder.isDueAt(DayOfWeek.MONDAY, NINE_AM)).isTrue();
    }
}
