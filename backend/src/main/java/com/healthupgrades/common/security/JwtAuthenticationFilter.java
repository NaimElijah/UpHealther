package com.healthupgrades.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Populates the security context from a {@code Bearer} token on every HTTP request.
 *
 * <p>Registered before {@code UsernamePasswordAuthenticationFilter} by {@link SecurityConfig}. Its
 * WebSocket counterpart is {@code JwtChannelInterceptor}, which authenticates the STOMP CONNECT frame
 * instead; the two exist because the JWT arrives in a different place on each transport, and both
 * delegate the decision to {@link BearerTokenAuthenticator}.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final BearerTokenAuthenticator authenticator;

    /**
     * Authenticates the request when it carries a usable token, then continues the chain either way.
     *
     * <p>A missing or unusable token is not rejected here: the request simply stays anonymous and the
     * authorization rules decide. That is what lets the permitted routes work without a token, while
     * everything else is refused by the chain's entry point. Throwing instead would reach no handler,
     * since this filter runs outside the {@code DispatcherServlet}, and answer 500.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (StringUtils.hasText(token)) {
            authenticator.authenticate(token).ifPresent(principal -> {
                UsernamePasswordAuthenticationToken auth =
                        UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
        filterChain.doFilter(request, response);
    }

    /** Extracts the token from an {@code Authorization: Bearer <token>} header, or null if absent. */
    private String resolveToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(authorization) && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
