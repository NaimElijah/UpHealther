package com.healthupgrades.user.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.healthupgrades.support.AUser;
import com.healthupgrades.support.PostgresIT;
import com.healthupgrades.user.domain.model.EmailAlreadyRegisteredException;
import com.healthupgrades.user.domain.model.Role;
import com.healthupgrades.user.domain.model.User;
import com.healthupgrades.user.domain.model.UserPage;
import com.healthupgrades.user.domain.port.out.UserRepositoryPort;
import jakarta.persistence.PersistenceException;
import java.util.Arrays;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

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
 * seeded demo account. And D2.13's one count per page at most, which only the statements Hibernate ran
 * can show, so this class switches its statistics on.
 *
 * <p>And FR-45's "only while no <em>enabled</em> administrator exists", which is a derived query no
 * mocked port can run.
 */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true") // to count statements
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(UserRepositoryAdapter.class)
class UserPersistenceIT extends PostgresIT {

    /** Fixed so their order under the {@code id} tie-break is known in advance: A &lt; B &lt; C &lt; D. */
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID D = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    /**
     * What every count Hibernate runs over the accounts starts with, Spring Data's page count and
     * {@code count()} alike: the statistics key each query by the SQL it ran.
     */
    private static final String COUNT_STATEMENT = "select count(";

    @Autowired
    UserRepositoryPort repository;

    @Autowired
    TestEntityManager entityManager;

    @Test
    void GivenAnEmailWithCapitals_WhenInsertedBehindTheApplication_ThenTheDatabaseRefusesIt() {
        assertThatThrownBy(() -> entityManager
                        .getEntityManager()
                        .createNativeQuery(
                                "INSERT INTO users (name, email, password_hash) VALUES ('Someone', 'Someone@Example.com', 'x')")
                        .executeUpdate())
                .isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("users_email_normalised");
    }

    @Test
    void GivenAnEmailWithSurroundingSpaces_WhenInsertedBehindTheApplication_ThenTheDatabaseRefusesIt() {
        assertThatThrownBy(() -> entityManager
                        .getEntityManager()
                        .createNativeQuery(
                                "INSERT INTO users (name, email, password_hash) VALUES ('Someone', ' someone@example.com', 'x')")
                        .executeUpdate())
                .isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("users_email_normalised");
    }

    @Test
    void GivenAMixedCaseEmail_WhenTheEntityIsPersisted_ThenItIsStoredNormalised() {
        UUID id = entityManager
                .persistAndFlush(
                        AUser.aUser().id(null).email(" Mixed@Example.COM ").build())
                .getId();
        entityManager.clear();

        assertThat(entityManager.find(User.class, id).getEmail()).isEqualTo("mixed@example.com");
    }

    @Test
    void GivenAnAddressAlreadyHeld_WhenAnotherUserIsRegistered_ThenTheDuplicateIsReportedInsideTheCall() {
        // The flush is the point: without it the violation is raised at commit, after the registering
        // method has returned, where nothing can translate it and the caller receives a 500.
        entityManager.persistAndFlush(
                AUser.aUser().id(null).email("taken@example.com").build());

        assertThatThrownBy(() -> repository.saveAndFlush(
                        AUser.aUser().id(null).email("taken@example.com").build()))
                .isInstanceOf(EmailAlreadyRegisteredException.class)
                .hasMessageNotContaining("taken@example.com");
    }

    @Test
    void GivenAnIntegrityFailureThatIsNotTheEmail_WhenAUserIsSaved_ThenItIsRethrownUnchanged() {
        // Only the email's unique constraint means "already registered". Translating anything else
        // would answer a defect with a 422 telling somebody their address is taken.
        User nameless = AUser.aUser()
                .id(null)
                .name(null)
                .email(UUID.randomUUID() + "@example.com")
                .build();

        assertThatThrownBy(() -> repository.saveAndFlush(nameless))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(EmailAlreadyRegisteredException.class);
    }

    @Test
    void GivenAccountsCreatedAtDifferentTimes_WhenTheyAreListed_ThenTheOldestComesFirstAndATieIsBrokenById() {
        // Inserted out of order, and B and C share a creation time, so neither insertion order nor
        // the timestamp alone can produce the expected sequence.
        insertUser(D, "2020-01-03T00:00:00");
        insertUser(C, "2020-01-02T00:00:00");
        insertUser(A, "2020-01-01T00:00:00");
        insertUser(B, "2020-01-02T00:00:00");

        assertThat(repository.findAll(0, 4).users()).extracting(User::getId).containsExactly(A, B, C, D);
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

        assertThat(repository.findAll(0, 2).users()).extracting(User::getId).containsExactly(A, B);
        assertThat(repository.findAll(1, 2).users()).extracting(User::getId).containsExactly(C, D);
    }

