package com.healthupgrades.common.security;

import com.healthupgrades.common.domain.exception.AuthenticationRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Turns the security chain's two refusals into the API's ordinary error body.
 *
 * <p>Both happen inside the filter chain, before any controller, so {@code GlobalExceptionHandler}
 * would never see them. Spring Security's defaults answered them itself: an anonymous request got a
 * bodiless 403 from {@code Http403ForbiddenEntryPoint}, which a client could not tell apart from "this is
 * not yours" (#58). Handing each one to the MVC exception resolver instead means one class decides every
 * status and every body, with the trace id on it (NFR-7).
 *
 * <p>The entry point does not forward Spring's own exception. {@code GlobalExceptionHandler} maps only
 * the two credential failures among {@code AuthenticationException}s, on purpose, so anything else would
 * reach its catch-all as a 500. It forwards {@link AuthenticationRequiredException} instead, which says
 * only whether a token was presented.
 */
public class ErrorBodySecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final String BEARER_PREFIX = "Bearer ";

    private final HandlerExceptionResolver resolver;

    /**
     * @param resolver Spring MVC's composite resolver, the one that consults {@code @ControllerAdvice};
     *                 not {@code DefaultErrorAttributes}, which also implements the interface
     */
    public ErrorBodySecurityHandlers(HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * Answers a request that needs authentication and has none the server accepts.
     *
     * <p>Reaching here with a bearer header means the token was refused: the filter leaves a refused
     * token's request anonymous rather than failing it, so the header is the only trace of it left.
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        AuthenticationRequiredException refusal = authorization != null && authorization.startsWith(BEARER_PREFIX)
                ? AuthenticationRequiredException.rejectedToken()
                : AuthenticationRequiredException.noCredential();
        resolver.resolveException(request, response, null, refusal);
    }

    /** Answers an authenticated request that is not allowed what it asked for; mapped to 403 as is. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) {
        resolver.resolveException(request, response, null, accessDeniedException);
    }
}
