package com.healthupgrades.common.security;

import com.healthupgrades.common.domain.exception.AuthenticationRequiredException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.servlet.HandlerExceptionResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The security chain's two refusals reach the same error body as every other failure (NFR-7), by being
 * handed to the MVC exception resolver rather than written here.
 *
 * <p>What is pinned is <em>what</em> gets handed over. Spring's own
 * {@code InsufficientAuthenticationException} must not be: {@code GlobalExceptionHandler} maps no
 * {@code AuthenticationException} but the two credential ones, so it would fall to the catch-all and
 * answer 500. The same contract over HTTP is asserted in {@code AuthenticatedBoundaryTest}.
 */
class ErrorBodySecurityHandlersTest {

    private final HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
    private final ErrorBodySecurityHandlers handlers = new ErrorBodySecurityHandlers(resolver);
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void GivenAnAnonymousRequest_WhenAuthenticationIsRequired_ThenANoCredentialRefusalIsResolved() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/upgrades");

        handlers.commence(request, response, new InsufficientAuthenticationException("anonymous"));

        AuthenticationRequiredException resolved = resolvedFor(request);
        assertThat(resolved.tokenPresented()).isFalse();
    }

    @Test
    void GivenARequestWithABearerToken_WhenAuthenticationIsRequired_ThenARejectedTokenRefusalIsResolved() {
        // The filter leaves a refused token anonymous, so reaching here with an Authorization header
        // means the token was presented and not accepted.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/upgrades");
        request.addHeader("Authorization", "Bearer expired.or.forged");

        handlers.commence(request, response, new InsufficientAuthenticationException("anonymous"));

        assertThat(resolvedFor(request).tokenPresented()).isTrue();
    }

    @Test
    void GivenAnAuthorizationFailure_WhenItIsHandled_ThenItIsResolvedUnchanged() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/users");
        AccessDeniedException denied = new AccessDeniedException("Access Denied");

        handlers.handle(request, response, denied);

        verify(resolver).resolveException(eq(request), eq(response), isNull(), eq(denied));
    }

    private AuthenticationRequiredException resolvedFor(MockHttpServletRequest request) {
        ArgumentCaptor<Exception> resolved = ArgumentCaptor.forClass(Exception.class);
        verify(resolver).resolveException(eq(request), eq(response), isNull(), resolved.capture());
        assertThat(resolved.getValue()).isInstanceOf(AuthenticationRequiredException.class);
        return (AuthenticationRequiredException) resolved.getValue();
    }
}
