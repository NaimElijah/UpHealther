package com.healthupgrades.auth.application;

import com.healthupgrades.auth.domain.model.AuthSession;
import com.healthupgrades.auth.domain.port.out.AuthSessionRepositoryPort;
import com.healthupgrades.auth.domain.port.out.SecretGeneratorPort;
import com.healthupgrades.common.domain.audit.AuditAction;
import com.healthupgrades.common.domain.audit.AuditOutcome;
import com.healthupgrades.support.RecordingAuditTrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The refresh protocol: one use per credential, a short forgiveness window, and a session that ends
 * itself the moment it can tell two parties are holding its credentials.
 *
 * <p>Uses a real in-memory implementation of the repository port rather than a mock. What is being
 * asserted is a sequence of reads and writes against one row — rotate, then present the old credential,
 * then see what the row now says — and a mock would turn each of those into a stubbing decision made by
 * this test rather than behaviour produced by the service.
 *
 * <p>The clock moves, because the grace window is the whole subject and it cannot be crossed by a fixed
 * one. Nothing sleeps: time is a field.
 */
class AuthSessionServiceTest {

    private static final Instant START = Instant.parse("2026-09-20T10:00:00Z");
    private static final Duration IDLE = Duration.ofDays(7);
    private static final Duration ABSOLUTE = Duration.ofDays(30);
    private static final Duration GRACE = Duration.ofSeconds(10);
    private static final UUID USER_ID = UUID.fromString("0f2c8f5a-2a4e-4a1d-8f0a-3c5b9d1e77a1");

    private final InMemorySessions sessions = new InMemorySessions();
    private final QueuedSecrets secrets = new QueuedSecrets();
    private final RecordingAuditTrail auditTrail = new RecordingAuditTrail();
    private final MovableClock clock = new MovableClock(START);

    private AuthSessionService service;

    @BeforeEach
    void setUp() {
        service = new AuthSessionService(sessions, secrets,
                new AuthSessionProperties(IDLE, ABSOLUTE, GRACE), auditTrail, clock);
    }

    @Test
    void GivenAnAuthenticatedAccount_WhenASessionIsOpened_ThenTheCredentialNamesItAndOnlyItsHashIsStored() {
        SessionGrant grant = service.open(USER_ID);

        assertThat(grant.refreshToken())
                .as("the credential is <sessionId>.<secret>, so the server knows which row to lock")
                .startsWith(grant.sessionId() + ".");
        assertThat(sessions.byId(grant.sessionId()).getRefreshTokenHash())
                .as("a copy of the table must not yield anything that can be presented")
                .isNotEqualTo(grant.refreshToken().getBytes());
        assertThat(grant.expiresAt()).isEqualTo(START.plus(ABSOLUTE));
    }

    @Test
    void GivenTheCurrentCredential_WhenItIsExchanged_ThenTheSessionContinuesUnderANewOne() {
        SessionGrant opened = service.open(USER_ID);

        RefreshOutcome outcome = service.refresh(opened.refreshToken());

        assertThat(outcome).isInstanceOfSatisfying(RefreshOutcome.Rotated.class, rotated -> {
            assertThat(rotated.userId()).isEqualTo(USER_ID);
            assertThat(rotated.grant().sessionId()).isEqualTo(opened.sessionId());
            assertThat(rotated.grant().refreshToken())
                    .as("a credential is good for one exchange and no more")
                    .isNotEqualTo(opened.refreshToken());
        });
        assertThat(auditTrail.recorded(AuditAction.AUTH_REFRESH, AuditOutcome.ALLOWED)).isTrue();
    }

    @Test
    void GivenACredentialAlreadyExchanged_WhenItIsPresentedInsideTheGraceWindow_ThenItIsStaleAndNothingIsRevoked() {
        // Two tabs waking together. The second holds a credential that was current when it was sent.
        SessionGrant opened = service.open(USER_ID);
        service.refresh(opened.refreshToken());
        clock.moveTo(START.plus(GRACE));

        RefreshOutcome outcome = service.refresh(opened.refreshToken());

        assertThat(outcome).isInstanceOf(RefreshOutcome.Stale.class);
        assertThat(sessions.byId(opened.sessionId()).isActive(clock.instant()))
                .as("being told to retry must not have cost the user their session")
                .isTrue();
    }

