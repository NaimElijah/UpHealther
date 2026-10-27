package com.healthupgrades.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.healthupgrades.common.domain.exception.AuthenticationRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.servlet.HandlerExceptionResolver;

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

    /** One call to the resolver, as the handlers made it. */
    private record Resolution(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {}

    private final List<Resolution> resolutions = new ArrayList<>();

    /** Records what it is handed; resolving it into a body is {@code GlobalExceptionHandler}'s business. */
    private final HandlerExceptionResolver resolver = (request, response, handler, ex) -> {
        resolutions.add(new Resolution(request, response, handler, ex));
        return null;
    };

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

        assertThat(resolutions).singleElement().isEqualTo(new Resolution(request, response, null, denied));
    }

    private AuthenticationRequiredException resolvedFor(MockHttpServletRequest request) {
        assertThat(resolutions).hasSize(1);
        Resolution resolution = resolutions.get(0);
        assertThat(resolution.request()).isSameAs(request);
        assertThat(resolution.response()).isSameAs(response);
        assertThat(resolution.handler()).isNull();
        assertThat(resolution.ex()).isInstanceOf(AuthenticationRequiredException.class);
        return (AuthenticationRequiredException) resolution.ex();
    }
}
