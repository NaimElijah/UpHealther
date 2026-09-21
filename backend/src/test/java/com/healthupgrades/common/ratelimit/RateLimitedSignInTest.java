package com.healthupgrades.common.ratelimit;

import com.healthupgrades.auth.adapter.in.web.AuthController;
import com.healthupgrades.auth.application.AuthService;
import com.healthupgrades.auth.application.port.in.SessionCommand;
import com.healthupgrades.common.security.BearerTokenAuthenticator;
import com.healthupgrades.common.security.JwtAuthenticationFilter;
import com.healthupgrades.common.security.SecurityConfig;
import com.healthupgrades.common.security.UserDetailsServiceImpl;
import com.healthupgrades.support.WebSliceSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #56 on the wire: repeated sign-in attempts from one address are refused with 429.
 *
 * <p>{@code FixedWindowRateLimiterTest} owns the counting. This class owns the three things only an
 * assembled dispatcher can show:
 *
 * <ul>
 *   <li>the interceptor is actually <strong>registered against these paths</strong> — a limiter nothing
 *       calls is the easiest possible way for this feature to be silently absent;</li>
 *   <li>it runs <strong>before the controller</strong>, so a refused attempt never reaches the
 *       authentication manager and costs no BCrypt — which is half the point of having it;</li>
 *   <li>the refusal carries <strong>{@code Retry-After}</strong> and the API's own error body.</li>
 * </ul>
 *
 * <p>The limit is set to three here rather than raised out of the way as every other slice does, which
 * is the one thing making this test possible at all.
 *
 * <p><strong>Each test uses its own client address.</strong> The limiter is one bean for the whole
 * class, so a shared address would carry an allowance from one test into the next and the suite would
 * pass or fail on method order. Distinct addresses are also closer to the truth: this is a per-client
 * limit, and the isolation is itself worth asserting.
 */
@WebMvcTest(value = AuthController.class, properties = "app.rate-limit.limit=3")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, WebSliceSupport.class})
class RateLimitedSignInTest {

    /** How many attempts the property above permits. */
    private static final int LIMIT = 3;

    private static final String CREDENTIALS =
            "{\"email\":\"someone@example.com\",\"password\":\"s3cret!42\"}";

    @Autowired MockMvc mockMvc;

    @MockBean AuthService authService;
    @MockBean SessionCommand sessions;
    @MockBean BearerTokenAuthenticator authenticator;
    @MockBean UserDetailsServiceImpl userDetailsService;

    @BeforeEach
    void credentialsNeverMatch() {
        when(authService.login(anyString(), anyString()))
                .thenThrow(new BadCredentialsException("Bad credentials"));
    }

    @Test
    void GivenRepeatedWrongPasswords_WhenTheAllowanceIsSpent_ThenFurtherAttemptsAreRefusedWith429()
            throws Exception {
        String client = "203.0.113.10";

        // Every permitted attempt is answered by the application itself: 401, wrong password.
        for (int attempt = 0; attempt < LIMIT; attempt++) {
            mockMvc.perform(signInFrom(client)).andExpect(status().isUnauthorized());
        }

        mockMvc.perform(signInFrom(client))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message").value("Too many attempts. Please try again in a moment."))
                .andExpect(jsonPath("$.path").value("/api/auth/login"));
    }

    @Test
    void GivenTheAllowanceIsSpent_WhenAnotherAttemptArrives_ThenItNeverReachesTheApplication()
            throws Exception {
        // The refusal happens in the interceptor, before the controller and before any password is
        // checked. If it did not, a rate limit would still cost one BCrypt comparison per attempt —
        // which is precisely the expense an attacker is trying to impose.
        String client = "203.0.113.11";
        for (int attempt = 0; attempt < LIMIT; attempt++) {
            mockMvc.perform(signInFrom(client));
        }

        mockMvc.perform(signInFrom(client)).andExpect(status().isTooManyRequests());

        verify(authService, times(LIMIT)).login(anyString(), anyString());
    }

    @Test
    void GivenOneClientHasSpentItsAllowance_WhenAnotherClientSignsIn_ThenItIsUnaffected() throws Exception {
        // A shared counter would let one attacker lock every other user out of the installation, which
        // is a better attack than the one being prevented.
        String attacker = "203.0.113.12";
        for (int attempt = 0; attempt < LIMIT + 1; attempt++) {
            mockMvc.perform(signInFrom(attacker));
        }

        mockMvc.perform(signInFrom("203.0.113.13")).andExpect(status().isUnauthorized());
    }

    @Test
    void GivenTheRetryAfterHeader_WhenARefusalIsRead_ThenItIsAWholeNumberOfSecondsAndNotZero()
            throws Exception {
        // Retry-After: 0 invites an immediate retry that is refused again, so a client honouring it
        // would spin. The value is rounded up for that reason.
        String client = "203.0.113.14";
        for (int attempt = 0; attempt < LIMIT; attempt++) {
            mockMvc.perform(signInFrom(client));
        }

        String retryAfter = mockMvc.perform(signInFrom(client))
                .andExpect(status().isTooManyRequests())
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.RETRY_AFTER);

        assertThat(retryAfter).isNotNull();
        assertThat(Long.parseLong(retryAfter)).isPositive();
    }

    @Test
    void GivenAnAuthenticatedEndpoint_WhenItIsCalledRepeatedly_ThenNoRateLimitApplies() throws Exception {
        // The limit is registered against the two anonymous paths only. Everything else needs a token,
        // which is its own limit on how fast anybody can try — and rate-limiting the whole API would
        // throttle an ordinary session.
        String client = "203.0.113.15";
        for (int attempt = 0; attempt < LIMIT + 3; attempt++) {
            mockMvc.perform(post("/api/auth/me").with(from(client)))
                    .andExpect(status().is(not(429)));
        }
    }

    /** Asserts a status is anything but the given one, without caring which. */
    private static org.hamcrest.Matcher<Integer> not(int status) {
        return org.hamcrest.Matchers.not(org.hamcrest.Matchers.is(status));
    }

    private static MockHttpServletRequestBuilder signInFrom(String clientAddress) {
        return post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(CREDENTIALS)
                .with(from(clientAddress));
    }

    /** Makes a request appear to come from a given client address, as the proxy would report it. */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
