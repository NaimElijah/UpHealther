package com.healthupgrades.user.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The account state that authorisation reads: what the account may do, and whether it may authenticate
 * at all.
 *
 * <p>Both fields default rather than being named by the caller, and the defaults are the interesting
 * part. Registration sets neither, so every account ever created takes them — and the failure worth
 * pinning is the quiet one: an account that arrived with no role would be refused everywhere rather
 * than treated as ordinary, and an account that arrived neither enabled nor disabled could not sign in.
 *
 * <p>Neither field has a setter. They move only through the three transitions below, which is what
 * makes the places an account gains a privilege or loses its access countable.
 */
class UserTest {

    @Test
    void GivenNeitherFieldIsNamed_WhenAUserIsBuilt_ThenItIsAnOrdinaryEnabledAccount() {
        User user = aUser();

        assertThat(user.getRole())
                .as("a registration names no role, so the default is what every new account gets")
                .isEqualTo(Role.USER);
        assertThat(user.isEnabled())
                .as("an account nobody has disabled must be able to sign in")
                .isTrue();
    }

    @Test
    void GivenAnEnabledAccount_WhenItIsDisabled_ThenItCannotAuthenticateAndKeepsItsRole() {
        User user = aUser();
        user.changeRole(Role.ADMIN);

        user.disable();

        assertThat(user.isEnabled()).isFalse();
        assertThat(user.getRole())
                .as("disabling is not demotion: re-enabling must restore the account as it was")
                .isEqualTo(Role.ADMIN);
    }

    @Test
    void GivenADisabledAccount_WhenItIsEnabled_ThenItMayAuthenticateAgain() {
        User user = aUser();
        user.disable();

        user.enable();

        assertThat(user.isEnabled()).isTrue();
    }

    @Test
    void GivenAnOrdinaryAccount_WhenItIsPromoted_ThenItHoldsTheNewRole() {
        User user = aUser();

        user.changeRole(Role.ADMIN);

        assertThat(user.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void GivenAnAdministrator_WhenTheRoleIsRevoked_ThenItIsAnOrdinaryAccountAgain() {
        User user = aUser();
        user.changeRole(Role.ADMIN);

        user.changeRole(Role.USER);

        assertThat(user.getRole()).isEqualTo(Role.USER);
    }

    @Test
    void GivenNoRoleAtAll_WhenTheRoleIsChanged_ThenItIsRefused() {
        // The column refuses a null and the application could not read one back. Failing here names the
        // caller that tried it; failing at the flush names a transaction.
        assertThatThrownBy(() -> aUser().changeRole(null))
                .isInstanceOf(NullPointerException.class);
    }

    private static User aUser() {
        return User.builder()
                .name("Someone")
                .email("someone@example.com")
                .passwordHash("$2a$10$abcdefghijklmnopqrstuvwxyz012345678901234567890123456")
                .build();
    }
}
