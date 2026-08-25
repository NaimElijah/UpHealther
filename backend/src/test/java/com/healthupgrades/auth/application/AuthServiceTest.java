package com.healthupgrades.auth.application;

import com.healthupgrades.common.domain.exception.BusinessRuleException;
import com.healthupgrades.common.security.JwtTokenProvider;
import com.healthupgrades.support.AUser;
import com.healthupgrades.user.application.port.in.UserCommand;
import com.healthupgrades.user.application.port.in.UserQuery;
import com.healthupgrades.user.domain.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the two ways into an authenticated session and the profile read behind them:
 * FR-1 (register), FR-2 (login), FR-3 (restore a session from a stored token), FR-4 (an email may be
 * registered once) and NFR-2 (a raw password is never stored).
 *
 * <p>The {@link PasswordEncoder} is the real BCrypt one rather than a mock. It is a pure function with no
 * I/O, and NFR-2 is a claim about what actually lands in the stored hash — a stubbed encoder would let
 * this class assert that the service called <em>something</em>, which is not the requirement.
 *
 * <p>The {@link AuthenticationManager} <em>is</em> mocked: it is Spring Security's, verifying a password
 * against a store this test does not have, and the behaviour under test is what {@code AuthService} does
 * with its verdict.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String RAW_PASSWORD = "correct-horse-battery-staple";

    @Mock UserQuery userQuery;
    @Mock UserCommand userCommand;
    @Mock JwtTokenProvider tokenProvider;
    @Mock AuthenticationManager authenticationManager;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(userQuery, userCommand, passwordEncoder, tokenProvider, authenticationManager);
    }

    @Test
    void GivenAnUnregisteredEmail_WhenTheVisitorRegisters_ThenTheUserIsSavedAndATokenIssued() {
        when(userQuery.existsByEmail(AUser.EMAIL)).thenReturn(false);
        when(userCommand.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
        when(tokenProvider.generateToken(AUser.EMAIL)).thenReturn("issued.jwt.token");

        AuthResult result = service.register(AUser.NAME, AUser.EMAIL, RAW_PASSWORD);

        assertThat(result.token()).isEqualTo("issued.jwt.token");
        assertThat(result.user().getEmail()).isEqualTo(AUser.EMAIL);
        assertThat(result.user().getName()).isEqualTo(AUser.NAME);
    }

    @Test
    void GivenAnEmailAlreadyRegistered_WhenTheVisitorRegisters_ThenItIsRefusedAndNothingIsSaved() {
        when(userQuery.existsByEmail(AUser.EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> service.register(AUser.NAME, AUser.EMAIL, RAW_PASSWORD))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining(AUser.EMAIL);

        verify(userCommand, never()).save(any());
        verify(tokenProvider, never()).generateToken(any());
    }

    @Test
    void GivenARawPassword_WhenTheVisitorRegisters_ThenOnlyItsHashIsStored() {
        when(userQuery.existsByEmail(AUser.EMAIL)).thenReturn(false);
        when(userCommand.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        service.register(AUser.NAME, AUser.EMAIL, RAW_PASSWORD);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userCommand).save(saved.capture());
        assertThat(saved.getValue().getPasswordHash())
                .as("the raw password must not reach the row")
                .isNotEqualTo(RAW_PASSWORD);
        assertThat(passwordEncoder.matches(RAW_PASSWORD, saved.getValue().getPasswordHash()))
                .as("and the stored hash must still verify that password")
                .isTrue();
    }

    @Test
    void GivenMatchingCredentials_WhenTheUserLogsIn_ThenATokenIsIssuedForThem() {
        User user = AUser.aUser().build();
        when(authenticationManager.authenticate(any())).thenReturn(authenticated());
        when(userQuery.findByEmail(AUser.EMAIL)).thenReturn(Optional.of(user));
        when(tokenProvider.generateToken(AUser.EMAIL)).thenReturn("issued.jwt.token");

        AuthResult result = service.login(AUser.EMAIL, RAW_PASSWORD);

        assertThat(result.token()).isEqualTo("issued.jwt.token");
        assertThat(result.user()).isSameAs(user);
    }

    @Test
    void GivenCredentialsThatDoNotMatch_WhenTheUserLogsIn_ThenNoTokenIsIssued() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() -> service.login(AUser.EMAIL, "wrong"))
                .isInstanceOf(BadCredentialsException.class);

        verify(tokenProvider, never()).generateToken(any());
    }

    @Test
    void GivenTheAccountDisappearsAfterTheCredentialsMatch_WhenTheUserLogsIn_ThenNoTokenIsIssued() {
        // Only reachable if the account is deleted mid-request, but the alternative is a token issued
        // for a user that no longer exists.
        when(authenticationManager.authenticate(any())).thenReturn(authenticated());
        when(userQuery.findByEmail(AUser.EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(AUser.EMAIL, RAW_PASSWORD))
                .isInstanceOf(BusinessRuleException.class);

        verify(tokenProvider, never()).generateToken(any());
    }

    @Test
    void GivenAStoredToken_WhenTheSessionIsRestored_ThenTheProfileBehindItIsReturned() {
        User user = AUser.aUser().build();
        when(userQuery.findByEmail(AUser.EMAIL)).thenReturn(Optional.of(user));

        assertThat(service.getMe(AUser.EMAIL)).isSameAs(user);
    }

    @Test
    void GivenATokenForAnAccountThatNoLongerExists_WhenTheSessionIsRestored_ThenItIsRefused() {
        when(userQuery.findByEmail(AUser.EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMe(AUser.EMAIL))
                .isInstanceOf(BusinessRuleException.class);
    }

    private static Authentication authenticated() {
        return new UsernamePasswordAuthenticationToken(AUser.EMAIL, null, java.util.List.of());
    }
}
