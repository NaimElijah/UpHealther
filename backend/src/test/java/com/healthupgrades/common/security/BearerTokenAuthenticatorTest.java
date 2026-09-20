package com.healthupgrades.common.security;

import com.healthupgrades.common.domain.port.out.SessionStatusPort;
import com.healthupgrades.support.AUser;
import com.healthupgrades.user.application.port.in.UserQuery;
import com.healthupgrades.user.domain.model.Role;
import com.healthupgrades.user.domain.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers NFR-5: the account behind a token is re-loaded on every request, so a deleted account stops
 * working on the next request rather than at token expiry.
 *
 * <p>Covers NFR-34 for the same reason: an account an administrator has switched off stops working on
 * its next request, rather than carrying on until the token it already holds happens to lapse.
 *
 * <p>This is the one place both transports turn a bearer token into a principal (the HTTP filter and the
 * STOMP interceptor), so the rules asserted here hold for both.
 */
@ExtendWith(MockitoExtension.class)
class BearerTokenAuthenticatorTest {

    private static final String TOKEN = "a.signed.token";
    private static final UUID SESSION_ID = UUID.randomUUID();

    @Mock JwtTokenProvider tokenProvider;
    @Mock UserQuery userQuery;
    @Mock SessionStatusPort sessionStatus;

    private BearerTokenAuthenticator authenticator;

    private final User user = AUser.aUser().build();

    @BeforeEach
    void setUp() {
        authenticator = new BearerTokenAuthenticator(tokenProvider, userQuery, sessionStatus);
        // A live session is the precondition of every case below except the one about a dead
        // one, which overrides it. Lenient, so the tests that never reach the check - a token
        // the provider refuses outright - are not failed for an unused stub.
        lenient().when(sessionStatus.isActive(SESSION_ID)).thenReturn(true);
    }

    @Test
    void GivenAVerifiedToken_WhenItIsAuthenticated_ThenThePrincipalIsTheAccountItNames() {
        when(tokenProvider.verify(TOKEN)).thenReturn(Optional.of(new VerifiedAccessToken(user.getId(), SESSION_ID)));
        when(userQuery.findById(user.getId())).thenReturn(Optional.of(user));

        assertThat(authenticator.authenticate(TOKEN)).hasValueSatisfying(principal -> {
            assertThat(principal.getId()).isEqualTo(user.getId());
            assertThat(principal.getUsername()).isEqualTo(user.getEmail());
        });
    }

    @Test
    void GivenAVerifiedToken_WhenItIsAuthenticated_ThenThePrincipalCarriesNoPasswordHash() {
        // A bearer-authenticated request never checks a password, so the hash has no reason to ride
        // along in the security context for the rest of the request.
        when(tokenProvider.verify(TOKEN)).thenReturn(Optional.of(new VerifiedAccessToken(user.getId(), SESSION_ID)));
        when(userQuery.findById(user.getId())).thenReturn(Optional.of(user));

        assertThat(authenticator.authenticate(TOKEN))
                .hasValueSatisfying(principal -> assertThat(principal.getPassword()).isNull());
    }

    @Test
    void GivenATokenForADeletedAccount_WhenItIsAuthenticated_ThenNobodyIsAuthenticated() {
        // NFR-5, and the reason the token carries an id rather than the account itself.
        UUID deleted = UUID.randomUUID();
        when(tokenProvider.verify(TOKEN)).thenReturn(Optional.of(new VerifiedAccessToken(deleted, SESSION_ID)));
        when(userQuery.findById(deleted)).thenReturn(Optional.empty());

        assertThat(authenticator.authenticate(TOKEN)).isEmpty();
    }

    @Test
    void GivenATokenForADisabledAccount_WhenItIsAuthenticated_ThenNobodyIsAuthenticated() {
        // The token is still perfectly valid: it was issued before the account was switched off and
        // has not expired. Checking the flag on the row, on every request, is the whole difference
        // between disabled meaning now and disabled meaning within the token's remaining lifetime.
        User disabled = AUser.aUser().build();
        disabled.disable();
        when(tokenProvider.verify(TOKEN)).thenReturn(Optional.of(new VerifiedAccessToken(disabled.getId(), SESSION_ID)));
        when(userQuery.findById(disabled.getId())).thenReturn(Optional.of(disabled));

        assertThat(authenticator.authenticate(TOKEN)).isEmpty();
    }

    @Test
    void GivenATokenForAnAdministrator_WhenItIsAuthenticated_ThenThePrincipalCarriesTheAdminAuthority() {
        User admin = AUser.withRole(UUID.randomUUID(), Role.ADMIN);
        when(tokenProvider.verify(TOKEN)).thenReturn(Optional.of(new VerifiedAccessToken(admin.getId(), SESSION_ID)));
        when(userQuery.findById(admin.getId())).thenReturn(Optional.of(admin));

        assertThat(authenticator.authenticate(TOKEN))
                .hasValueSatisfying(principal -> assertThat(principal.getAuthorities())
                        .extracting(GrantedAuthority::getAuthority)
                        .containsExactly("ROLE_ADMIN"));
    }

    @Test
    void GivenATokenForAnEndedSession_WhenItIsAuthenticated_ThenNobodyIsAuthenticated() {
        // The token itself is perfectly valid and unexpired; its session has been signed out of.
        // Without this check signing out would be a suggestion until the token lapsed.
        when(tokenProvider.verify(TOKEN))
                .thenReturn(Optional.of(new VerifiedAccessToken(user.getId(), SESSION_ID)));
        when(sessionStatus.isActive(SESSION_ID)).thenReturn(false);

        assertThat(authenticator.authenticate(TOKEN)).isEmpty();
        verify(userQuery, never()).findById(any());
    }

    @Test
    void GivenATokenTheProviderRefuses_WhenItIsAuthenticated_ThenNoAccountIsLookedUp() {
        when(tokenProvider.verify(TOKEN)).thenReturn(Optional.empty());

        assertThat(authenticator.authenticate(TOKEN)).isEmpty();
        verify(userQuery, never()).findById(any());
    }
}
