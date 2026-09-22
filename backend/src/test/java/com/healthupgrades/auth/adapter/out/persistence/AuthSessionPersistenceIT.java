package com.healthupgrades.auth.adapter.out.persistence;

import com.healthupgrades.auth.domain.model.AuthSession;
import com.healthupgrades.auth.domain.model.RefreshToken;
import com.healthupgrades.auth.domain.port.out.AuthSessionRepositoryPort;
import com.healthupgrades.support.AUser;
import com.healthupgrades.support.PostgresIT;
import com.healthupgrades.user.domain.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the session table actually does, which no in-memory fake can tell us.
 *
 * <p>Three things are being checked and only one of them is a query. The first is that the mapping and
 * the migration agree at all — Hibernate boots with {@code ddl-auto: validate}, so an {@code Instant}
 * against a naive {@code TIMESTAMP} or a {@code byte[]} against the wrong type fails the context rather
 * than this method. The second is that the digests survive a round trip through {@code BYTEA} byte for
 * byte, because a comparison against a mangled digest would refuse every refresh. The third is that
 * deleting an account takes its sessions with it, which is a property of the foreign key and of nothing
 * in Java.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AuthSessionRepositoryAdapter.class)
class AuthSessionPersistenceIT extends PostgresIT {

    private static final Instant NOW = Instant.parse("2026-09-20T10:00:00Z");
    private static final Duration IDLE = Duration.ofDays(7);
    private static final Duration ABSOLUTE = Duration.ofDays(30);

    @Autowired AuthSessionRepositoryPort repository;
    @Autowired TestEntityManager entityManager;

    @Test
    void GivenASessionWithTokenDigests_WhenItIsReadBack_ThenTheBytesAreUnchanged() {
        // The digests are compared with MessageDigest.isEqual on the way back in. A column that padded,
        // truncated or re-encoded them would refuse every refresh, with nothing to show for it.
        RefreshToken issued = new RefreshToken(UUID.randomUUID(), "a-secret");
        RefreshToken rotated = new RefreshToken(issued.sessionId(), "a-newer-secret");
        AuthSession session = opened(issued);
        session.rotate(rotated.hash(), NOW.plusSeconds(60), IDLE);
        repository.save(session);
        flushAndClear();

        AuthSession read = repository.findById(session.getId()).orElseThrow();

        assertThat(read.matchesCurrent(rotated.hash())).isTrue();
        assertThat(read.matchesPrevious(issued.hash())).isTrue();
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
                .allSatisfy(id -> assertThat(repository.findById(id).orElseThrow().isActive(NOW)).isFalse());
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
        entityManager.getEntityManager().createNativeQuery("DELETE FROM auth_sessions").executeUpdate();
        User owner = persistedUser();
        AuthSession live = repository.save(openedFor(owner, "live"));
        AuthSession signedOut = openedFor(owner, "signed-out");
        signedOut.revoke();
        repository.save(signedOut);
        flushAndClear();

        assertThat(repository.deleteUnusableAsOf(NOW)).isEqualTo(1);

        assertThat(repository.findById(live.getId())).isPresent();
        assertThat(repository.findById(signedOut.getId())).isEmpty();
    }

    @Test
    void GivenASessionForAnAccount_WhenTheAccountIsDeleted_ThenItsSessionsGoWithIt() {
        // The cascade is a property of the foreign key. Without it, deleting an account would leave
        // behind rows holding token digests for a person who no longer exists.
        User owner = persistedUser();
        AuthSession session = repository.save(openedFor(owner, "one"));
        flushAndClear();

        entityManager.getEntityManager()
                .createNativeQuery("DELETE FROM users WHERE id = :id")
                .setParameter("id", owner.getId())
                .executeUpdate();
        flushAndClear();

        assertThat(repository.findById(session.getId())).isEmpty();
    }

    private User persistedUser() {
        return entityManager.persistAndFlush(
                AUser.aUser().id(null).email(UUID.randomUUID() + "@example.com").build());
    }

    private AuthSession openedFor(User owner, String secret) {
        UUID sessionId = UUID.randomUUID();
        return AuthSession.opened(sessionId, owner.getId(),
                new RefreshToken(sessionId, secret).hash(), NOW, IDLE, ABSOLUTE);
    }

    /**
     * A session whose owner is a freshly persisted account, because the foreign key insists on one.
     */
    private AuthSession opened(RefreshToken token) {
        return AuthSession.opened(token.sessionId(), persistedUser().getId(), token.hash(),
                NOW, IDLE, ABSOLUTE);
    }

    /** Forces the writes out and empties the persistence context, so a read is a read and not a cache hit. */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
