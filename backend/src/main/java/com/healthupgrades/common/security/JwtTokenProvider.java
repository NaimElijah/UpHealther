package com.healthupgrades.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and verifies the HMAC-signed access tokens this API authenticates with.
 *
 * <p>A token names its account by id ({@code sub}) and nothing else about it. Every request re-loads the
 * account, which is what lets a deleted account stop working on the next request instead of at expiry.
 *
 * <p>Verification is strict about everything the token claims about itself:
 * <ul>
 *   <li>the algorithm is pinned to HS256, so a token signed with our key under another algorithm is
 *       refused, and an unsigned token is refused by JJWT's signed-claims parser;</li>
 *   <li>{@code iss} and {@code aud} must be this application's, so a token minted by another service
 *       that happens to share the secret is not accepted here;</li>
 *   <li>{@code exp} is checked against the injected {@link Clock}, with a small configured skew.</li>
 * </ul>
 *
 * <p>The signing key is derived from {@code app.jwt.secret}. It must be at least 256 bits, or
 * {@link Keys#hmacShaKeyFor} rejects it at startup: a too-short secret fails the boot rather than
 * weakening every token silently (NFR-3).
 */
@Component
@Slf4j
public class JwtTokenProvider {

    private final SecretKey secretKey;
    private final Duration accessTokenTtl;
    private final String issuer;
    private final String audience;
    private final Clock clock;
    private final JwtParser parser;

    /**
     * @param properties the validated {@code app.jwt} settings
     * @param clock      the application clock; issue and expiry are both read from it
     * @throws io.jsonwebtoken.security.WeakKeyException if the secret is shorter than 256 bits
     */
    public JwtTokenProvider(JwtProperties properties, Clock clock) {
        this.secretKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtl = properties.accessTokenTtl();
        this.issuer = properties.issuer();
        this.audience = properties.audience();
        this.clock = clock;
        // Built once: the parser is immutable and thread-safe, and every request would otherwise rebuild it.
        this.parser = Jwts.parser()
                .verifyWith(secretKey)
                .sig().clear().add(Jwts.SIG.HS256).and()
                .requireIssuer(issuer)
                .requireAudience(audience)
                .clockSkewSeconds(properties.clockSkew().toSeconds())
                .clock(() -> Date.from(clock.instant()))
                .build();
    }

    /**
     * Issues an access token for an account.
     *
     * @param userId the account the token is for
     * @return the signed token and the instant it lapses
     */
    public IssuedAccessToken issue(UUID userId) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(accessTokenTtl);
        String token = Jwts.builder()
                .subject(userId.toString())
                .issuer(issuer)
                .audience().add(audience).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
        return new IssuedAccessToken(token, expiresAt);
    }

    /**
     * Verifies a token and reads the account it names.
     *
     * @param token candidate JWT, from an {@code Authorization} header or a STOMP header
     * @return the verified claims, or empty when the token is malformed, forged, expired, from another
     *         issuer or audience, signed with another algorithm, or names something that is not an id
     */
    public Optional<VerifiedAccessToken> verify(String token) {
        try {
            Claims claims = parser.parseSignedClaims(token).getPayload();
            return Optional.of(new VerifiedAccessToken(UUID.fromString(claims.getSubject())));
        } catch (JwtException | IllegalArgumentException rejected) {
            // A rejected token is an expected outcome on a public endpoint, not a fault, and the reason is
            // withheld from the caller on purpose: telling an unauthenticated caller whether a token
            // expired or was forged is free information for an attacker.
            //
            // Withheld from the caller, not from us. DEBUG, because an expired token is the most ordinary
            // thing that happens here and this would otherwise be a line per stale tab. The exception's
            // type distinguishes "expired" from "forged"; its message is not logged, because a JJWT
            // message can quote the malformed token back.
            log.debug("Rejected a token: {}", rejected.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
