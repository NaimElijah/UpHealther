package com.healthupgrades.common.security;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.healthupgrades.auth.adapter.in.web.AuthController;
import com.healthupgrades.auth.application.AuthService;
import com.healthupgrades.auth.application.port.in.SessionCommand;
import com.healthupgrades.common.observability.TraceIdResponseHeaderFilter;
import com.healthupgrades.support.WebSliceSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The CORS policy {@code SecurityConfig} applies to a frontend served from another origin.
 *
 * <p>The default deployment is same-origin and never meets it, which is why nothing else would notice
 * it break. The exposed headers are the part most easily lost: without them cross-origin JavaScript can
 * read a response but not its {@code X-Trace-Id}, the one thing a user can quote when reporting a
 * failure.
 */
@WebMvcTest(
        value = AuthController.class,
        properties = {"app.cors.allowed-origins=" + CorsPolicyTest.ALLOWED_ORIGIN, "app.rate-limit.limit=1000000"})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, WebSliceSupport.class})
class CorsPolicyTest {

    static final String ALLOWED_ORIGIN = "https://app.example.org";

    private static final String CREDENTIALS = "{\"email\":\"someone@example.com\",\"password\":\"s3cret!42\"}";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AuthService authService;

    @MockitoBean
    SessionCommand sessions;

    @MockitoBean
    BearerTokenAuthenticator authenticator;

    @MockitoBean
    UserDetailsServiceImpl userDetailsService;

    @Test
    void GivenAnAllowedOrigin_WhenAPreflightArrives_ThenTheCrossOriginRequestIsPermitted() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, HttpHeaders.CONTENT_TYPE))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void GivenAnOriginNotConfigured_WhenAPreflightArrives_ThenItIsRefused() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header(HttpHeaders.ORIGIN, "https://elsewhere.example.net")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void GivenAnAllowedOrigin_WhenAResponseArrives_ThenItsTraceIdAndRetryAfterAreReadableByScript() throws Exception {
        // A refused sign-in: exactly the response a user would want to quote the trace id of.
        when(authService.login(anyString(), anyString())).thenThrow(new BadCredentialsException("Bad credentials"));

        mockMvc.perform(post("/api/auth/login")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREDENTIALS))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(
                                HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                                containsString(TraceIdResponseHeaderFilter.TRACE_ID_HEADER)))
                .andExpect(header().string(
                                HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString(HttpHeaders.RETRY_AFTER)));
    }
}