    @Test
    void GivenMoreAccountsThanOnePage_WhenAFullPageIsRead_ThenItCarriesHowManyAccountsExistInAll() {
        // D2.13. The total comes with the page, from the one count the page runs. Compared with the
        // table's own count, because other integration tests commit accounts into the shared database.
        insertUser(A, "2020-01-01T00:00:00");
        insertUser(B, "2020-01-02T00:00:00");
        insertUser(C, "2020-01-03T00:00:00");

        UserPage firstPage = repository.findAll(0, 2);

        assertThat(firstPage.users()).hasSize(2);
        assertThat(firstPage.total()).isEqualTo(accountsInTheTable());
    }

    @Test
    void GivenMoreAccountsThanOnePage_WhenAFullPageIsRead_ThenItsTotalCostsOneCountStatement() {
        // D2.13. The total above is right with a second count beside the page's own too, so only the
        // statements that ran can tell the fix from its revert.
        insertUser(A, "2020-01-01T00:00:00");
        insertUser(B, "2020-01-02T00:00:00");
        insertUser(C, "2020-01-03T00:00:00");
        statistics().clear();

        repository.findAll(0, 2);

        assertThat(countStatementsRun()).isEqualTo(1);
    }

    @Test
    void GivenFewerAccountsThanOnePage_WhenTheFirstPageIsRead_ThenNoCountRunsAndTheTotalIsStillRight() {
        // A short first page holds every account, so it is its own total.
        long accounts = accountsInTheTable();
        statistics().clear();

        UserPage onlyPage = repository.findAll(0, Math.toIntExact(accounts + 1));

        assertThat(countStatementsRun()).isZero();
        assertThat(onlyPage.total()).isEqualTo(accounts);
    }

    @Test
    void GivenTheLastPageTheAdminListAdmits_WhenItIsRead_ThenItRunsAndComesBackEmpty() {
        // The bound AdminUserController sets, restated because the controller's constants are its own: the
        // largest page whose offset, page times size, still fits the int Spring Data hands the query, which
        // refuses one page more. AdminUserControllerTest pins the bound against a mocked query; only a real
        // one runs the offset.
        int largestPageSize = 100;
        int lastPage = Integer.MAX_VALUE / largestPageSize;

        UserPage beyondEveryAccount = repository.findAll(lastPage, largestPageSize);

        assertThat(beyondEveryAccount.users()).isEmpty();
    }

    /** The session factory's statistics, which this class's properties switch on. */
    private Statistics statistics() {
        return entityManager
                .getEntityManager()
                .getEntityManagerFactory()
                .unwrap(SessionFactory.class)
                .getStatistics();
    }

    /** How many {@code count} statements ran since the statistics were last cleared, whichever query ran them. */
    private long countStatementsRun() {
        Statistics statistics = statistics();
        return Arrays.stream(statistics.getQueries())
                .filter(query -> query.contains(COUNT_STATEMENT))
                .mapToLong(query -> statistics.getQueryStatistics(query).getExecutionCount())
                .sum();
    }

    private long accountsInTheTable() {
        return ((Number) entityManager
                        .getEntityManager()
                        .createNativeQuery("SELECT count(*) FROM users")
                        .getSingleResult())
                .longValue();
    }

    @Test
    void GivenOnlyDisabledAdministrators_WhenAnEnabledOneIsAskedFor_ThenThereIsNone() {
        // FR-45. Two administrators can disable each other, leaving rows that say ADMIN and an
        // installation nobody can administer. Counting them would stop the bootstrap from ever
        // recovering it, short of a database console.
        withNoAdministrators();
        entityManager.persistAndFlush(anAdministrator().enabled(false).build());

        assertThat(repository.existsEnabledWithRole(Role.ADMIN)).isFalse();
    }

    @Test
    void GivenAnEnabledAdministrator_WhenAnEnabledOneIsAskedFor_ThenThereIsOne() {
        withNoAdministrators();
        entityManager.persistAndFlush(anAdministrator().build());

        assertThat(repository.existsEnabledWithRole(Role.ADMIN)).isTrue();
    }

    /**
     * Demotes every administrator the shared database already holds, so the answer is about this
     * test's rows alone. The test's transaction rolls back, which restores them.
     */
    private void withNoAdministrators() {
        entityManager
                .getEntityManager()
                .createNativeQuery("UPDATE users SET role = 'USER' WHERE role = 'ADMIN'")
                .executeUpdate();
    }

    private static User.UserBuilder anAdministrator() {
        return AUser.aUser().id(null).email(UUID.randomUUID() + "@example.com").role(Role.ADMIN);
    }

    private void insertUser(UUID id, String createdAt) {
        entityManager
                .getEntityManager()
                .createNativeQuery("INSERT INTO users (id, name, email, password_hash, created_at) "
                        + "VALUES (CAST(?1 AS uuid), 'Someone', ?2, 'x', CAST(?3 AS timestamp))")
                .setParameter(1, id.toString())
                .setParameter(2, id + "@example.com")
                .setParameter(3, createdAt)
                .executeUpdate();
    }
}
