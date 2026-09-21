package com.healthupgrades.common.security;

import com.healthupgrades.support.AUser;
import com.healthupgrades.user.application.port.in.UserQuery;
import com.healthupgrades.user.domain.model.Role;
import com.healthupgrades.user.domain.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Covers the bridge between Spring Security and the user context: a domain {@link User} is wrapped in a
 * {@link SecurityUser}, which is what keeps the framework out of the domain model.
 *
 * <p>The id carried on the principal matters beyond identity — every controller threads
 * {@code principal.getId()} down as the owner id, so losing it here would silently break user scoping
 * (BR-15) on every endpoint at once.
 */
@ExtendWith(MockitoExtension.class)
class UserDetailsServiceImplTest {

    @Mock UserQuery userQuery;

    @InjectMocks UserDetailsServiceImpl service;

    @Test
    void GivenARegisteredEmail_WhenThePrincipalIsLoaded_ThenItCarriesTheUsersIdentity() {
        User user = AUser.aUser().build();
        when(userQuery.findByEmail(AUser.EMAIL)).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername(AUser.EMAIL);

        assertThat(details).isInstanceOf(SecurityUser.class);
        assertThat(((SecurityUser) details).getId()).isEqualTo(user.getId());
        assertThat(details.getUsername()).isEqualTo(user.getEmail());
        assertThat(details.getPassword()).isEqualTo(user.getPasswordHash());
    }

    @Test
    void GivenAnUnknownEmail_WhenThePrincipalIsLoaded_ThenItIsReportedAsNotFound() {
        when(userQuery.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("nobody@example.com"))
                .isInstanceOf(UsernameNotFoundException.class)
                .as("an exception message is one careless log line away from the log (NFR-6)")
                .hasMessageNotContaining("nobody@example.com");
    }

    @Test
    void GivenAMixedCaseEmail_WhenThePrincipalIsLoaded_ThenTheNormalisedAddressIsLookedUp() {
        when(userQuery.findByEmail(AUser.EMAIL)).thenReturn(Optional.of(AUser.aUser().build()));

        assertThat(service.loadUserByUsername(" Someone@EXAMPLE.com").getUsername()).isEqualTo(AUser.EMAIL);
    }

    @Test
    void GivenALoadedPrincipal_WhenItsCredentialsAreErased_ThenTheStoredHashIsGone() {
        // Spring Security calls this once authentication has completed. It is the reason the hash is on
        // the principal at all, and the reason SecurityUser implements CredentialsContainer.
        when(userQuery.findByEmail(AUser.EMAIL)).thenReturn(Optional.of(AUser.aUser().build()));

        SecurityUser principal = (SecurityUser) service.loadUserByUsername(AUser.EMAIL);
        principal.eraseCredentials();

        assertThat(principal.getPassword()).isNull();
    }

    @Test
    void GivenAnOrdinaryAccount_WhenItsAuthoritiesAreRead_ThenItHoldsOnlyTheUserRole() {
        // Exactly one authority, and it is the account's own role under Spring Security's prefix.
        // A second authority appearing here would mean the authorization model grew something that
        // is not a role, which is a decision for an ADR rather than a line of wiring.
        when(userQuery.findByEmail(AUser.EMAIL)).thenReturn(Optional.of(AUser.aUser().build()));

        assertThat(service.loadUserByUsername(AUser.EMAIL).getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    void GivenAnAdministrator_WhenItsAuthoritiesAreRead_ThenItHoldsTheAdminRole() {
        when(userQuery.findByEmail(AUser.EMAIL))
                .thenReturn(Optional.of(AUser.withRole(UUID.randomUUID(), Role.ADMIN)));

        assertThat(service.loadUserByUsername(AUser.EMAIL).getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void GivenADisabledAccount_WhenThePrincipalIsLoaded_ThenItReportsItselfDisabled() {
        // The principal carries the flag; SecurityConfig's post-authentication check is what acts on
        // it, and BearerTokenAuthenticator is what acts on it for a token.
        User disabled = AUser.aUser().build();
        disabled.disable();
        when(userQuery.findByEmail(AUser.EMAIL)).thenReturn(Optional.of(disabled));

        assertThat(service.loadUserByUsername(AUser.EMAIL).isEnabled()).isFalse();
    }
}
