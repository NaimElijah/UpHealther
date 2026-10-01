package com.healthupgrades.tracking.domain.service;
import com.healthupgrades.tracking.domain.model.ProgressEntry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers streak counting, boundaries included: an unlogged today must not break the run, gaps must end
 * it, and duplicate or unsuccessful entries must not extend it. Also BR-10: which milestone, if any, a
 * newly logged entry reaches.
 */
class StreakCalculatorTest {

    /** A fixed reference day: the calculator is told what "today" is, so nothing here reads the clock. */
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

    private StreakCalculator calculator;
    private final UUID upgradeId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        calculator = new StreakCalculator();
    }

    private ProgressEntry entry(LocalDate date, boolean completed) {
        return ProgressEntry.builder()
                .id(UUID.randomUUID())
                .upgradeId(upgradeId)
                .userId(userId)
                .date(date)
                .completed(completed)
                .build();
    }

    @Test
    void GivenNoEntries_WhenTheCurrentStreakIsCalculated_ThenItIsZero() {
        assertThat(calculator.calculateCurrentStreak(Collections.emptyList(), TODAY)).isZero();
    }

    @Test
    void GivenNoCompletedEntries_WhenTheCurrentStreakIsCalculated_ThenItIsZero() {
        List<ProgressEntry> entries = List.of(
                entry(TODAY.minusDays(1), false),
                entry(TODAY, false)
        );
        assertThat(calculator.calculateCurrentStreak(entries, TODAY)).isZero();
    }

    @Test
    void GivenConsecutiveCompletedDaysIncludingToday_WhenTheCurrentStreakIsCalculated_ThenItCountsThemAll() {
        List<ProgressEntry> entries = List.of(
                entry(TODAY.minusDays(2), true),
                entry(TODAY.minusDays(1), true),
                entry(TODAY, true)
        );
        assertThat(calculator.calculateCurrentStreak(entries, TODAY)).isEqualTo(3);
    }

    @Test
    void GivenConsecutiveCompletedDaysEndingYesterday_WhenTheCurrentStreakIsCalculated_ThenTodayBeingUnloggedDoesNotBreakIt() {
        List<ProgressEntry> entries = List.of(
                entry(TODAY.minusDays(3), true),
                entry(TODAY.minusDays(2), true),
                entry(TODAY.minusDays(1), true)
        );
        assertThat(calculator.calculateCurrentStreak(entries, TODAY)).isEqualTo(3);
    }

    @Test
    void GivenAStreakBrokenByAMissedDay_WhenTheCurrentStreakIsCalculated_ThenOnlyTheRunSinceTheBreakCounts() {
        List<ProgressEntry> entries = List.of(
                entry(TODAY.minusDays(5), true),
                entry(TODAY.minusDays(4), true),
                entry(TODAY.minusDays(1), true),
                entry(TODAY, true)
        );
        assertThat(calculator.calculateCurrentStreak(entries, TODAY)).isEqualTo(2);
    }

    @Test
    void GivenAnExplicitDay_WhenTheCurrentStreakIsCalculated_ThenItIsMeasuredFromThatDayNotTheSystemClock() {
        // The same entries yield a different answer for a different reference day, which they could not
        // do if the calculator consulted a clock of its own. Both reference days are fixed, so this
        // holds whatever date the suite runs on.
        List<ProgressEntry> entries = List.of(
                entry(TODAY.minusDays(2), true),
                entry(TODAY.minusDays(1), true)
        );
        assertThat(calculator.calculateCurrentStreak(entries, TODAY)).isEqualTo(2);
        assertThat(calculator.calculateCurrentStreak(entries, TODAY.plusYears(1))).isZero();
    }

    @Test
    void GivenNoEntries_WhenTheLongestStreakIsCalculated_ThenItIsZero() {
        assertThat(calculator.calculateLongestStreak(Collections.emptyList())).isZero();
    }

    @Test
    void GivenASingleCompletedEntry_WhenTheLongestStreakIsCalculated_ThenItIsOne() {
        List<ProgressEntry> entries = List.of(entry(TODAY, true));
        assertThat(calculator.calculateLongestStreak(entries)).isEqualTo(1);
    }

    @Test
    void GivenSeveralSeparatedStreaks_WhenTheLongestIsCalculated_ThenTheLongestOneIsReturned() {
        List<ProgressEntry> entries = List.of(
                entry(TODAY.minusDays(10), true),
                entry(TODAY.minusDays(9), true),
                entry(TODAY.minusDays(8), true),
                entry(TODAY.minusDays(5), true),
                entry(TODAY.minusDays(4), true)
        );
        assertThat(calculator.calculateLongestStreak(entries)).isEqualTo(3);
    }

    @Test
    void GivenIncompleteEntriesAmongTheCompletedOnes_WhenTheLongestStreakIsCalculated_ThenTheIncompleteOnesAreIgnored() {
        List<ProgressEntry> entries = List.of(
                entry(TODAY.minusDays(3), true),
                entry(TODAY.minusDays(2), false),
                entry(TODAY.minusDays(1), true)
        );
        assertThat(calculator.calculateLongestStreak(entries)).isEqualTo(1);
    }

    // ---- BR-10: the milestone an entry reaches ----

    @ParameterizedTest
    @ValueSource(ints = {7, 14, 21, 70})
    void GivenARunOneDayShortOfAMultipleOfSeven_WhenTodayCompletesIt_ThenThatMilestoneIsReached(int streak) {
        List<ProgressEntry> entries = completedRunEndingOn(TODAY, streak);

        assertThat(calculator.milestoneReachedBy(entries, TODAY, TODAY)).hasValue(streak);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 6, 8, 13, 69})
    void GivenARunThatIsNotAMultipleOfSeven_WhenTodayExtendsIt_ThenNoMilestoneIsReached(int streak) {
        // Announcing every consecutive day would put a notification in the list once a day per tracked
        // upgrade, which is what makes the milestone worth nothing.
        List<ProgressEntry> entries = completedRunEndingOn(TODAY, streak);

        assertThat(calculator.milestoneReachedBy(entries, TODAY, TODAY)).isEmpty();
    }

    @Test
    void GivenNoStreakAtAll_WhenAnUnsuccessfulDayIsLogged_ThenNoMilestoneIsReached() {
        // Zero is divisible by seven, so a rule that only asked "is the streak a multiple of seven?"
        // would announce a milestone for logging a missed day.
        List<ProgressEntry> entries = List.of(entry(TODAY, false));

        assertThat(calculator.milestoneReachedBy(entries, TODAY, TODAY)).isEmpty();
    }

    @Test
    void GivenAStreakOfSevenEndingYesterday_WhenTodayIsLoggedAsNotCompleted_ThenTheMilestoneIsNotReachedAgain() {
        // #101. An unsuccessful today does not break the run ending yesterday, so the streak is still 7
        // after this entry — but this entry did not take it there.
        List<ProgressEntry> entries = new ArrayList<>(completedRunEndingOn(TODAY.minusDays(1), 7));
        entries.add(entry(TODAY, false));

        assertThat(calculator.milestoneReachedBy(entries, TODAY, TODAY)).isEmpty();
    }

    @Test
    void GivenAStreakOfSeven_WhenADayLongBeforeTheRunIsBackfilled_ThenTheMilestoneIsNotReachedAgain() {
        // #101. A backfilled day outside the current run leaves the streak where it was.
        LocalDate longBefore = TODAY.minusDays(30);
        List<ProgressEntry> entries = new ArrayList<>(completedRunEndingOn(TODAY, 7));
        entries.add(entry(longBefore, true));

        assertThat(calculator.milestoneReachedBy(entries, longBefore, TODAY)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 9, 13})
    void GivenARunThatHasPassedSeven_WhenYesterdayIsLoggedBeforeToday_ThenTheSevenDayMilestoneIsNotReachedAgain(int streak) {
        // The catch-up morning. With today unlogged, the current streak is counted from yesterday — the
        // very day being logged — so the streak without this entry reads as zero. The run ending the day
        // before it had already reached 7.
        LocalDate yesterday = TODAY.minusDays(1);
        List<ProgressEntry> entries = completedRunEndingOn(yesterday, streak);

        assertThat(calculator.milestoneReachedBy(entries, yesterday, TODAY)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {7, 14})
    void GivenARunOneDayShortOfAMultipleOfSeven_WhenYesterdayIsLoggedBeforeTodayAndCompletesIt_ThenThatMilestoneIsReached(int streak) {
        LocalDate yesterday = TODAY.minusDays(1);
        List<ProgressEntry> entries = completedRunEndingOn(yesterday, streak);

        assertThat(calculator.milestoneReachedBy(entries, yesterday, TODAY)).hasValue(streak);
    }

    @Test
    void GivenAnAnnouncedRunThenAMissedDayThenToday_WhenTheMissedDayIsBackfilled_ThenTheMilestoneIsNotReachedAgain() {
        // Seven days ending the day before yesterday reached 7 when the seventh was logged. Filling in
        // the missed day joins that run to today's, making 9 — past 7, but not past anything new.
        LocalDate missed = TODAY.minusDays(1);
        List<ProgressEntry> entries = new ArrayList<>(completedRunEndingOn(missed.minusDays(1), 7));
        entries.add(entry(TODAY, true));
        entries.add(entry(missed, true));

        assertThat(calculator.milestoneReachedBy(entries, missed, TODAY)).isEmpty();
    }

    @Test
    void GivenTwoRunsOneDayApart_WhenTheGapIsBackfilledAndJoinsThemIntoSeven_ThenTheSevenDayMilestoneIsReached() {
        LocalDate gap = TODAY.minusDays(3);
        List<ProgressEntry> entries = new ArrayList<>(completedRunEndingOn(TODAY, 3));
        entries.addAll(completedRunEndingOn(gap.minusDays(1), 3));
        entries.add(entry(gap, true));

        assertThat(calculator.milestoneReachedBy(entries, gap, TODAY)).hasValue(7);
    }

    @Test
    void GivenABackfillThatJoinsTwoRunsPastAMultipleOfSeven_WhenItIsLogged_ThenTheHighestMilestoneCrossedIsReached() {
        // 3 days, then the gap, then 11 more: the streak goes from 3 to 15, landing on no multiple of
        // seven but passing both 7 and 14.
        LocalDate gap = TODAY.minusDays(3);
        List<ProgressEntry> entries = new ArrayList<>(completedRunEndingOn(TODAY, 3));
        entries.addAll(completedRunEndingOn(gap.minusDays(1), 11));
        entries.add(entry(gap, true));

        assertThat(calculator.milestoneReachedBy(entries, gap, TODAY)).hasValue(14);
    }

    @Test
    void GivenAStreakOfSix_WhenADayAfterTodayIsLogged_ThenNoMilestoneIsReached() {
        // The current streak is counted back from today, so a future-dated entry is not part of it.
        LocalDate tomorrow = TODAY.plusDays(1);
        List<ProgressEntry> entries = new ArrayList<>(completedRunEndingOn(TODAY, 6));
        entries.add(entry(tomorrow, true));

        assertThat(calculator.milestoneReachedBy(entries, tomorrow, TODAY)).isEmpty();
    }

    /** {@code days} completed entries, one per day, the last of them on {@code lastDay}. */
    private List<ProgressEntry> completedRunEndingOn(LocalDate lastDay, int days) {
        return IntStream.range(0, days)
                .mapToObj(i -> entry(lastDay.minusDays(i), true))
                .toList();
    }
}
