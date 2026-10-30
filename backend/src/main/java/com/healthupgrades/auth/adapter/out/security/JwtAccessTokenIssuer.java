package com.healthupgrades.auth.adapter.out.security;

import com.healthupgrades.auth.domain.port.out.AccessToken;
import com.healthupgrades.auth.domain.port.out.AccessTokenIssuerPort;
import com.healthupgrades.common.security.IssuedAccessToken;
import com.healthupgrades.common.security.JwtTokenProvider;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The shipped {@link AccessTokenIssuerPort}: a JWT from the kernel's {@link JwtTokenProvider}.
 *
 * <p>It delegates rather than signing, because the provider is also what verifies every token the HTTP
 * filter and the STOMP interceptor are handed. Claims, lifetime and key are decided in one class, so a
 * token this context issues is one the kernel accepts. It lives here and not in {@code common.security},
 * because {@code common} may not depend on {@code auth} (ADR-062).
 */
@Component
@RequiredArgsConstructor
public class JwtAccessTokenIssuer implements AccessTokenIssuerPort {

    private final JwtTokenProvider tokenProvider; // signs with the configured key, HS256

    /** {@inheritDoc} */
    @Override
    public AccessToken issue(UUID userId, UUID sessionId) {
        IssuedAccessToken issued = tokenProvider.issue(userId, sessionId);
        return new AccessToken(issued.value(), issued.expiresAt());
    }
}