    @Test
    void GivenACredentialAlreadyExchanged_WhenItIsPresentedAfterTheGraceWindow_ThenTheSessionIsRevoked() {
        // Past the window, the legitimate client has demonstrably already exchanged this credential, so
        // whoever is presenting it now is not that client. Ending the session signs the real user out
        // too, and that is the intended cost: it is the only signal available that something is wrong.
        SessionGrant opened = service.open(USER_ID);
        service.refresh(opened.refreshToken());
        clock.moveTo(START.plus(GRACE).plusSeconds(1));

        RefreshOutcome outcome = service.refresh(opened.refreshToken());

        assertThat(outcome).isInstanceOf(RefreshOutcome.Rejected.class);
        assertThat(sessions.byId(opened.sessionId()).isActive(clock.instant())).isFalse();
        assertThat(auditTrail.recorded(AuditAction.AUTH_TOKEN_REUSE, AuditOutcome.REFUSED)).isTrue();
    }

    @Test
    void GivenTheRotatedCredential_WhenItIsPresentedAfterAReplayEndedTheSession_ThenItIsRefusedToo() {
        // The consequence worth stating: the honest client's own credential stops working as well.
        SessionGrant opened = service.open(USER_ID);
        RefreshOutcome first = service.refresh(opened.refreshToken());
        SessionGrant live = ((RefreshOutcome.Rotated) first).grant();
        clock.moveTo(START.plus(GRACE).plusSeconds(1));
        service.refresh(opened.refreshToken());

        assertThat(service.refresh(live.refreshToken())).isInstanceOf(RefreshOutcome.Rejected.class);
    }

    @Test
    void GivenACredentialTheSessionNeverIssued_WhenItIsPresented_ThenItIsRefusedWithoutRevokingAnything() {
        // The session id travels in an access token claim, so it is not a secret. If a wrong secret
        // revoked the session, anybody who learned an id could sign its owner out at will.
        SessionGrant opened = service.open(USER_ID);

        RefreshOutcome outcome = service.refresh(opened.sessionId() + ".not-the-secret");

        assertThat(outcome).isInstanceOf(RefreshOutcome.Rejected.class);
        assertThat(sessions.byId(opened.sessionId()).isActive(clock.instant())).isTrue();
        assertThat(auditTrail.recorded(AuditAction.AUTH_TOKEN_REUSE, AuditOutcome.REFUSED)).isFalse();
    }

    @Test
    void GivenAnUnparseableCredential_WhenItIsPresented_ThenItIsRefusedWithoutReadingAnything() {
        assertThat(service.refresh("not-a-credential")).isInstanceOf(RefreshOutcome.Rejected.class);
        assertThat(service.refresh(null)).isInstanceOf(RefreshOutcome.Rejected.class);
    }

    @Test
    void GivenASessionUnusedPastItsIdleWindow_WhenItIsRefreshed_ThenItIsRefused() {
        SessionGrant opened = service.open(USER_ID);
        clock.moveTo(START.plus(IDLE).plusSeconds(1));

        assertThat(service.refresh(opened.refreshToken())).isInstanceOf(RefreshOutcome.Rejected.class);
    }

    @Test
    void GivenASessionKeptAliveUntilItsAbsoluteCap_WhenItIsRefreshed_ThenItIsRefused() {
        SessionGrant grant = service.open(USER_ID);
        // Refreshed every six days, so the idle window never lapses - and it still ends at the cap.
        for (int day = 6; day <= 30; day += 6) {
            clock.moveTo(START.plus(Duration.ofDays(day)));
            RefreshOutcome outcome = service.refresh(grant.refreshToken());
            if (outcome instanceof RefreshOutcome.Rotated rotated) {
                grant = rotated.grant();
            }
        }
        clock.moveTo(START.plus(ABSOLUTE).plusSeconds(1));

        assertThat(service.refresh(grant.refreshToken())).isInstanceOf(RefreshOutcome.Rejected.class);
    }

    @Test
    void GivenALiveSession_WhenItIsSignedOutOf_ThenItStopsWorkingAndIsAudited() {
        SessionGrant opened = service.open(USER_ID);

        assertThat(service.revoke(opened.refreshToken())).isTrue();

        assertThat(service.isActive(opened.sessionId())).isFalse();
        assertThat(auditTrail.recorded(AuditAction.AUTH_LOGOUT, AuditOutcome.ALLOWED)).isTrue();
    }

    @Test
    void GivenACredentialThatDoesNotMatch_WhenSignOutIsAttempted_ThenTheSessionIsLeftAlone() {
        SessionGrant opened = service.open(USER_ID);

        assertThat(service.revoke(opened.sessionId() + ".not-the-secret")).isFalse();

        assertThat(service.isActive(opened.sessionId()))
                .as("knowing a session id is not authority to end it")
                .isTrue();
    }

