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
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(UserRepositoryAdapter.class)
class UserPersistenceIT extends PostgresIT {

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
}
