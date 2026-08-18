package com.healthupgrades.common.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Issues and verifies the HMAC-signed JWTs this API authenticates with.
 *
 * <p>The token carries the user's email as its subject and nothing else — no roles, no user id. Every
 * request therefore re-loads the user, which keeps a deleted or renamed account from staying usable for
 * the lifetime of an already-issued token.
 *
 * <p>The signing key is derived from {@code app.jwt.secret}. It must be at least 256 bits, or
 * {@link Keys#hmacShaKeyFor} rejects it at startup — deliberately, so a too-short secret fails the boot
 * rather than weakening every token silently.
 */
@Component
public class JwtTokenProvider {

    private final SecretKey secretKey;
    private final long expirationMs;

    /**
     * @param secret       HMAC signing secret, at least 256 bits ({@code app.jwt.secret})
     * @param expirationMs token lifetime in milliseconds ({@code app.jwt.expiration})
     * @throws io.jsonwebtoken.security.WeakKeyException if the secret is shorter than 256 bits
     */
    public JwtTokenProvider(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration}") long expirationMs) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    /**
     * Issues a signed token for the given user.
     *
     * @param email the user's email, stored as the token subject
     * @return a compact, signed JWT valid for the configured lifetime
     */
    public String generateToken(String email) {
        return Jwts.builder()
                .subject(email)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(secretKey)
                .compact();
    }

    /**
     * Reads the subject out of a token, verifying the signature first.
     *
     * @param token compact JWT
     * @return the email the token was issued for
     * @throws JwtException if the signature, structure or expiry does not check out — callers must
     *                      therefore call {@link #validateToken} first, or be prepared to handle it
     */
    public String extractEmail(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    /**
     * Reports whether a token is well-formed, correctly signed and unexpired.
     *
     * @param token candidate JWT, from an {@code Authorization} header or a STOMP header
     * @return true if the token can be trusted
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            // A rejected token is an expected outcome on a public endpoint, not a fault: the caller's
            // contract is a boolean, and the reason is withheld on purpose — telling an unauthenticated
            // caller whether a token expired or was forged is free information for an attacker.
            return false;
        }
    }
}
