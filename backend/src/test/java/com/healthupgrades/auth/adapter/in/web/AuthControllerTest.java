package com.healthupgrades.auth.adapter.in.web;

import com.healthupgrades.auth.application.AuthResult;
import com.healthupgrades.auth.application.AuthService;
import com.healthupgrades.auth.application.RefreshOutcome;
import com.healthupgrades.auth.application.SessionGrant;
import com.healthupgrades.auth.application.port.in.SessionCommand;
import com.healthupgrades.common.domain.exception.BusinessRuleException;
import com.healthupgrades.common.security.JwtAuthenticationFilter;
import com.healthupgrades.common.security.BearerTokenAuthenticator;
import com.healthupgrades.common.security.SecurityConfig;
import com.healthupgrades.common.security.UserDetailsServiceImpl;
import com.healthupgrades.support.AUser;
import com.healthupgrades.support.WebSliceSupport;
import com.healthupgrades.user.domain.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import jakarta.servlet.http.Cookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.UUID;

import static com.healthupgrades.support.WebSliceSupport.bearer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of the three endpoints a session begins and resumes through: FR-1 (register),
 * FR-2 (login) and FR-3 (a stored token restores a session, looked up by the id the token names).
 *
 * <p>The response body is the one place a password could leak, so the shape assertions here are about
 * what is <em>absent</em> as much as what is present — {@code TokenPair} carries a {@code UserDto}, and
 * a {@code UserDto} has no password field at all (NFR-2).
 *
 * <p>Runs the real security chain. {@code /register} and {@code /login} must work anonymously and
 * {@code /me} must not, which is the distinction the blanket {@code /api/auth/**} rule used to lose.
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class,
        WebSliceSupport.class})
class AuthControllerTest {

    /** A grant whose credential is recognisable in an assertion about the cookie it lands in. */
    private static final SessionGrant GRANT = new SessionGrant(
            UUID.fromString("6b1f0f4c-9a2d-4c3e-9b7a-1d2e3f4a5b6c"),
            "6b1f0f4c-9a2d-4c3e-9b7a-1d2e3f4a5b6c.a-secret",
            WebSliceSupport.COOKIE_NOW.plus(Duration.ofDays(30)));

    private static final UUID USER_ID = UUID.fromString("0f2c8f5a-2a4e-4a1d-8f0a-3c5b9d1e77a1");

    /** The cookie the browser would be holding when it calls refresh or logout. */
    private static final Cookie PRESENTED = new Cookie("refresh_token", GRANT.refreshToken());

    @Autowired MockMvc mockMvc;

    @MockBean AuthService authService;
    @MockBean SessionCommand sessions;
    @MockBean BearerTokenAuthenticator authenticator;
    @MockBean UserDetailsServiceImpl userDetailsService;

    @Test
    void GivenValidRegistrationDetails_WhenAVisitorRegisters_ThenItAnswers201WithATokenAndTheirProfile()
            throws Exception {
        when(authService.register(anyString(), anyString(), anyString()))
                .thenReturn(new AuthResult("issued.jwt.token", aUser(), GRANT));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Someone\",\"email\":\"someone@example.com\",\"password\":\"s3cret!42\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("issued.jwt.token"))
                .andExpect(jsonPath("$.user.email").value(AUser.EMAIL))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.user.password").doesNotExist());
    }

    @Test
    void GivenAnEmailThatIsNotAnEmail_WhenAVisitorRegisters_ThenItAnswers400AndNobodyIsRegistered()
            throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Someone\",\"email\":\"not-an-email\",\"password\":\"s3cret!42\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").exists());

        verify(authService, never()).register(any(), any(), any());
    }

    @Test
    void GivenARegistrationWithNoPassword_WhenItIsSent_ThenItAnswers400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Someone\",\"email\":\"someone@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    void GivenAnEmptyPasswordViolatingTwoConstraints_WhenItIsSent_ThenTheSameMessageComesBackEveryTime()
            throws Exception {
        // "" fails both @NotBlank and @Size(min = 8), and the field map holds one message per field.
        // Hibernate Validator does not specify the order of getFieldErrors(), so without the sort in
        // GlobalExceptionHandler two identical requests could answer differently. Repeated because a
        // single call cannot tell a stable choice from a lucky one.
        for (int attempt = 0; attempt < 5; attempt++) {
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Someone\",\"email\":\"someone@example.com\",\"password\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.password").value("must not be blank"));
        }

        verify(authService, never()).register(any(), any(), any());
    }

    @Test
    void GivenAPasswordBelowTheMinimum_WhenAVisitorRegisters_ThenItAnswers400AndNobodyIsRegistered()
            throws Exception {
        // The browser has always asked for a minimum and the API never did, so any caller that was not
        // the frontend could register a one-character password. BR-17.
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Someone\",\"email\":\"someone@example.com\",\"password\":\"a\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").exists());

        verify(authService, never()).register(any(), any(), any());
    }

    @Test
    void GivenAPasswordAtTheMinimum_WhenAVisitorRegisters_ThenItIsAccepted() throws Exception {
        // The bound is inclusive, so the shortest allowed password must not be refused.
        when(authService.register(anyString(), anyString(), anyString()))
                .thenReturn(new AuthResult("issued.jwt.token", aUser(), GRANT));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Someone\",\"email\":\"someone@example.com\",\"password\":\"" + "x".repeat(AuthController.PASSWORD_MIN) + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void GivenAPasswordLongerThanBcryptHashes_WhenAVisitorRegisters_ThenItIsRefusedRatherThanTruncated()
            throws Exception {
        // BCryptPasswordEncoder in Spring Security 6.2 guards only against null: anything past 72 bytes
        // is silently dropped, so a 100-character password and its first 72 characters would be the
        // same password and the user would never be told. Refusing the input is the honest answer.
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Someone\",\"email\":\"someone@example.com\",\"password\":\""
                                + "p".repeat(AuthController.PASSWORD_MAX + 1) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").exists());

        verify(authService, never()).register(any(), any(), any());
    }

    @Test
    void GivenAnEmailAlreadyRegistered_WhenAVisitorRegisters_ThenItAnswers422() throws Exception {
        // FR-4 reaching the client. The request was well-formed and the rules refuse it, which is a 422
        // rather than a 400 — the form has nothing to correct.
        when(authService.register(anyString(), anyString(), anyString()))
                .thenThrow(new BusinessRuleException("That email is already registered"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Someone\",\"email\":\"someone@example.com\",\"password\":\"s3cret!42\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("That email is already registered"));
    }

    @Test
    void GivenAnEmailWithSpacesAndCapitals_WhenAVisitorRegisters_ThenItIsAcceptedAndPassedOnNormalised()
            throws Exception {
        // @Email refuses surrounding whitespace, so normalising after validation would turn an address a
        // user pasted with a trailing space into a 400. The request record normalises before it is
        // validated.
        when(authService.register(anyString(), anyString(), anyString()))
                .thenReturn(new AuthResult("issued.jwt.token", aUser(), GRANT));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Someone\",\"email\":\" SomeOne@Example.COM \",\"password\":\"s3cret!42\"}"))
                .andExpect(status().isCreated());

        verify(authService).register("Someone", AUser.EMAIL, "s3cret!42");
    }

    @Test
    void GivenAnEmailWithSpacesAndCapitals_WhenAUserLogsIn_ThenTheNormalisedAddressIsPresented() throws Exception {
        when(authService.login(anyString(), anyString()))
                .thenReturn(new AuthResult("issued.jwt.token", aUser(), GRANT));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"SOMEONE@example.com \",\"password\":\"s3cret!\"}"))
                .andExpect(status().isOk());

        verify(authService).login(AUser.EMAIL, "s3cret!");
    }

    @Test
    void GivenMatchingCredentials_WhenAUserLogsIn_ThenItAnswers200WithATokenAndTheirProfile() throws Exception {
        when(authService.login(anyString(), anyString()))
                .thenReturn(new AuthResult("issued.jwt.token", aUser(), GRANT));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"someone@example.com\",\"password\":\"s3cret!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("issued.jwt.token"))
                .andExpect(jsonPath("$.user.name").value(AUser.NAME));
    }

    @Test
    void GivenCredentialsThatDoNotMatch_WhenAUserLogsIn_ThenTheResponseDoesNotSayWhichHalfWasWrong()
            throws Exception {
        // Telling a caller that the email exists but the password is wrong is free information for
        // someone enumerating accounts.
        when(authService.login(anyString(), anyString()))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"someone@example.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("password"))));
    }

    @Test
    void GivenAStoredToken_WhenTheSessionIsRestored_ThenItAnswers200WithTheCallersOwnProfile() throws Exception {
        UUID userId = UUID.randomUUID();
        WebSliceSupport.authenticateAs(authenticator, userId);
        when(authService.getMe(userId)).thenReturn(aUser());

        mockMvc.perform(bearer(get("/api/auth/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(AUser.EMAIL))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void GivenNoToken_WhenTheSessionIsRestored_ThenItIsRefusedRatherThanAnswered() throws Exception {
        // The regression this class exists to hold: /me sat behind a blanket /api/auth/** permitAll and
        // answered 500 from a null principal instead of refusing the request.
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());

        verify(authService, never()).getMe(any());
    }

    @Test
    void GivenASuccessfulSignIn_WhenTheResponseIsRead_ThenTheCredentialIsInACookieScriptCannotTouch()
            throws Exception {
        // The split the whole design rests on: the short-lived access token goes in the body for the
        // client to hold in memory, and the long-lived credential goes somewhere script cannot read.
        // If this cookie ever loses HttpOnly, injected script can take a credential that outlives the
        // page it ran on.
        when(authService.login(anyString(), anyString()))
                .thenReturn(new AuthResult("issued.jwt.token", aUser(), GRANT));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"someone@example.com\",\"password\":\"s3cret!42\"}"))
                .andExpect(status().isOk())
                .andExpect(cookie().value("refresh_token", GRANT.refreshToken()))
                .andExpect(cookie().httpOnly("refresh_token", true))
                .andExpect(cookie().secure("refresh_token", true))
                .andExpect(cookie().path("refresh_token", "/api/auth"))
                .andExpect(cookie().maxAge("refresh_token", (int) Duration.ofDays(30).toSeconds()))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Strict")))
                .andExpect(jsonPath("$.token").value("issued.jwt.token"));
    }

    @Test
    void GivenTheCurrentCredential_WhenItIsRefreshed_ThenItAnswers200WithANewTokenAndANewCookie()
            throws Exception {
        SessionGrant rotated = new SessionGrant(GRANT.sessionId(),
                GRANT.sessionId() + ".a-newer-secret", GRANT.expiresAt());
        when(sessions.refresh(GRANT.refreshToken()))
                .thenReturn(new RefreshOutcome.Rotated(USER_ID, rotated));
        when(authService.continueSession(USER_ID, rotated))
                .thenReturn(new AuthResult("a.newer.token", aUser(), rotated));

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(PRESENTED)
                        .header(RefreshCookies.REQUESTED_WITH, "XMLHttpRequest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("a.newer.token"))
                .andExpect(cookie().value("refresh_token", rotated.refreshToken()));
    }

    @Test
    void GivenNoRequestedWithHeader_WhenARefreshIsAttempted_ThenItIsRefusedAs400AndNoSessionIsTouched()
            throws Exception {
        // The half of the CSRF defence that SameSite does not cover. A cross-site form post can never
        // set a header, so the absence of one is the refusal - and it happens before any session is
        // read, which is why the verify below matters as much as the status.
        mockMvc.perform(post("/api/auth/refresh").cookie(PRESENTED))
                .andExpect(status().isBadRequest());

        verify(sessions, never()).refresh(any());
    }

    @Test
    void GivenNoRequestedWithHeader_WhenASignOutIsAttempted_ThenItIsRefusedAs400() throws Exception {
        mockMvc.perform(post("/api/auth/logout").cookie(PRESENTED))
                .andExpect(status().isBadRequest());

        verify(sessions, never()).revoke(any());
    }

    @Test
    void GivenACredentialRotatedOutMomentsAgo_WhenItIsRefreshed_ThenItAnswers409AndLeavesTheCookieAlone()
            throws Exception {
        // Two tabs woke together. Answering 401 here would sign somebody out for having a second tab
        // open; 409 says try again, and the cookie the browser holds is still the right one.
        when(sessions.refresh(GRANT.refreshToken())).thenReturn(new RefreshOutcome.Stale());

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(PRESENTED)
                        .header(RefreshCookies.REQUESTED_WITH, "XMLHttpRequest"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, nullValue()));
    }

    @Test
    void GivenAnUnusableCredential_WhenItIsRefreshed_ThenItAnswers401AndClearsTheCookie() throws Exception {
        // Leaving a dead credential in the browser means it is sent with every later attempt, and the
        // client cannot clear it itself - the cookie is HttpOnly by design.
        when(sessions.refresh(GRANT.refreshToken())).thenReturn(new RefreshOutcome.Rejected());

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(PRESENTED)
                        .header(RefreshCookies.REQUESTED_WITH, "XMLHttpRequest"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(cookie().maxAge("refresh_token", 0))
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        allOf(containsString("HttpOnly"), not(containsString("a-secret")))));
    }

    @Test
    void GivenASignedInCaller_WhenTheySignOut_ThenItAnswers204AndExpiresTheCookie() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .cookie(PRESENTED)
                        .header(RefreshCookies.REQUESTED_WITH, "XMLHttpRequest"))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("refresh_token", 0));

        verify(sessions).revoke(GRANT.refreshToken());
    }

    @Test
    void GivenNoCookieAtAll_WhenASignOutIsAttempted_ThenItStillAnswers204() throws Exception {
        // Signing out is idempotent from the caller side. Saying the credential was already dead gives
        // it nothing to do differently, and would let an anonymous caller probe session ids.
        mockMvc.perform(post("/api/auth/logout")
                        .header(RefreshCookies.REQUESTED_WITH, "XMLHttpRequest"))
                .andExpect(status().isNoContent());

        verify(sessions, never()).revoke(any());
    }

    private static User aUser() {
        return AUser.aUser().build();
    }
}
