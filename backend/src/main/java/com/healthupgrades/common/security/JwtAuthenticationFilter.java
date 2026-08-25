package com.healthupgrades.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
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
 * instead — the two exist because the JWT arrives in a different place on each transport.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final UserDetailsServiceImpl userDetailsService;

    /**
     * Authenticates the request when it carries a usable token, then continues the chain either way.
     *
     * <p>A missing, invalid or unusable token is not rejected here: the request simply stays anonymous
     * and the authorization rules decide. That is what lets the permitted routes
     * ({@code /api/auth/register}, {@code /api/auth/login}, {@code /actuator/**} and the
     * {@code /ws/**} handshake) work without a token, while everything else is refused by the chain.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (StringUtils.hasText(token) && tokenProvider.validateToken(token)) {
            authenticate(request, tokenProvider.extractEmail(token));
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Populates the security context for the user the token names, or leaves the request anonymous when
     * that user no longer exists.
     *
     * <p>Re-loading on every request is what makes a deleted account stop working immediately rather
     * than at token expiry (NFR-5). Letting the resulting {@code UsernameNotFoundException} escape would
     * make it stop working with a 500: this filter runs outside the {@code DispatcherServlet}, so
     * {@code GlobalExceptionHandler} never sees it. A token naming a deleted account is an unusable
     * token, and unusable tokens leave the request anonymous — the same as every other kind.
     */
    private void authenticate(HttpServletRequest request, String email) {
        UserDetails userDetails;
        try {
            userDetails = userDetailsService.loadUserByUsername(email);
        } catch (UsernameNotFoundException ex) {
            return;
        }
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** Extracts the token from an {@code Authorization: Bearer <token>} header, or null if absent. */
    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }
}
