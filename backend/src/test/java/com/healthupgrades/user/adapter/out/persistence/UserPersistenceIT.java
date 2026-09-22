package com.healthupgrades.user.adapter.out.persistence;

import com.healthupgrades.support.AUser;
import com.healthupgrades.support.PostgresIT;
import com.healthupgrades.user.domain.model.EmailAlreadyRegisteredException;
import com.healthupgrades.user.domain.model.User;
import com.healthupgrades.user.domain.port.out.UserRepositoryPort;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-4 as the database enforces it: one address, one account, however it was typed.
 *
 * <p>The application normalises every address it writes, but a rule that exists only in Java is a rule
 * any other writer can break. These assertions are about the schema and the adapter's reading of it,
 * which no mocked repository can observe.
 *
 * <p>Also FR-42's order: the administration list is oldest first, and the sort is applied by this
 * adapter, so only a real database can show it. The rows are inserted natively because
 * {@code @PrePersist} stamps the current time, and they are dated 2020 so they sort ahead of the
 * seeded demo account.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(UserRepositoryAdapter.class)
class UserPersistenceIT extends PostgresIT {

    /** Fixed so their order under the {@code id} tie-break is known in advance: A &lt; B &lt; C &lt; D. */
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID D = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    @Autowired UserRepositoryPort repository;
    @Autowired TestEntityManager entityManager;

    @Test
    void GivenAnEmailWithCapitals_WhenInsertedBehindTheApplication_ThenTheDatabaseRefusesIt() {
        assertThatThrownBy(() -> entityManager.getEntityManager().createNativeQuery(
                        "INSERT INTO users (name, email, password_hash) VALUES ('Someone', 'Someone@Example.com', 'x')")
                .executeUpdate())
                .isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("users_email_normalised");
    }

    @Test
    void GivenAnEmailWithSurroundingSpaces_WhenInsertedBehindTheApplication_ThenTheDatabaseRefusesIt() {
        assertThatThrownBy(() -> entityManager.getEntityManager().createNativeQuery(
                        "INSERT INTO users (name, email, password_hash) VALUES ('Someone', ' someone@example.com', 'x')")
                .executeUpdate())
                .isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("users_email_normalised");
    }

    @Test
    void GivenAMixedCaseEmail_WhenTheEntityIsPersisted_ThenItIsStoredNormalised() {
        UUID id = entityManager.persistAndFlush(
                AUser.aUser().id(null).email(" Mixed@Example.COM ").build()).getId();
        entityManager.clear();

        assertThat(entityManager.find(User.class, id).getEmail()).isEqualTo("mixed@example.com");
    }

    @Test
    void GivenAnAddressAlreadyHeld_WhenAnotherUserIsRegistered_ThenTheDuplicateIsReportedInsideTheCall() {
        // The flush is the point: without it the violation is raised at commit, after the registering
        // method has returned, where nothing can translate it and the caller receives a 500.
        entityManager.persistAndFlush(AUser.aUser().id(null).email("taken@example.com").build());

        assertThatThrownBy(() -> repository.saveAndFlush(
                AUser.aUser().id(null).email("taken@example.com").build()))
                .isInstanceOf(EmailAlreadyRegisteredException.class)
                .hasMessageNotContaining("taken@example.com");
    }

    @Test
    void GivenAccountsCreatedAtDifferentTimes_WhenTheyAreListed_ThenTheOldestComesFirstAndATieIsBrokenById() {
        // Inserted out of order, and B and C share a creation time, so neither insertion order nor
        // the timestamp alone can produce the expected sequence.
        insertUser(D, "2020-01-03T00:00:00");
        insertUser(C, "2020-01-02T00:00:00");
        insertUser(A, "2020-01-01T00:00:00");
        insertUser(B, "2020-01-02T00:00:00");

        assertThat(repository.findAll(0, 4)).extracting(User::getId).containsExactly(A, B, C, D);
    }

    @Test
    void GivenMoreAccountsThanOnePage_WhenConsecutivePagesAreRead_ThenEveryAccountAppearsExactlyOnce() {
        // The tie-break is what this protects: without a total order, B and C can swap between the
        // two queries and one of them is shown twice while the other is never shown. C is inserted
        // first so that physical order alone would put it on the wrong page.
        insertUser(A, "2020-01-01T00:00:00");
        insertUser(C, "2020-01-02T00:00:00");
        insertUser(B, "2020-01-02T00:00:00");
        insertUser(D, "2020-01-03T00:00:00");

        assertThat(repository.findAll(0, 2)).extracting(User::getId).containsExactly(A, B);
        assertThat(repository.findAll(1, 2)).extracting(User::getId).containsExactly(C, D);
    }

    private void insertUser(UUID id, String createdAt) {
        entityManager.getEntityManager().createNativeQuery(
                        "INSERT INTO users (id, name, email, password_hash, created_at) "
                                + "VALUES (CAST(?1 AS uuid), 'Someone', ?2, 'x', CAST(?3 AS timestamp))")
                .setParameter(1, id.toString())
                .setParameter(2, id + "@example.com")
                .setParameter(3, createdAt)
                .executeUpdate();
    }
}
