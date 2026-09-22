package com.healthupgrades.auth.application;

import com.healthupgrades.auth.application.port.in.SessionCommand;
import com.healthupgrades.common.domain.exception.BusinessRuleException;
import com.healthupgrades.common.security.IssuedAccessToken;
import com.healthupgrades.common.security.JwtTokenProvider;
import com.healthupgrades.support.AUser;
import com.healthupgrades.support.RecordingAuditTrail;
import com.healthupgrades.user.application.port.in.UserCommand;
import com.healthupgrades.user.application.port.in.UserQuery;
import com.healthupgrades.user.domain.model.EmailAlreadyRegisteredException;
import com.healthupgrades.user.domain.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import com.healthupgrades.common.domain.audit.AuditAction;
import com.healthupgrades.common.domain.audit.AuditEvent;
import com.healthupgrades.common.domain.audit.AuditOutcome;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the two ways into an authenticated session and the profile read behind them:
 * FR-1 (register), FR-2 (login), FR-3 (read the profile a restored session belongs to), FR-4 (an email may be
 * registered once, compared case-insensitively) and NFR-2 (a raw password is never stored).
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
    private static final IssuedAccessToken ISSUED =
            new IssuedAccessToken("issued.jwt.token", Instant.parse("2026-09-16T10:15:00Z"));
    private static final SessionGrant GRANT = new SessionGrant(
            UUID.fromString("6b1f0f4c-9a2d-4c3e-9b7a-1d2e3f4a5b6c"),
            "6b1f0f4c-9a2d-4c3e-9b7a-1d2e3f4a5b6c.a-secret",
            Instant.parse("2026-10-16T10:15:00Z"));

    @Mock UserQuery userQuery;
    @Mock UserCommand userCommand;
    @Mock JwtTokenProvider tokenProvider;
    @Mock AuthenticationManager authenticationManager;
    @Mock SessionCommand sessions;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private AuthService service;
    /** A real implementation, not a mock: AuditTrail.recording is a default method. */
    private final RecordingAuditTrail auditTrail = new RecordingAuditTrail();

    @BeforeEach
    void setUp() {
        service = new AuthService(userQuery, userCommand, passwordEncoder, tokenProvider,
                authenticationManager, sessions, auditTrail);
        // Opening a session is a precondition of every successful sign-in here, and the subject
        // of none of them - AuthSessionServiceTest is where that behaviour is pinned. Lenient,
        // so the refusal tests are not failed for a stub they never reach.
        lenient().when(sessions.open(any(UUID.class))).thenReturn(GRANT);
    }

    @Test
    void GivenAnUnregisteredEmail_WhenTheVisitorRegisters_ThenTheUserIsSavedAndATokenIssued() {
        when(userQuery.existsByEmail(AUser.EMAIL)).thenReturn(false);
        when(userCommand.register(any(User.class))).thenAnswer(AuthServiceTest::persisted);
        when(tokenProvider.issue(any(UUID.class), any(UUID.class))).thenReturn(ISSUED);

        AuthResult result = service.register(AUser.NAME, AUser.EMAIL, RAW_PASSWORD);

        assertThat(result.token()).isEqualTo(ISSUED.value());
        assertThat(result.user().getEmail()).isEqualTo(AUser.EMAIL);
        assertThat(result.user().getName()).isEqualTo(AUser.NAME);
    }

    @Test
    void GivenAnEmailAlreadyRegistered_WhenTheVisitorRegisters_ThenItIsRefusedAndNothingIsSaved() {
        when(userQuery.existsByEmail(AUser.EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> service.register(AUser.NAME, AUser.EMAIL, RAW_PASSWORD))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("That email is already registered");

        verify(userCommand, never()).register(any());
        verify(tokenProvider, never()).issue(any(), any());
    }

    @Test
    void GivenAnEmailAlreadyTaken_WhenTheRefusalIsWorded_ThenTheAddressIsNotEchoedBack() {
        // The 422 body reaches whoever submitted the form. Quoting the address back adds nothing the
        // caller did not send, and turns every refusal into a line that repeats personal data.
        when(userQuery.existsByEmail(AUser.EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> service.register(AUser.NAME, AUser.EMAIL, RAW_PASSWORD))
                .hasMessageNotContaining(AUser.EMAIL);
    }

    @Test
    void GivenAMixedCaseEmailWithSpaces_WhenAVisitorRegisters_ThenItIsCheckedAndStoredTrimmedAndLowercase() {
        when(userQuery.existsByEmail(AUser.EMAIL)).thenReturn(false);
        when(userCommand.register(any(User.class))).thenAnswer(AuthServiceTest::persisted);
        when(tokenProvider.issue(any(UUID.class), any(UUID.class))).thenReturn(ISSUED);

        service.register(AUser.NAME, "  SomeOne@Example.com ", RAW_PASSWORD);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userCommand).register(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo(AUser.EMAIL);
    }

    @Test
    void GivenTheUniqueConstraintLosesARace_WhenAVisitorRegisters_ThenItIsRefusedAndAuditedAsRefused() {
        // Two registrations for one address can both pass the existence check. The loser used to be
        // decided at commit, outside this method, and came back as a 500.
        when(userQuery.existsByEmail(AUser.EMAIL)).thenReturn(false);
        when(userCommand.register(any(User.class))).thenThrow(new EmailAlreadyRegisteredException());

        assertThatThrownBy(() -> service.register(AUser.NAME, AUser.EMAIL, RAW_PASSWORD))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("That email is already registered");

        verify(tokenProvider, never()).issue(any(), any());
        assertThat(auditTrail.only(AuditAction.AUTH_REGISTER)).hasValue(
                new AuditEvent(AuditAction.AUTH_REGISTER, null, null, AuditOutcome.REFUSED));
    }

    @Test
    void GivenAMixedCaseEmail_WhenTheUserLogsIn_ThenTheNormalisedAddressIsAuthenticated() {
        User user = AUser.aUser().build();
        when(authenticationManager.authenticate(any())).thenReturn(authenticated());
        when(userQuery.findByEmail(AUser.EMAIL)).thenReturn(Optional.of(user));
        when(tokenProvider.issue(any(UUID.class), any(UUID.class))).thenReturn(ISSUED);

        service.login(" SOMEONE@example.com", RAW_PASSWORD);

        ArgumentCaptor<Authentication> presented = ArgumentCaptor.forClass(Authentication.class);
        verify(authenticationManager).authenticate(presented.capture());
        assertThat(presented.getValue().getPrincipal()).isEqualTo(AUser.EMAIL);
    }

    @Test
    void GivenARawPassword_WhenTheVisitorRegisters_ThenOnlyItsHashIsStored() {
        when(userQuery.existsByEmail(AUser.EMAIL)).thenReturn(false);
        when(userCommand.register(any(User.class))).thenAnswer(AuthServiceTest::persisted);
        when(tokenProvider.issue(any(UUID.class), any(UUID.class))).thenReturn(ISSUED);

        service.register(AUser.NAME, AUser.EMAIL, RAW_PASSWORD);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userCommand).register(saved.capture());
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
        when(tokenProvider.issue(any(UUID.class), any(UUID.class))).thenReturn(ISSUED);

        AuthResult result = service.login(AUser.EMAIL, RAW_PASSWORD);

        assertThat(result.token()).isEqualTo(ISSUED.value());
        assertThat(result.user()).isSameAs(user);
    }

    @Test
    void GivenCredentialsThatDoNotMatch_WhenTheUserLogsIn_ThenNoTokenIsIssued() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() -> service.login(AUser.EMAIL, "wrong"))
                .isInstanceOf(BadCredentialsException.class);

        verify(tokenProvider, never()).issue(any(), any());
    }

    @Test
    void GivenMatchingCredentials_WhenTheUserLogsIn_ThenTheSignInIsAuditedAgainstThem() {
        User user = AUser.aUser().build();
        when(authenticationManager.authenticate(any())).thenReturn(authenticated());
        when(userQuery.findByEmail(AUser.EMAIL)).thenReturn(Optional.of(user));
        when(tokenProvider.issue(any(UUID.class), any(UUID.class))).thenReturn(ISSUED);

        service.login(AUser.EMAIL, RAW_PASSWORD);

        assertThat(auditTrail.only(AuditAction.AUTH_LOGIN)).hasValue(
                new AuditEvent(AuditAction.AUTH_LOGIN, user.getId(), user.getId(), AuditOutcome.ALLOWED));
    }

    @Test
    void GivenCredentialsThatDoNotMatch_WhenTheUserLogsIn_ThenTheRefusalIsAuditedWithNoSubject() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() -> service.login(AUser.EMAIL, "wrong"))
                .isInstanceOf(BadCredentialsException.class);

        // Deliberately subject-less. The submitted email is personal data (NFR-6) and naming a user id
        // would confirm the account exists, which is what handleBadCredentials refuses to do on the
        // wire. What is left is a rate signal and a trace id — the honest limit of auditing an
        // anonymous endpoint, and the reason this is asserted rather than left to a reader's goodwill.
        assertThat(auditTrail.only(AuditAction.AUTH_LOGIN)).hasValue(
                new AuditEvent(AuditAction.AUTH_LOGIN, null, null, AuditOutcome.REFUSED));
    }

    @Test
    void GivenADatabaseOutageDuringLogin_WhenTheUserLogsIn_ThenItIsAuditedAsAFaultNotARefusal() {
        // Spring wraps an outage in an AuthenticationException too. Counting it as a refusal would make
        // "the database is down" indistinguishable from "somebody typed the wrong password".
        when(authenticationManager.authenticate(any()))
                .thenThrow(new AuthenticationServiceException("connection refused"));

        assertThatThrownBy(() -> service.login(AUser.EMAIL, RAW_PASSWORD))
                .isInstanceOf(AuthenticationServiceException.class);

        assertThat(auditTrail.recorded(AuditAction.AUTH_LOGIN, AuditOutcome.FAILED)).isTrue();
    }

    @Test
    void GivenTokenIssuingFails_WhenAVisitorRegisters_ThenTheAttemptIsAuditedOnceAndNotTwice() {
        // Recording ALLOWED before the token existed put one attempt in the trail under two outcomes -
        // allowed and failed - and double-counted it in audit.events to match. A trail that can report
        // one event twice, differently, is a trail nobody can total.
        when(userQuery.existsByEmail(AUser.EMAIL)).thenReturn(false);
        when(userCommand.register(any())).thenAnswer(AuthServiceTest::persisted);
        when(tokenProvider.issue(any(UUID.class), any(UUID.class))).thenThrow(new IllegalStateException("secret too short"));

        assertThatThrownBy(() -> service.register(AUser.NAME, AUser.EMAIL, RAW_PASSWORD))
                .isInstanceOf(IllegalStateException.class);

        assertThat(auditTrail.recorded())
                .filteredOn(event -> event.action() == AuditAction.AUTH_REGISTER)
                .singleElement()
                .satisfies(event -> assertThat(event.outcome()).isEqualTo(AuditOutcome.FAILED));
    }

    @Test
    void GivenTokenIssuingFails_WhenTheUserLogsIn_ThenTheAttemptIsAuditedOnceAndNotTwice() {
        when(authenticationManager.authenticate(any())).thenReturn(authenticated());
        when(userQuery.findByEmail(AUser.EMAIL)).thenReturn(Optional.of(AUser.aUser().build()));
        when(tokenProvider.issue(any(UUID.class), any(UUID.class))).thenThrow(new IllegalStateException("secret too short"));

        assertThatThrownBy(() -> service.login(AUser.EMAIL, RAW_PASSWORD))
                .isInstanceOf(IllegalStateException.class);

        assertThat(auditTrail.recorded())
                .filteredOn(event -> event.action() == AuditAction.AUTH_LOGIN)
                .singleElement()
                .satisfies(event -> assertThat(event.outcome()).isEqualTo(AuditOutcome.FAILED));
    }

    @Test
    void GivenAnEmailAlreadyTaken_WhenAVisitorRegisters_ThenTheRefusalIsAuditedWithNoSubject() {
        when(userQuery.existsByEmail(AUser.EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> service.register(AUser.NAME, AUser.EMAIL, RAW_PASSWORD))
                .isInstanceOf(BusinessRuleException.class);

        assertThat(auditTrail.only(AuditAction.AUTH_REGISTER)).hasValue(
                new AuditEvent(AuditAction.AUTH_REGISTER, null, null, AuditOutcome.REFUSED));
    }

    @Test
    void GivenTheAccountDisappearsAfterTheCredentialsMatch_WhenTheUserLogsIn_ThenNoTokenIsIssued() {
        // Only reachable if the account is deleted mid-request, but the alternative is a token issued
        // for a user that no longer exists.
        when(authenticationManager.authenticate(any())).thenReturn(authenticated());
        when(userQuery.findByEmail(AUser.EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(AUser.EMAIL, RAW_PASSWORD))
                .isInstanceOf(BusinessRuleException.class);

        verify(tokenProvider, never()).issue(any(), any());
    }

    @Test
    void GivenMatchingCredentials_WhenTheUserLogsIn_ThenTheTokenIsIssuedForTheirIdNotTheirEmail() {
        User user = AUser.aUser().build();
        when(authenticationManager.authenticate(any())).thenReturn(authenticated());
        when(userQuery.findByEmail(AUser.EMAIL)).thenReturn(Optional.of(user));
        when(tokenProvider.issue(user.getId(), GRANT.sessionId())).thenReturn(ISSUED);

        assertThat(service.login(AUser.EMAIL, RAW_PASSWORD).token()).isEqualTo(ISSUED.value());
    }

    @Test
    void GivenAStoredToken_WhenTheSessionIsRestored_ThenTheProfileBehindItIsReturned() {
        User user = AUser.aUser().build();
        when(userQuery.findById(user.getId())).thenReturn(Optional.of(user));

        assertThat(service.getMe(user.getId())).isSameAs(user);
    }

    @Test
    void GivenATokenForAnAccountThatNoLongerExists_WhenTheSessionIsRestored_ThenItIsRefused() {
        UUID gone = UUID.randomUUID();
        when(userQuery.findById(gone)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMe(gone))
                .isInstanceOf(BusinessRuleException.class);
    }

    /**
     * What {@code UserCommand.register} answers with: the user that was handed to it, now carrying
     * the id the database assigned on insert.
     *
     * <p>The service builds a user with no id, so answering with the bare argument would hand back a
     * null id and every token would be issued for one — a state no live registration reaches, and one
     * that would let a token-issuing assertion pass for the wrong reason. Setting the id on the same
     * instance is what Hibernate does, so a captured argument is still the user that was saved.
     */
    private static User persisted(InvocationOnMock call) {
        User registered = call.getArgument(0);
        registered.setId(UUID.randomUUID());
        return registered;
    }

    private static Authentication authenticated() {
        return new UsernamePasswordAuthenticationToken(AUser.EMAIL, null, java.util.List.of());
    }
}
