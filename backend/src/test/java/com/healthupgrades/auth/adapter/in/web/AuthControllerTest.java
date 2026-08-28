package com.healthupgrades.auth.adapter.in.web;

import com.healthupgrades.auth.application.AuthResult;
import com.healthupgrades.auth.application.AuthService;
import com.healthupgrades.common.domain.exception.BusinessRuleException;
import com.healthupgrades.common.security.JwtAuthenticationFilter;
import com.healthupgrades.common.security.JwtTokenProvider;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static com.healthupgrades.support.WebSliceSupport.bearer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of the three endpoints a session begins and resumes through: FR-1 (register),
 * FR-2 (login) and FR-3 (a stored token restores a session).
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

    @Autowired MockMvc mockMvc;

    @MockBean AuthService authService;
    @MockBean JwtTokenProvider tokenProvider;
    @MockBean UserDetailsServiceImpl userDetailsService;

    @Test
    void GivenValidRegistrationDetails_WhenAVisitorRegisters_ThenItAnswers201WithATokenAndTheirProfile()
            throws Exception {
        when(authService.register(anyString(), anyString(), anyString()))
                .thenReturn(new AuthResult("issued.jwt.token", aUser()));

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
                .thenReturn(new AuthResult("issued.jwt.token", aUser()));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Someone\",\"email\":\"someone@example.com\",\"password\":\"12345678\"}"))
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
                                + "p".repeat(73) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").exists());

        verify(authService, never()).register(any(), any(), any());
    }

    @Test
    void GivenAnEmailAlreadyRegistered_WhenAVisitorRegisters_ThenItAnswers422() throws Exception {
        // FR-4 reaching the client. The request was well-formed and the rules refuse it, which is a 422
        // rather than a 400 — the form has nothing to correct.
        when(authService.register(anyString(), anyString(), anyString()))
                .thenThrow(new BusinessRuleException("Email already registered: " + AUser.EMAIL));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Someone\",\"email\":\"someone@example.com\",\"password\":\"s3cret!42\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void GivenMatchingCredentials_WhenAUserLogsIn_ThenItAnswers200WithATokenAndTheirProfile() throws Exception {
        when(authService.login(anyString(), anyString()))
                .thenReturn(new AuthResult("issued.jwt.token", aUser()));

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
        WebSliceSupport.authenticateAs(tokenProvider, userDetailsService, UUID.randomUUID());
        when(authService.getMe(AUser.EMAIL)).thenReturn(aUser());

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
                .andExpect(status().isForbidden());

        verify(authService, never()).getMe(any());
    }

    private static User aUser() {
        return AUser.aUser().build();
    }
}
