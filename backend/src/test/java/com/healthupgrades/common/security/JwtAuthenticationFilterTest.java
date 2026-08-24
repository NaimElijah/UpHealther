package com.healthupgrades.common.security;

import com.healthupgrades.support.AUser;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers NFR-5: the user behind a token is re-loaded on every request, so a deleted account stops working
 * immediately rather than at token expiry.
 *
 * <p>Also pins the filter's less obvious contract — it never rejects. A request with no token, a
 * malformed header or an invalid token continues down the chain <em>anonymous</em>, and the authorization
 * rules decide. That is what lets the permitted endpoints work without a token, and getting it wrong
 * would either lock those endpoints or let an invalid token through.
 *
 * <p>The security context is cleared after each test: it lives in a {@code ThreadLocal} that outlives the
 * test method, so leaving it set would decide the outcome of the next one.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    private static final String TOKEN = "a.valid.token";

    @Mock JwtTokenProvider tokenProvider;
    @Mock UserDetailsServiceImpl userDetailsService;
    @Mock FilterChain chain;

    private JwtAuthenticationFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(tokenProvider, userDetailsService);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void GivenAValidBearerToken_WhenTheRequestIsFiltered_ThenTheUserBehindItIsAuthenticated() throws Exception {
        UUID userId = UUID.randomUUID();
        request.addHeader("Authorization", "Bearer " + TOKEN);
        when(tokenProvider.validateToken(TOKEN)).thenReturn(true);
        when(tokenProvider.extractEmail(TOKEN)).thenReturn(AUser.EMAIL);
        when(userDetailsService.loadUserByUsername(AUser.EMAIL)).thenReturn(AUser.principalFor(userId));

        filter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(((SecurityUser) auth.getPrincipal()).getId()).isEqualTo(userId);
        verify(chain).doFilter(request, response);
    }

    @Test
    void GivenAValidToken_WhenTheRequestIsFiltered_ThenTheUserIsLoadedFreshRatherThanTakenFromTheToken() throws Exception {
        // NFR-5. The token carries only an email, so the identity has to be re-read; this is what makes
        // a renamed or deleted account take effect on the next request instead of at expiry.
        request.addHeader("Authorization", "Bearer " + TOKEN);
        when(tokenProvider.validateToken(TOKEN)).thenReturn(true);
        when(tokenProvider.extractEmail(TOKEN)).thenReturn(AUser.EMAIL);
        when(userDetailsService.loadUserByUsername(AUser.EMAIL)).thenReturn(AUser.principalFor(UUID.randomUUID()));

        filter.doFilter(request, response, chain);

        verify(userDetailsService).loadUserByUsername(AUser.EMAIL);
    }

    @Test
    void GivenATokenForADeletedAccount_WhenTheRequestIsFiltered_ThenItDoesNotAuthenticate() throws Exception {
        // NFR-5 is met — the account stops working on the very next request. What this pins is *how*:
        // the lookup throws and the exception leaves the filter, so the chain never runs. Nothing
        // downstream converts it, because the filter chain sits outside the DispatcherServlet and so
        // outside GlobalExceptionHandler. A caller therefore sees a 500 where 401 would be right.
        // Asserted as it behaves, not as it should behave; see the note on this in the pull request.
        request.addHeader("Authorization", "Bearer " + TOKEN);
        when(tokenProvider.validateToken(TOKEN)).thenReturn(true);
        when(tokenProvider.extractEmail(TOKEN)).thenReturn(AUser.EMAIL);
        when(userDetailsService.loadUserByUsername(AUser.EMAIL))
                .thenThrow(new UsernameNotFoundException("User not found: " + AUser.EMAIL));

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(UsernameNotFoundException.class);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void GivenNoAuthorizationHeader_WhenTheRequestIsFiltered_ThenItContinuesAnonymously() throws Exception {
        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void GivenAnInvalidToken_WhenTheRequestIsFiltered_ThenItContinuesAnonymouslyRatherThanBeingRejected() throws Exception {
        request.addHeader("Authorization", "Bearer " + TOKEN);
        when(tokenProvider.validateToken(TOKEN)).thenReturn(false);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void GivenAnAuthorizationHeaderThatIsNotBearer_WhenTheRequestIsFiltered_ThenNoTokenIsRead() throws Exception {
        request.addHeader("Authorization", "Basic dXNlcjpwYXNzd29yZA==");

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(tokenProvider, never()).validateToken(org.mockito.ArgumentMatchers.any());
        verify(chain).doFilter(request, response);
    }
}
