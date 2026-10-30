package com.healthupgrades.user.adapter.in.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.healthupgrades.common.domain.port.out.AccountCredentials;
import com.healthupgrades.support.AUser;
import com.healthupgrades.user.application.port.in.UserQuery;
import com.healthupgrades.user.domain.model.Role;
import com.healthupgrades.user.domain.model.User;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Covers the supplying half of the kernel's {@code AccountCredentialsPort}: what the user context hands
 * the security adapter for an account, and the address rule it applies before looking one up.
 *
 * <p>The role travels by name, and {@code hasRole('ADMIN')} matches that name under the {@code ROLE_}
 * prefix, so a role whose name changed in translation would lock every administrator out of
 * {@code /api/admin/**} (NFR-33).
 */
@ExtendWith(MockitoExtension.class)
class AccountCredentialsAdapterTest {

    @Mock
    UserQuery userQuery;

    @InjectMocks
    AccountCredentialsAdapter adapter;

    @Test
    void GivenAnExistingAccount_WhenItsCredentialsAreLoadedById_ThenTheyCarryItsIdentityAndPasswordHash() {
        User user = AUser.aUser().build();
        when(userQuery.findById(user.getId())).thenReturn(Optional.of(user));

        assertThat(adapter.findById(user.getId())).hasValueSatisfying(credentials -> {
            assertThat(credentials.id()).isEqualTo(user.getId());
            assertThat(credentials.email()).isEqualTo(user.getEmail());
            assertThat(credentials.passwordHash()).isEqualTo(user.getPasswordHash());
            assertThat(credentials.enabled()).isTrue();
        });
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void GivenAnAccountHoldingARole_WhenItsCredentialsAreLoaded_ThenTheRoleTravelsUnderItsOwnName(Role role) {
        User user = AUser.withRole(UUID.randomUUID(), role);
        when(userQuery.findById(user.getId())).thenReturn(Optional.of(user));

        assertThat(adapter.findById(user.getId()))
                .map(AccountCredentials::roleName)
                .contains(role.name());
    }

    @Test
    void GivenADisabledAccount_WhenItsCredentialsAreLoaded_ThenTheyReportItDisabled() {
        User disabled = AUser.aUser().build();
        disabled.disable();
        when(userQuery.findById(disabled.getId())).thenReturn(Optional.of(disabled));

        assertThat(adapter.findById(disabled.getId()))
                .map(AccountCredentials::enabled)
                .contains(false);
    }

    @Test
    void GivenAnUnknownId_WhenItsCredentialsAreLoaded_ThenNothingIsFound() {
        UUID unknown = UUID.randomUUID();
        when(userQuery.findById(unknown)).thenReturn(Optional.empty());

        assertThat(adapter.findById(unknown)).isEmpty();
    }

    @Test
    void GivenAMixedCaseEmail_WhenItsCredentialsAreLoaded_ThenTheNormalisedAddressIsLookedUp() {
        // Moved here from UserDetailsServiceImplTest with the rule it covers: the kernel passes the
        // address on as typed, and this context decides when two addresses are one account (FR-4).
        when(userQuery.findByEmail(AUser.EMAIL))
                .thenReturn(Optional.of(AUser.aUser().build()));

        assertThat(adapter.findByEmail(" Someone@EXAMPLE.com"))
                .map(AccountCredentials::email)
                .contains(AUser.EMAIL);
    }

    @Test
    void GivenAnUnknownEmail_WhenItsCredentialsAreLoaded_ThenNothingIsFound() {
        when(userQuery.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThat(adapter.findByEmail("nobody@example.com")).isEmpty();
    }
}
