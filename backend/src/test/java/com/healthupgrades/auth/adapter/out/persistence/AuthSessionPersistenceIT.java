package com.healthupgrades.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.auth.domain.model.AuthSession;
import com.healthupgrades.auth.domain.model.RefreshToken;
import com.healthupgrades.auth.domain.model.SpentCredential;
import com.healthupgrades.auth.domain.port.out.AuthSessionRepositoryPort;
import com.healthupgrades.support.AUser;
import com.healthupgrades.support.PostgresIT;
import com.healthupgrades.user.domain.model.User;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

/**
 * What the session table actually does, which no in-memory fake can tell us.
 *
 * <p>Three kinds of thing are checked. The mapping: it has to agree with the migration at all —
 * Hibernate boots with {@code ddl-auto: validate}, so an {@code Instant} against a naive
 * {@code TIMESTAMP} or a {@code byte[]} against the wrong type fails the context rather than a method
 * here — and the digests and instants have to survive a round trip unchanged, because a comparison
 * against a mangled digest would refuse every refresh. The queries: revoking every session of one
 * account, and no other account's, the sweep's choice of which rows nothing can use again, and the
 * lookup of a spent credential, scoped to its session. And the foreign keys: deleting an account takes
 * its sessions with it, and deleting a session takes its spent credentials, which nothing in Java does.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AuthSessionRepositoryAdapter.class)
class AuthSessionPersistenceIT extends PostgresIT {

    private static final Instant NOW = Instant.parse("2026-09-20T10:00:00Z");
    private static final Duration IDLE = Duration.ofDays(7);
    private static final Duration ABSOLUTE = Duration.ofDays(30);

    @Autowired
    AuthSessionRepositoryPort repository;

    @Autowired
    TestEntityManager entityManager;

    @Test
    void GivenASessionWithTokenDigests_WhenItIsReadBack_ThenTheBytesAreUnchanged() {
        // The digests are compared with MessageDigest.isEqual on the way back in. A column that padded,
        // truncated or re-encoded them would refuse every refresh, with nothing to show for it.
        RefreshToken issued = new RefreshToken(UUID.randomUUID(), "a-secret");
        RefreshToken rotated = new RefreshToken(issued.sessionId(), "a-newer-secret");
        AuthSession session = stored(opened(issued));
        rotate(session, rotated, NOW.plusSeconds(60));

        AuthSession read = repository.findById(session.getId()).orElseThrow();

        assertThat(read.matchesCurrent(rotated.hash())).isTrue();
        assertThat(repository.findSpent(session.getId(), issued.hash()))
                .contains(new SpentCredential(issued.hash(), NOW.plusSeconds(60)));
    }

    @Test
    void GivenASessionThatRotatedTwice_WhenEachSpentCredentialIsLookedUp_ThenBothAreRememberedWithTheirInstants() {
        // S5.A1: a credential two rotations old has to be recognised, or a thief who refreshed twice is
        // never detected.
        RefreshToken first = new RefreshToken(UUID.randomUUID(), "first");
        RefreshToken second = new RefreshToken(first.sessionId(), "second");
        AuthSession session = stored(opened(first));
        SpentCredential firstSpent = rotate(session, second, NOW.plusSeconds(60));
        SpentCredential secondSpent =
                rotate(session, new RefreshToken(first.sessionId(), "third"), NOW.plusSeconds(120));

        assertThat(repository.findSpent(session.getId(), first.hash())).contains(firstSpent);
        assertThat(repository.findSpent(session.getId(), second.hash())).contains(secondSpent);
    }

    @Test
    void GivenACredentialAnotherSessionSpent_WhenItIsLookedUpForThisSession_ThenItIsNotFound() {
        // The lookup is scoped by session as well as by digest: one session's history says nothing about
        // a credential presented to another.
        RefreshToken spentElsewhere = new RefreshToken(UUID.randomUUID(), "spent-elsewhere");
        AuthSession elsewhere = stored(opened(spentElsewhere));
        AuthSession here = stored(opened(new RefreshToken(UUID.randomUUID(), "here")));
        rotate(elsewhere, new RefreshToken(spentElsewhere.sessionId(), "newer"), NOW.plusSeconds(60));

        assertThat(repository.findSpent(here.getId(), spentElsewhere.hash())).isEmpty();
    }

    @Test
    void GivenASessionWithSpentCredentials_WhenTheCleanupDeletesIt_ThenItsSpentCredentialsGoWithIt() {
        // The sweep deletes sessions in one statement, so only the foreign key's cascade can take the
        // history with them; without it a deleted session would leave its digests behind.
        RefreshToken issued = new RefreshToken(UUID.randomUUID(), "a-secret");
        AuthSession session = stored(opened(issued));
        rotate(session, new RefreshToken(issued.sessionId(), "a-newer-secret"), NOW.plusSeconds(60));
        AuthSession ended = repository.findForUpdate(session.getId()).orElseThrow();
        ended.revoke();
        repository.save(ended);
        flushAndClear();

        repository.deleteUnusableAsOf(NOW);
        flushAndClear();

        assertThat(spentRowsOf(session.getId())).isZero();
    }

    @Test
    void GivenASessionWithInstants_WhenItIsReadBack_ThenTheyAreTheSameInstants() {
        // TIMESTAMPTZ, not TIMESTAMP. A naive column would make the value depend on the timezone of
        // whichever host happened to write it, and expiry is compared against now() on another.
        AuthSession session = repository.save(opened(new RefreshToken(UUID.randomUUID(), "a-secret")));
        flushAndClear();

        AuthSession read = repository.findById(session.getId()).orElseThrow();

        assertThat(read.getCreatedAt()).isEqualTo(NOW);
        assertThat(read.getIdleExpiresAt()).isEqualTo(NOW.plus(IDLE));
        assertThat(read.getAbsoluteExpiresAt()).isEqualTo(NOW.plus(ABSOLUTE));
    }

    @Test
    void GivenSeveralLiveSessionsForOneAccount_WhenTheyAreAllRevoked_ThenNoneOfThemRemainUsable() {
        User owner = persistedUser();
        AuthSession first = repository.save(openedFor(owner, "one"));
        AuthSession second = repository.save(openedFor(owner, "two"));
        flushAndClear();

        assertThat(repository.revokeAllForUser(owner.getId())).isEqualTo(2);

        assertThat(List.of(first.getId(), second.getId()))
                .allSatisfy(
                        id -> assertThat(repository.findById(id).orElseThrow().isActive(NOW))
                                .isFalse());
    }

    @Test
    void GivenLiveSessionsForTwoAccounts_WhenOneAccountsSessionsAreRevoked_ThenTheOtherAccountStaysSignedIn() {
        // Switching one account off (FR-43) must not sign everybody out. The count above cannot show it:
        // it holds only while no other live session is in the shared table.
        User switchedOff = persistedUser();
        User bystander = persistedUser();
        AuthSession ended = repository.save(openedFor(switchedOff, "one"));
        AuthSession untouched = repository.save(openedFor(bystander, "two"));
        flushAndClear();

        repository.revokeAllForUser(switchedOff.getId());

        assertThat(repository.findById(ended.getId()).orElseThrow().isActive(NOW))
                .isFalse();
        assertThat(repository.findById(untouched.getId()).orElseThrow().isActive(NOW))
                .as("another account's session")
                .isTrue();
    }

    @Test
    void GivenAnAlreadyRevokedSession_WhenEverySessionIsRevokedAgain_ThenItIsNotCountedTwice() {
        // The statement is bounded to the live ones, so a caller can use the count as "how many devices
        // were signed out" rather than "how many rows exist".
        User owner = persistedUser();
        AuthSession session = openedFor(owner, "one");
        session.revoke();
        repository.save(session);
        flushAndClear();

        assertThat(repository.revokeAllForUser(owner.getId())).isZero();
    }

    @Test
    void GivenSessionsThatCanNoLongerBeUsed_WhenTheCleanupRuns_ThenOnlyTheUsableOnesSurvive() {
        // The sweep deletes every unusable row in the table, and the container is shared by the whole
        // integration suite. AuthSessionFlowIT commits real sign-outs into it, so whenever that class
        // ran first, the count below included its revoked sessions. Emptying the table inside this
        // test's own transaction makes the count mean "this fixture" again, in any order. The
        // rollback at the end restores what was there.
        entityManager
                .getEntityManager()
                .createNativeQuery("DELETE FROM auth_sessions")
                .executeUpdate();
        User owner = persistedUser();
        AuthSession live = repository.save(openedFor(owner, "live"));
        AuthSession signedOut = openedFor(owner, "signed-out");
        signedOut.revoke();
        repository.save(signedOut);
        // Each expiry on its own: unused past its idle window but inside its cap, and past its cap with
        // an idle window that has not run out, so each row is unusable for exactly one reason.
        AuthSession idledOut =
                repository.save(openedFor(owner, "idled-out", NOW.minus(IDLE).minusSeconds(1), IDLE));
        AuthSession capped =
                repository.save(openedFor(owner, "capped", NOW.minus(ABSOLUTE).minusSeconds(1), ABSOLUTE.plus(IDLE)));
        flushAndClear();

        assertThat(repository.deleteUnusableAsOf(NOW)).isEqualTo(3);

        assertThat(repository.findById(live.getId())).isPresent();
        assertThat(repository.findById(signedOut.getId()))
                .as("the revoked session")
                .isEmpty();
        assertThat(repository.findById(idledOut.getId()))
                .as("the idled-out session")
                .isEmpty();
        assertThat(repository.findById(capped.getId()))
                .as("the session past its cap")
                .isEmpty();
    }

    @Test
    void GivenASessionForAnAccount_WhenTheAccountIsDeleted_ThenItsSessionsGoWithIt() {
        // The cascade is a property of the foreign key. Without it, deleting an account would leave
        // behind rows holding token digests for a person who no longer exists.
        User owner = persistedUser();
        AuthSession session = repository.save(openedFor(owner, "one"));
        flushAndClear();

        entityManager
                .getEntityManager()
                .createNativeQuery("DELETE FROM users WHERE id = :id")
                .setParameter("id", owner.getId())
                .executeUpdate();
        flushAndClear();

        assertThat(repository.findById(session.getId())).isEmpty();
    }

    /** Counts the spent-credential rows of one session straight from the table, past any port. */
    private long spentRowsOf(UUID sessionId) {
        return ((Number) entityManager
                        .getEntityManager()
                        .createNativeQuery("SELECT count(*) FROM auth_session_spent_tokens WHERE session_id = :id")
                        .setParameter("id", sessionId)
                        .getSingleResult())
                .longValue();
    }

    private User persistedUser() {
        return entityManager.persistAndFlush(
                AUser.aUser().id(null).email(UUID.randomUUID() + "@example.com").build());
    }

    private AuthSession openedFor(User owner, String secret) {
        return openedFor(owner, secret, NOW, IDLE);
    }

    /** A session opened at {@code openedAt} with the given idle window, under the usual absolute cap. */
    private AuthSession openedFor(User owner, String secret, Instant openedAt, Duration idle) {
        UUID sessionId = UUID.randomUUID();
        return AuthSession.opened(
                sessionId, owner.getId(), new RefreshToken(sessionId, secret).hash(), openedAt, idle, ABSOLUTE);
    }

    /**
     * A session whose owner is a freshly persisted account, because the foreign key insists on one.
     */
    private AuthSession opened(RefreshToken token) {
        return AuthSession.opened(token.sessionId(), persistedUser().getId(), token.hash(), NOW, IDLE, ABSOLUTE);
    }

    /** Stores a newly opened session and detaches it, as a sign-in commits one before any refresh. */
    private AuthSession stored(AuthSession opened) {
        repository.save(opened);
        flushAndClear();
        return opened;
    }

    /**
     * Rotates a stored session as a refresh does: read under the row lock, rotated, and stored through the
     * one port method that writes the session and its spent credential together. Nothing is flushed
     * between the two writes, so the order the port issues them in is the one under test.
     */
    private SpentCredential rotate(AuthSession stored, RefreshToken next, Instant at) {
        AuthSession locked = repository.findForUpdate(stored.getId()).orElseThrow();
        SpentCredential retired = locked.rotate(next.hash(), at, IDLE);
        repository.saveRotation(locked, retired);
        flushAndClear();
        return retired;
    }

    /** Forces the writes out and empties the persistence context, so a read is a read and not a cache hit. */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
