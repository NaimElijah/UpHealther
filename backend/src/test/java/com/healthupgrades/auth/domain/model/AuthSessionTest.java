package com.healthupgrades.auth.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules a session enforces about itself: when it may be used, what counts as the right credential,
 * and what rotation does to both.
 *
 * <p>Every method takes the instant it is judged at, so all of this is asserted without a clock and
 * without waiting. That is the reason the aggregate has no clock of its own.
 *
 * <p>The distinction the class exists for is between a credential that is <em>stale</em> and one that is
 * <em>replayed</em>, and they are the same bytes — only the timing differs. Both are pinned here, on
 * either side of the grace window, because getting the boundary wrong in one direction signs people out
 * for having two tabs open and in the other lets a stolen credential through.
 */
class AuthSessionTest {

    private static final Instant OPENED_AT = Instant.parse("2026-09-20T10:00:00Z");
    private static final Duration IDLE = Duration.ofDays(7);
    private static final Duration ABSOLUTE = Duration.ofDays(30);
    private static final Duration GRACE = Duration.ofSeconds(10);

    private static final byte[] FIRST = digest("first");
    private static final byte[] SECOND = digest("second");
    private static final byte[] THIRD = digest("third");

    @Test
    void GivenAFreshSession_WhenItIsJudgedAtTheMomentItOpened_ThenItIsUsable() {
        assertThat(opened().isActive(OPENED_AT)).isTrue();
    }

    @Test
    void GivenASessionUnusedForLongerThanTheIdleWindow_WhenItIsJudged_ThenItIsNotUsable() {
        assertThat(opened().isActive(OPENED_AT.plus(IDLE).plusSeconds(1))).isFalse();
    }

    @Test
    void GivenASessionRefreshedRegularly_WhenTheIdleWindowWouldHavePassed_ThenItIsStillUsable() {
        AuthSession session = opened();

        session.rotate(SECOND, OPENED_AT.plus(Duration.ofDays(6)), IDLE);

        assertThat(session.isActive(OPENED_AT.plus(Duration.ofDays(12))))
                .as("each refresh slides the idle window forward, so a session in use does not lapse")
                .isTrue();
    }

    @Test
    void GivenASessionRefreshedUpToItsAbsoluteCap_WhenTheCapPasses_ThenItIsNoLongerUsable() {
        // The cap is the reason a stolen session cannot be kept alive forever by refreshing it.
        AuthSession session = opened();

        session.rotate(SECOND, OPENED_AT.plus(Duration.ofDays(29)), IDLE);

        assertThat(session.isActive(OPENED_AT.plus(ABSOLUTE).plusSeconds(1))).isFalse();
    }

    @Test
    void GivenARotationNearTheAbsoluteCap_WhenTheIdleWindowIsSlid_ThenItIsNotPushedPastTheCap() {
        AuthSession session = opened();

        session.rotate(SECOND, OPENED_AT.plus(Duration.ofDays(29)), IDLE);

        assertThat(session.getIdleExpiresAt())
                .as("sliding the idle window seven days from day 29 would outlive the thirty-day cap")
                .isEqualTo(session.getAbsoluteExpiresAt());
    }

    @Test
    void GivenARevokedSession_WhenItIsJudged_ThenItIsNotUsableHoweverFreshItIs() {
        AuthSession session = opened();

        session.revoke();

        assertThat(session.isActive(OPENED_AT)).isFalse();
    }

    @Test
    void GivenTheCredentialItWasOpenedWith_WhenItIsPresented_ThenItIsRecognisedAsCurrent() {
        assertThat(opened().matchesCurrent(FIRST)).isTrue();
    }

    @Test
    void GivenACredentialItNeverIssued_WhenItIsPresented_ThenItMatchesNeitherDigest() {
        AuthSession session = opened();
        session.rotate(SECOND, OPENED_AT, IDLE);

        assertThat(session.matchesCurrent(THIRD)).isFalse();
        assertThat(session.matchesPrevious(THIRD)).isFalse();
    }

    @Test
    void GivenARotatedSession_WhenTheNewCredentialIsPresented_ThenItIsTheCurrentOne() {
        AuthSession session = opened();

        session.rotate(SECOND, OPENED_AT, IDLE);

        assertThat(session.matchesCurrent(SECOND)).isTrue();
        assertThat(session.matchesCurrent(FIRST))
                .as("the credential that was exchanged must stop being the current one")
                .isFalse();
    }

    @Test
    void GivenTheSupersededCredential_WhenItIsPresentedInsideTheGraceWindow_ThenItIsMerelyStale() {
        // Two tabs waking together. The second one to arrive is holding a credential that was current
        // when it was sent, and signing that user out would be the wrong answer.
        AuthSession session = opened();
        session.rotate(SECOND, OPENED_AT, IDLE);

        assertThat(session.matchesPreviousWithin(FIRST, OPENED_AT.plus(GRACE), GRACE)).isTrue();
    }

    @Test
    void GivenTheSupersededCredential_WhenItIsPresentedAfterTheGraceWindow_ThenItIsNoLongerMerelyStale() {
        AuthSession session = opened();
        session.rotate(SECOND, OPENED_AT, IDLE);

        assertThat(session.matchesPreviousWithin(FIRST, OPENED_AT.plus(GRACE).plusMillis(1), GRACE))
                .as("past the window it is a replay, and the caller has to treat it as one")
                .isFalse();
        assertThat(session.matchesPrevious(FIRST))
                .as("but it is still recognisably the credential this session issued, which is how a "
                        + "replay is told apart from a guess")
                .isTrue();
    }

    @Test
    void GivenASessionThatHasNeverRotated_WhenAnyCredentialIsCheckedAgainstThePrevious_ThenThereIsNone() {
        // There is no superseded credential yet, so nothing may be forgiven as stale and nothing may be
        // treated as a replay. Without the null guard this is where the first refresh would throw.
        assertThat(opened().matchesPrevious(FIRST)).isFalse();
    }

    @Test
    void GivenTwoRotations_WhenTheOldestCredentialIsPresented_ThenItIsNotRecognisedAtAll() {
        // Only the immediately previous credential is remembered. An older one is indistinguishable
        // from a guess, and is refused without revoking anything - which is what stops someone who
        // knows a session id from ending it by presenting rubbish.
        AuthSession session = opened();
        session.rotate(SECOND, OPENED_AT, IDLE);
        session.rotate(THIRD, OPENED_AT.plusSeconds(60), IDLE);

        assertThat(session.matchesCurrent(FIRST)).isFalse();
        assertThat(session.matchesPrevious(FIRST)).isFalse();
    }

    private static AuthSession opened() {
        return AuthSession.opened(UUID.randomUUID(), UUID.randomUUID(), FIRST, OPENED_AT, IDLE, ABSOLUTE);
    }

    /** A stand-in digest. Its bytes are irrelevant; only whether two of them are equal matters here. */
    private static byte[] digest(String seed) {
        return new RefreshToken(UUID.nameUUIDFromBytes(seed.getBytes()), seed).hash();
    }
}
