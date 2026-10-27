package com.healthupgrades.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.healthupgrades.support.AUser;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Pins the filter's contract: it never rejects. A request with no token, a malformed header or an
 * unusable token continues down the chain <em>anonymous</em>, and the authorization rules decide. That is
 * what lets the permitted endpoints work without a token; getting it wrong would either lock those
 * endpoints or let an unusable token through.
 *
 * <p>Whether a token is usable is {@link BearerTokenAuthenticator}'s decision, covered by its own test.
 *
 * <p>The security context is cleared after each test: it lives in a {@code ThreadLocal} that outlives the
 * test method, so leaving it set would decide the outcome of the next one.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    private static final String TOKEN = "a.valid.token";

    @Mock
    BearerTokenAuthenticator authenticator;

    private MockFilterChain chain;
    private JwtAuthenticationFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(authenticator);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void GivenAUsableBearerToken_WhenTheRequestIsFiltered_ThenTheAccountBehindItIsAuthenticated() throws Exception {
        UUID userId = UUID.randomUUID();
        request.addHeader("Authorization", "Bearer " + TOKEN);
        when(authenticator.authenticate(TOKEN)).thenReturn(Optional.of(AUser.principalFor(userId)));

        filter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(((SecurityUser) auth.getPrincipal()).getId()).isEqualTo(userId);
        assertContinuedDownTheChain();
    }

    @Test
    void GivenAnUnusableToken_WhenTheRequestIsFiltered_ThenItContinuesAnonymouslyRatherThanBeingRejected()
            throws Exception {
        // Unusable covers invalid, expired and "names an account that is gone" alike. This filter runs
        // outside the DispatcherServlet, so throwing here would reach no handler and answer 500.
        request.addHeader("Authorization", "Bearer " + TOKEN);
        when(authenticator.authenticate(TOKEN)).thenReturn(Optional.empty());

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertContinuedDownTheChain();
    }

    @Test
    void GivenNoAuthorizationHeader_WhenTheRequestIsFiltered_ThenItContinuesAnonymously() throws Exception {
        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(authenticator, never()).authenticate(any());
        assertContinuedDownTheChain();
    }

    @Test
    void GivenAnAuthorizationHeaderThatIsNotBearer_WhenTheRequestIsFiltered_ThenNoTokenIsRead() throws Exception {
        request.addHeader("Authorization", "Basic dXNlcjpwYXNzd29yZA==");

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(authenticator, never()).authenticate(any());
        assertContinuedDownTheChain();
    }

    @Test
    void GivenAnEmptyBearerToken_WhenTheRequestIsFiltered_ThenNoTokenIsRead() throws Exception {
        request.addHeader("Authorization", "Bearer ");

        filter.doFilter(request, response, chain);

        verify(authenticator, never()).authenticate(any());
        assertContinuedDownTheChain();
    }

    /** The filter never answers a request itself: whatever it decided, the chain carries on with it. */
    private void assertContinuedDownTheChain() {
        assertThat(chain.getRequest())
                .as("the request continued down the chain")
                .isSameAs(request);
        assertThat(chain.getResponse()).isSameAs(response);
    }
}
