package com.healthupgrades.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.healthupgrades.auth.adapter.in.web.AuthController;
import com.healthupgrades.auth.application.AuthService;
import com.healthupgrades.auth.application.port.in.SessionCommand;
import com.healthupgrades.support.WebSliceSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The CORS policy {@code SecurityConfig} applies when {@code app.cors.allowed-origins} is {@code *}.
 *
 * <p>A wildcard admits every origin, so it must not admit credentials as well: the refresh token travels
 * as a cookie, and a credentialed wildcard would let a page on any origin call {@code /api/auth/refresh}
 * with the user's cookie and read the rotated token. {@link CorsPolicyTest} configures a single origin,
 * which never reaches this branch; this class is its own slice because the origins are read once, when
 * the context starts.
 */
@WebMvcTest(
        value = AuthController.class,
        properties = {"app.cors.allowed-origins=*", "app.rate-limit.limit=1000000"})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, WebSliceSupport.class})
class WildcardCorsPolicyTest {

    private static final String ANY_ORIGIN = "https://elsewhere.example.net";

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
    void GivenAWildcardOrigin_WhenAPreflightArrivesFromAnyOrigin_ThenItIsPermittedWithoutCredentials()
            throws Exception {
        mockMvc.perform(options("/api/auth/refresh")
                        .header(HttpHeaders.ORIGIN, ANY_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-Requested-With"))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }
}
