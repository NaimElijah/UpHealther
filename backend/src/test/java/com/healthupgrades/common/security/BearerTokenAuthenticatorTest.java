package com.healthupgrades.common.security;

import com.healthupgrades.support.AUser;
import com.healthupgrades.user.application.port.in.UserQuery;
import com.healthupgrades.user.domain.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers NFR-5: the account behind a token is re-loaded on every request, so a deleted account stops
 * working on the next request rather than at token expiry.
 *
 * <p>This is the one place both transports turn a bearer token into a principal (the HTTP filter and the
 * STOMP interceptor), so the rules asserted here hold for both.
 */
@ExtendWith(MockitoExtension.class)
class BearerTokenAuthenticatorTest {

    private static final String TOKEN = "a.signed.token";

    @Mock JwtTokenProvider tokenProvider;
    @Mock UserQuery userQuery;

    private BearerTokenAuthenticator authenticator;

    private final User user = AUser.aUser().build();

    @BeforeEach
    void setUp() {
        authenticator = new BearerTokenAuthenticator(tokenProvider, userQuery);
    }

    @Test
    void GivenAVerifiedToken_WhenItIsAuthenticated_ThenThePrincipalIsTheAccountItNames() {
        when(tokenProvider.verify(TOKEN)).thenReturn(Optional.of(new VerifiedAccessToken(user.getId())));
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
        when(tokenProvider.verify(TOKEN)).thenReturn(Optional.of(new VerifiedAccessToken(user.getId())));
        when(userQuery.findById(user.getId())).thenReturn(Optional.of(user));

        assertThat(authenticator.authenticate(TOKEN))
                .hasValueSatisfying(principal -> assertThat(principal.getPassword()).isNull());
    }

    @Test
    void GivenATokenForADeletedAccount_WhenItIsAuthenticated_ThenNobodyIsAuthenticated() {
        // NFR-5, and the reason the token carries an id rather than the account itself.
        UUID deleted = UUID.randomUUID();
        when(tokenProvider.verify(TOKEN)).thenReturn(Optional.of(new VerifiedAccessToken(deleted)));
        when(userQuery.findById(deleted)).thenReturn(Optional.empty());

        assertThat(authenticator.authenticate(TOKEN)).isEmpty();
    }

    @Test
    void GivenATokenTheProviderRefuses_WhenItIsAuthenticated_ThenNoAccountIsLookedUp() {
        when(tokenProvider.verify(TOKEN)).thenReturn(Optional.empty());

        assertThat(authenticator.authenticate(TOKEN)).isEmpty();
        verify(userQuery, never()).findById(any());
    }
}