    @Test
    void GivenACredentialRotatedOutLongAgo_WhenItIsUsedToSignOut_ThenTheSessionIsLeftAlone() {
        // Otherwise sign-out is a way around reuse detection: the same credential presented to refresh
        // is theft - revoked, logged, audited AUTH_TOKEN_REUSE - and presented here would have ended the
        // victim's session as an ordinary sign-out, with the counter that should stay at zero unmoved.
        SessionGrant opened = service.open(USER_ID);
        service.refresh(opened.refreshToken());
        clock.moveTo(START.plus(GRACE).plusSeconds(1));

        assertThat(service.revoke(opened.refreshToken())).isFalse();

        assertThat(service.isActive(opened.sessionId())).isTrue();
        assertThat(auditTrail.recorded(AuditAction.AUTH_LOGOUT, AuditOutcome.ALLOWED)).isFalse();
    }

    @Test
    void GivenACredentialRotatedOutMomentsAgo_WhenItIsUsedToSignOut_ThenItStillWorks() {
        // The forgiving half: a request already in flight when another tab rotated must still sign out.
        SessionGrant opened = service.open(USER_ID);
        service.refresh(opened.refreshToken());
        clock.moveTo(START.plus(GRACE));

        assertThat(service.revoke(opened.refreshToken())).isTrue();

        assertThat(service.isActive(opened.sessionId())).isFalse();
    }

    @Test
    void GivenTwoSessionsForOneAccount_WhenOneSignsOut_ThenTheOtherIsUntouched() {
        // Sign-out is per device. Ending every session because one browser said goodbye would be a
        // surprise on whatever phone the same account is signed in on.
        SessionGrant first = service.open(USER_ID);
        SessionGrant second = service.open(USER_ID);

        service.revoke(first.refreshToken());

        assertThat(service.isActive(second.sessionId())).isTrue();
    }

    @Test
    void GivenSeveralSessionsForOneAccount_WhenTheyAreAllRevoked_ThenNoneOfThemWork() {
        SessionGrant first = service.open(USER_ID);
        SessionGrant second = service.open(USER_ID);

        assertThat(service.revokeAllForUser(USER_ID)).isEqualTo(2);

        assertThat(service.isActive(first.sessionId())).isFalse();
        assertThat(service.isActive(second.sessionId())).isFalse();
    }

    @Test
    void GivenASessionIdNothingIssued_WhenItIsCheckedForLiveness_ThenItIsNotActive() {
        assertThat(service.isActive(UUID.randomUUID())).isFalse();
    }

    @Test
    void GivenRevokedAndExpiredSessions_WhenTheCleanupRuns_ThenOnlyTheUsableOnesRemain() {
        SessionGrant live = service.open(USER_ID);
        SessionGrant signedOut = service.open(USER_ID);
        service.revoke(signedOut.refreshToken());

        assertThat(service.deleteUnusableSessions()).isEqualTo(1);

        assertThat(sessions.all()).extracting(AuthSession::getId).containsExactly(live.sessionId());
    }

    // ------------------------------------------------------------------ fakes

    /**
     * An in-memory stand-in for the repository port.
     *
     * <p>{@code findForUpdate} returns the same instance as {@code findById} and locks nothing: the lock
     * is a database concern, and asserting it needs a database — {@code AuthSessionPersistenceIT} is
     * where that happens.
     */
    private static final class InMemorySessions implements AuthSessionRepositoryPort {

        private final Map<UUID, AuthSession> rows = new HashMap<>();

        @Override
        public AuthSession save(AuthSession session) {
            rows.put(session.getId(), session);
            return session;
        }

        @Override
        public Optional<AuthSession> findById(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<AuthSession> findForUpdate(UUID id) {
            return findById(id);
        }

        @Override
        public int revokeAllForUser(UUID userId) {
            List<AuthSession> live = rows.values().stream()
                    .filter(session -> session.getUserId().equals(userId))
                    .filter(session -> !session.isRevoked())
                    .toList();
            live.forEach(AuthSession::revoke);
            return live.size();
        }

        @Override
        public int deleteUnusableAsOf(Instant now) {
            List<UUID> doomed = rows.values().stream()
                    .filter(session -> !session.isActive(now))
                    .map(AuthSession::getId)
                    .toList();
            doomed.forEach(rows::remove);
            return doomed.size();
        }

        AuthSession byId(UUID id) {
            return findById(id).orElseThrow(() -> new AssertionError("no session " + id));
        }

        List<AuthSession> all() {
            return new ArrayList<>(rows.values());
        }
    }

    /** Predictable secrets, so a credential in an assertion is one this test can name. */
    private static final class QueuedSecrets implements SecretGeneratorPort {

        private int count;

        @Override
        public String generate() {
            return "secret-" + (++count);
        }
    }

    /** A clock that can be moved, because the grace window cannot be crossed with a fixed one. */
    private static final class MovableClock extends Clock {

        private Instant now;

        private MovableClock(Instant now) {
            this.now = now;
        }

        void moveTo(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
