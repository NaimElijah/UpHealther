package com.healthupgrades.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.healthupgrades.auth.adapter.in.web.AuthController;
import com.healthupgrades.auth.adapter.in.web.RefreshCookies;
import com.healthupgrades.auth.application.AuthService;
import com.healthupgrades.auth.application.port.in.RefreshOutcome;
import com.healthupgrades.auth.application.port.in.SessionCommand;
import com.healthupgrades.common.security.BearerTokenAuthenticator;
import com.healthupgrades.common.security.JwtAuthenticationFilter;
import com.healthupgrades.common.security.SecurityConfig;
import com.healthupgrades.common.security.UserDetailsServiceImpl;
import com.healthupgrades.support.WebSliceSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * S5.A11 on the wire: refresh and sign-out, the two anonymous endpoints that act on the cookie alone, are
 * rate-limited per client address, by a limiter of their own.
 *
 * <p>Each attempt that reaches the session service writes an audit entry, so an unthrottled caller could
 * write the audit stream at whatever rate it liked. A refused attempt never reaches the service, which
 * is the only place either endpoint audits, so asserting that the service was not called is asserting
 * that nothing was audited.
 *
 * <p>The two limits are set apart here, three for sign-in and five for the session endpoints, so a test
 * can tell which bucket refused it. As in {@code RateLimitedSignInTest}, each test has its own address.
 */
@WebMvcTest(
        value = AuthController.class,
        properties = {
            "app.rate-limit.limit=" + RateLimitedSessionEndpointsTest.SIGN_IN_LIMIT,
            "app.session-rate-limit.limit=" + RateLimitedSessionEndpointsTest.SESSION_LIMIT
        })
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, WebSliceSupport.class})
class RateLimitedSessionEndpointsTest {

    static final int SIGN_IN_LIMIT = 3;
    static final int SESSION_LIMIT = 5;

    private static final String CREDENTIAL = "6b1f0f4c-9a2d-4c3e-9b7a-1d2e3f4a5b6c.a-secret";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MeterRegistry meterRegistry;

    @MockitoBean
    AuthService authService;

    @MockitoBean
    SessionCommand sessions;

    @MockitoBean
    BearerTokenAuthenticator authenticator;

    @MockitoBean
    UserDetailsServiceImpl userDetailsService;

    @BeforeEach
    void everyCredentialIsRefused() {
        when(sessions.refresh(anyString())).thenReturn(new RefreshOutcome.Rejected());
        when(authService.login(anyString(), anyString())).thenThrow(new BadCredentialsException("Bad credentials"));
    }

    @Test
    void GivenRepeatedRefreshes_WhenTheAllowanceIsSpent_ThenFurtherAttemptsAre429AndNeverAudited() throws Exception {
        String client = "203.0.113.30";
        for (int attempt = 0; attempt < SESSION_LIMIT; attempt++) {
            mockMvc.perform(refreshFrom(client)).andExpect(status().isUnauthorized());
        }

        mockMvc.perform(refreshFrom(client))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.path").value("/api/auth/refresh"));

        verify(sessions, times(SESSION_LIMIT)).refresh(anyString());
    }

    @Test
    void GivenARefreshOverTheAllowance_WhenItIsRefused_ThenTheRefusalIsCountedUnderTheRefreshPath() throws Exception {
        // The session interceptor draws its tag from the session paths. Built from any other list, every
        // refused refresh would be counted as "other", and the refusals would vanish from the endpoint's
        // own series.
        String client = "203.0.113.35";
        double before = refusalsCountedFor("/api/auth/refresh");
        for (int attempt = 0; attempt < SESSION_LIMIT; attempt++) {
            mockMvc.perform(refreshFrom(client));
        }

        mockMvc.perform(refreshFrom(client)).andExpect(status().isTooManyRequests());

        assertThat(refusalsCountedFor("/api/auth/refresh")).isEqualTo(before + 1);
    }

    @Test
    void GivenRepeatedSignOuts_WhenTheAllowanceIsSpent_ThenFurtherAttemptsAre429AndNeverAudited() throws Exception {
        String client = "203.0.113.31";
        for (int attempt = 0; attempt < SESSION_LIMIT; attempt++) {
            mockMvc.perform(signOutFrom(client)).andExpect(status().isNoContent());
        }

        mockMvc.perform(signOutFrom(client))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.path").value("/api/auth/logout"));

        verify(sessions, times(SESSION_LIMIT)).revoke(anyString());
    }

    @Test
    void GivenRefreshesAndSignOutsFromOneAddress_WhenTogetherTheySpendTheAllowance_ThenTheNextIsRefused()
            throws Exception {
        // One bucket for the pair: alternating between them buys no extra allowance.
        String client = "203.0.113.32";
        for (int attempt = 0; attempt < SESSION_LIMIT; attempt++) {
            mockMvc.perform(attempt % 2 == 0 ? refreshFrom(client) : signOutFrom(client));
        }

        mockMvc.perform(refreshFrom(client)).andExpect(status().isTooManyRequests());
    }

    @Test
    void GivenTheSignInAllowanceIsSpent_WhenTheSameAddressRefreshes_ThenTheRefreshIsNotRefused() throws Exception {
        // Separate buckets: a household that mistyped its password ten times must still be able to
        // renew the sessions it has, and a busy tab's renewals must not cost anyone a sign-in.
        String client = "203.0.113.33";
        for (int attempt = 0; attempt < SIGN_IN_LIMIT + 1; attempt++) {
            mockMvc.perform(signInFrom(client));
        }

        mockMvc.perform(refreshFrom(client)).andExpect(status().isUnauthorized());
    }

    @Test
    void GivenTheSessionAllowanceIsSpent_WhenTheSameAddressSignsIn_ThenTheSignInIsNotRefused() throws Exception {
        String client = "203.0.113.34";
        for (int attempt = 0; attempt < SESSION_LIMIT + 1; attempt++) {
            mockMvc.perform(refreshFrom(client));
        }

        mockMvc.perform(signInFrom(client)).andExpect(status().isUnauthorized());
    }

    private double refusalsCountedFor(String endpoint) {
        Counter counter = meterRegistry
                .find(RateLimitInterceptor.REFUSALS)
                .tag("endpoint", endpoint)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private static MockHttpServletRequestBuilder refreshFrom(String clientAddress) {
        return withCookie(post("/api/auth/refresh"), clientAddress);
    }

    private static MockHttpServletRequestBuilder signOutFrom(String clientAddress) {
        return withCookie(post("/api/auth/logout"), clientAddress);
    }

    private static MockHttpServletRequestBuilder withCookie(MockHttpServletRequestBuilder request, String address) {
        return request.cookie(new Cookie(WebSliceSupport.COOKIE_NAME, CREDENTIAL))
                .header(RefreshCookies.REQUESTED_WITH, "XMLHttpRequest")
                .with(from(address));
    }

    private static MockHttpServletRequestBuilder signInFrom(String clientAddress) {
        return post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"someone@example.com\",\"password\":\"s3cret!42\"}")
                .with(from(clientAddress));
    }

    /** Makes a request appear to come from a given client address, as the proxy would report it. */
    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
