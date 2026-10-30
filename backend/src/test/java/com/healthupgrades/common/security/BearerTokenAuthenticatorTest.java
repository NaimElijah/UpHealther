package com.healthupgrades.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.healthupgrades.common.domain.port.out.AccountCredentialsPort;
import com.healthupgrades.common.domain.port.out.SessionStatusPort;
import com.healthupgrades.support.AUser;
import com.healthupgrades.user.domain.model.Role;
import com.healthupgrades.user.domain.model.User;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;

/**
 * Covers NFR-5: the account behind a token is re-loaded on every request, so a deleted account stops
 * working on the next request rather than at token expiry, and the session the token names is checked
 * as a live session of that same account, so a token pairing one account with another's session is
 * nobody's.
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

    @Mock
    JwtTokenProvider tokenProvider;

    @Mock
    AccountCredentialsPort accounts;

    @Mock
    SessionStatusPort sessionStatus;

    private BearerTokenAuthenticator authenticator;

    private final User user = AUser.aUser().build();

    @BeforeEach
    void setUp() {
        authenticator = new BearerTokenAuthenticator(tokenProvider, accounts, sessionStatus);
        // A live session is the precondition of every case below except the ones about a dead or
        // a borrowed one. It is live for user alone, so a token naming any other account finds it
        // ended unless the test says whose else it is.
        givenALiveSessionOf(user.getId());
    }

    /**
     * Makes {@link #SESSION_ID} live for one account; asked about any other, the port answers false.
     * Lenient, so the tests that never reach the check - a token the provider refuses outright - are
     * not failed for an unused stub.
     */
    private void givenALiveSessionOf(UUID owner) {
        lenient().when(sessionStatus.isActive(SESSION_ID, owner)).thenReturn(true);
    }

    @Test
    void GivenAVerifiedToken_WhenItIsAuthenticated_ThenThePrincipalIsTheAccountItNames() {
        when(tokenProvider.verify(TOKEN)).thenReturn(Optional.of(new VerifiedAccessToken(user.getId(), SESSION_ID)));
        when(accounts.findById(user.getId())).thenReturn(Optional.of(AUser.credentialsFor(user)));

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
        when(accounts.findById(user.getId())).thenReturn(Optional.of(AUser.credentialsFor(user)));

        assertThat(authenticator.authenticate(TOKEN))
                .hasValueSatisfying(
                        principal -> assertThat(principal.getPassword()).isNull());
    }

    @Test
    void GivenAVerifiedToken_WhenItIsAuthenticatedWithItsSession_ThenTheSessionItNamesComesWithThePrincipal() {
        // The socket keeps the session it was opened in, so that ending the session can close it.
        when(tokenProvider.verify(TOKEN)).thenReturn(Optional.of(new VerifiedAccessToken(user.getId(), SESSION_ID)));
        when(accounts.findById(user.getId())).thenReturn(Optional.of(AUser.credentialsFor(user)));

        assertThat(authenticator.authenticateWithSession(TOKEN)).hasValueSatisfying(authenticated -> {
            assertThat(authenticated.user().getId()).isEqualTo(user.getId());
            assertThat(authenticated.sessionId()).isEqualTo(SESSION_ID);
        });
    }

    @Test
    void GivenATokenForAnEndedSession_WhenItIsAuthenticatedWithItsSession_ThenNobodyIsAuthenticated() {
        when(tokenProvider.verify(TOKEN)).thenReturn(Optional.of(new VerifiedAccessToken(user.getId(), SESSION_ID)));
        when(sessionStatus.isActive(SESSION_ID, user.getId())).thenReturn(false);

        assertThat(authenticator.authenticateWithSession(TOKEN)).isEmpty();
    }

    @Test
    void GivenATokenForADeletedAccount_WhenItIsAuthenticated_ThenNobodyIsAuthenticated() {
        // NFR-5, and the reason the token carries an id rather than the account itself.
        UUID deleted = UUID.randomUUID();
        givenALiveSessionOf(deleted);
        when(tokenProvider.verify(TOKEN)).thenReturn(Optional.of(new VerifiedAccessToken(deleted, SESSION_ID)));
        when(accounts.findById(deleted)).thenReturn(Optional.empty());

        assertThat(authenticator.authenticate(TOKEN)).isEmpty();
    }

    @Test
    void GivenATokenForADisabledAccount_WhenItIsAuthenticated_ThenNobodyIsAuthenticated() {
        // The token is still perfectly valid: it was issued before the account was switched off and
        // has not expired. Checking the flag on the row, on every request, is the whole difference
        // between disabled meaning now and disabled meaning within the token's remaining lifetime.
        User disabled = AUser.aUser().build();
        disabled.disable();
        givenALiveSessionOf(disabled.getId());
        when(tokenProvider.verify(TOKEN))
                .thenReturn(Optional.of(new VerifiedAccessToken(disabled.getId(), SESSION_ID)));
        when(accounts.findById(disabled.getId())).thenReturn(Optional.of(AUser.credentialsFor(disabled)));

        assertThat(authenticator.authenticate(TOKEN)).isEmpty();
    }

    @Test
    void GivenATokenForAnAdministrator_WhenItIsAuthenticated_ThenThePrincipalCarriesTheAdminAuthority() {
        User admin = AUser.withRole(UUID.randomUUID(), Role.ADMIN);
        givenALiveSessionOf(admin.getId());
        when(tokenProvider.verify(TOKEN)).thenReturn(Optional.of(new VerifiedAccessToken(admin.getId(), SESSION_ID)));
        when(accounts.findById(admin.getId())).thenReturn(Optional.of(AUser.credentialsFor(admin)));

        assertThat(authenticator.authenticate(TOKEN))
                .hasValueSatisfying(principal -> assertThat(principal.getAuthorities())
                        .extracting(GrantedAuthority::getAuthority)
                        .containsExactly("ROLE_ADMIN"));
    }

    @Test
    void GivenATokenForAnEndedSession_WhenItIsAuthenticated_ThenNobodyIsAuthenticated() {
        // The token itself is perfectly valid and unexpired; its session has been signed out of.
        // Without this check signing out would be a suggestion until the token lapsed.
        when(tokenProvider.verify(TOKEN)).thenReturn(Optional.of(new VerifiedAccessToken(user.getId(), SESSION_ID)));
        when(sessionStatus.isActive(SESSION_ID, user.getId())).thenReturn(false);

        assertThat(authenticator.authenticate(TOKEN)).isEmpty();
        verify(accounts, never()).findById(any());
    }

    @Test
    void GivenATokenWhoseSessionBelongsToAnotherAccount_WhenItIsAuthenticated_ThenNobodyIsAuthenticated() {
        // The session is live, but it is user's, and the token names somebody else. Only a leaked
        // signing key can produce that pair, and the key alone must not borrow a session (#286).
        UUID intruder = UUID.randomUUID();
        when(tokenProvider.verify(TOKEN)).thenReturn(Optional.of(new VerifiedAccessToken(intruder, SESSION_ID)));

        assertThat(authenticator.authenticate(TOKEN)).isEmpty();
        verify(accounts, never()).findById(any());
    }

    @Test
    void GivenATokenTheProviderRefuses_WhenItIsAuthenticated_ThenNoAccountIsLookedUp() {
        when(tokenProvider.verify(TOKEN)).thenReturn(Optional.empty());

        assertThat(authenticator.authenticate(TOKEN)).isEmpty();
        verify(accounts, never()).findById(any());
    }
}
