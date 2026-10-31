package com.healthupgrades.user.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.healthupgrades.support.AUser;
import com.healthupgrades.support.PostgresIT;
import com.healthupgrades.user.domain.model.AccountModifiedConcurrentlyException;
import com.healthupgrades.user.domain.model.Role;
import com.healthupgrades.user.domain.model.User;
import com.healthupgrades.user.domain.port.out.UserRepositoryPort;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * NFR-34 against a lost update: a disable committed while another write to the same account is in
 * flight stays committed, and the write that was overtaken is refused rather than undoing it (#340, D6.5).
 *
 * <p>An account's row is written whole, so the write that commits last would put back every value its
 * writer loaded: a role change loaded before a disable would write {@code enabled = true} back, and the
 * disable would be silently undone while the audit trail still said "disabled". {@code User}'s
 * {@code @Version} refuses that write instead, the mechanism BR-14 already uses for upgrades. The adapter's
 * save flushes, so the refusal is raised by the save itself, as the {@code user} context's conflict, which
 * the API answers 409 and the audit trail records as a refusal (ADR-073).
 *
 * <p>The interleaving is forced in one thread rather than raced across two, so the outcome never depends
 * on timing: the outer transaction loads the account, an inner {@code REQUIRES_NEW} transaction disables
 * it and commits, and only then does the outer one change its copy and save it.
 *
 * <p>Both transactions are real, so the class opts out of {@code @DataJpaTest}'s rolled-back transaction, as
 * {@code AuthSessionRowLockIT} does. It deletes the account it creates.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(UserRepositoryAdapter.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserConcurrentWritesIT extends PostgresIT {

    private static final String ORIGINAL_NAME = "Original";
    private static final String NEW_NAME = "Renamed";

    @Autowired
    UserRepositoryPort repository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    DataSource dataSource;

    private TransactionTemplate outer;
    private TransactionTemplate inner;
    private UUID accountId;

    @BeforeEach
    void anEnabledAccount() {
        outer = new TransactionTemplate(transactionManager);
        inner = new TransactionTemplate(transactionManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        accountId = outer.execute(status -> repository
                .save(AUser.aUser()
                        .id(null)
                        .name(ORIGINAL_NAME)
                        .email(UUID.randomUUID() + "@example.com")
                        .build())
                .getId());
    }

    @AfterEach
    void deleteTheAccount() {
        new JdbcTemplate(dataSource).update("DELETE FROM users WHERE id = ?", accountId);
    }

    @Test
    void
            GivenAnAccountDisabledWhileANameChangeWasInFlight_WhenTheNameChangeIsSaved_ThenItIsRefusedAndTheAccountStaysDisabled() {
        assertThatThrownBy(() -> saveAfterAConcurrentDisable(account -> account.setName(NEW_NAME)))
                .isInstanceOf(AccountModifiedConcurrentlyException.class);

        Map<String, Object> row = storedRow();
        assertThat(row.get("enabled")).as("the disable committed first").isEqualTo(false);
        assertThat(row.get("name")).as("the overtaken name change did not land").isEqualTo(ORIGINAL_NAME);
    }

    @Test
    void
            GivenAnAccountDisabledWhileARoleChangeWasInFlight_WhenTheRoleChangeIsSaved_ThenItIsRefusedAndTheAccountStaysDisabled() {
        assertThatThrownBy(() -> saveAfterAConcurrentDisable(account -> account.changeRole(Role.ADMIN)))
                .isInstanceOf(AccountModifiedConcurrentlyException.class);

        Map<String, Object> row = storedRow();
        assertThat(row.get("enabled")).as("the disable committed first").isEqualTo(false);
        assertThat(row.get("role")).as("the overtaken role change did not land").isEqualTo(Role.USER.name());
    }

    @Test
    void GivenTwoWritesOneAfterTheOther_WhenEachLoadsTheAccountFresh_ThenBothLand() {
        // The version refuses only a write that was overtaken, not every second write.
        inner.executeWithoutResult(status -> {
            User account = repository.findById(accountId).orElseThrow();
            account.disable();
            repository.save(account);
        });
        inner.executeWithoutResult(status -> {
            User account = repository.findById(accountId).orElseThrow();
            account.changeRole(Role.ADMIN);
            repository.save(account);
        });

        Map<String, Object> row = storedRow();
        assertThat(row.get("enabled")).isEqualTo(false);
        assertThat(row.get("role")).isEqualTo(Role.ADMIN.name());
    }

    /**
     * Loads the account, lets a second transaction disable it and commit, then applies {@code change} to
     * the copy loaded before the disable and saves it.
     */
    private void saveAfterAConcurrentDisable(Consumer<User> change) {
        outer.executeWithoutResult(status -> {
            User loadedBeforeTheDisable = repository.findById(accountId).orElseThrow();

            inner.executeWithoutResult(nested -> {
                User account = repository.findById(accountId).orElseThrow();
                account.disable();
                repository.save(account);
            });

            change.accept(loadedBeforeTheDisable);
            repository.save(loadedBeforeTheDisable);
        });
    }

    private Map<String, Object> storedRow() {
        return new JdbcTemplate(dataSource)
                .queryForMap("SELECT name, role, enabled FROM users WHERE id = ?", accountId);
    }
}
