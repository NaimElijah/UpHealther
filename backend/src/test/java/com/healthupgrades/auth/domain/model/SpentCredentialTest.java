package com.healthupgrades.auth.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * A credential a session has spent: a digest and the instant it was retired (S5.A1, ADR-065).
 *
 * <p>A record compares and copies an array component by reference, so each of the overrides below is a
 * place the generated behaviour would be wrong: two reads of one stored row would differ, a caller
 * could change a digest the session relies on, and a log line would print the digest's bytes.
 */
class SpentCredentialTest {

    private static final Instant SPENT_AT = Instant.parse("2026-09-20T10:00:00Z");
    private static final Duration GRACE = Duration.ofSeconds(10);

    @Test
    void GivenNoDigest_WhenASpentCredentialIsBuilt_ThenItIsRefused() {
        assertThatThrownBy(() -> new SpentCredential(null, SPENT_AT)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void GivenNoInstant_WhenASpentCredentialIsBuilt_ThenItIsRefused() {
        assertThatThrownBy(() -> new SpentCredential(digest(), null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void GivenTheDigestItWasBuiltFrom_WhenThatArrayIsChangedAfterwards_ThenTheSpentCredentialKeepsItsOwn() {
        byte[] given = digest();
        SpentCredential spent = new SpentCredential(given, SPENT_AT);

        given[0] = 99;

        assertThat(spent.tokenHash()).isEqualTo(digest());
    }

    @Test
    void GivenTheDigestItHandsOut_WhenThatArrayIsChanged_ThenTheNextReadIsUnchanged() {
        SpentCredential spent = new SpentCredential(digest(), SPENT_AT);

        spent.tokenHash()[0] = 99;

        assertThat(spent.tokenHash()).isEqualTo(digest());
    }

    @Test
    void GivenTwoSpentCredentialsWithEqualDigestsAndInstants_WhenTheyAreCompared_ThenTheyAreEqualWithEqualHashes() {
        SpentCredential one = new SpentCredential(digest(), SPENT_AT);
        SpentCredential other = new SpentCredential(digest(), SPENT_AT);

        assertThat(one).isEqualTo(other).hasSameHashCodeAs(other);
    }

    @Test
    void GivenTwoSpentCredentialsWithDifferentDigests_WhenTheyAreCompared_ThenTheyDiffer() {
        assertThat(new SpentCredential(digest(), SPENT_AT))
                .isNotEqualTo(new SpentCredential(new byte[] {4, 5, 6}, SPENT_AT));
    }

    @Test
    void GivenTwoSpentCredentialsSpentAtDifferentInstants_WhenTheyAreCompared_ThenTheyDiffer() {
        assertThat(new SpentCredential(digest(), SPENT_AT))
                .isNotEqualTo(new SpentCredential(digest(), SPENT_AT.plusSeconds(1)));
    }

    @Test
    void GivenASpentCredential_WhenItIsPrinted_ThenItShowsWhenItWasSpentAndNotItsDigest() {
        String printed = new SpentCredential(digest(), SPENT_AT).toString();

        assertThat(printed)
                .contains(SPENT_AT.toString())
                .doesNotContain("tokenHash")
                .doesNotContain(Arrays.toString(digest()));
    }

    @Test
    void GivenTheLastInstantOfItsGrace_WhenItIsJudged_ThenItIsStillStaleAndAMomentLaterItIsNot() {
        SpentCredential spent = new SpentCredential(digest(), SPENT_AT);

        assertThat(spent.isWithinGrace(SPENT_AT.plus(GRACE), GRACE)).isTrue();
        assertThat(spent.isWithinGrace(SPENT_AT.plus(GRACE).plusNanos(1), GRACE))
                .isFalse();
    }

    /** A fresh array every call, so no test can change another's expected value. */
    private static byte[] digest() {
        return new byte[] {1, 2, 3};
    }
}
