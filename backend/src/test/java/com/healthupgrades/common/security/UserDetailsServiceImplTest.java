package com.healthupgrades.common.security;

import com.healthupgrades.support.AUser;
import com.healthupgrades.user.application.port.in.UserQuery;
import com.healthupgrades.user.domain.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

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
                .isInstanceOf(UsernameNotFoundException.class);
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
    void GivenAnyPrincipal_WhenItsAuthoritiesAreRead_ThenThereAreNone() {
        // No roles are modelled: authorization is "authenticated or not", and ownership is decided by
        // the user-scoped queries rather than by an authority. A role appearing here would mean the
        // authorization model changed.
        when(userQuery.findByEmail(AUser.EMAIL)).thenReturn(Optional.of(AUser.aUser().build()));

        assertThat(service.loadUserByUsername(AUser.EMAIL).getAuthorities()).isEmpty();
    }
}
