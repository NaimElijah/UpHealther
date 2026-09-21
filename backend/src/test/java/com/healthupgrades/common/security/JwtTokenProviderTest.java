package com.healthupgrades.common.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the access-token contract every authenticated request depends on: NFR-3 (the signing secret must
 * be at least 256 bits or the application refuses to start) and NFR-4 (a token expires).
 *
 * <p>Time comes from an injected {@link Clock}, so expiry is observed by verifying with a provider whose
 * clock is later than the issuer's rather than by waiting.
 *
 * <p>The forged tokens are built with JJWT directly, which is the point: each one is something an
 * attacker holding the library could produce, and the provider must refuse it even though it parses.
 */
class JwtTokenProviderTest {

    /** Exactly 64 ASCII characters: 512 bits, comfortably over the HS256 minimum. */
    private static final String STRONG_SECRET = "a-signing-secret-long-enough-for-hs256-at-least-256-bits-long!!!!";

    private static final String OTHER_SECRET = "a-completely-different-secret-also-long-enough-for-hs256-hmac!!!!";

    private static final Duration TTL = Duration.ofMinutes(15);
    private static final Duration SKEW = Duration.ofSeconds(30);
    private static final String ISSUER = "healthupgrades";
    private static final String AUDIENCE = "healthupgrades-web";

    private static final Instant ISSUED_AT = Instant.parse("2026-09-16T10:00:00Z");
    private static final UUID USER_ID = UUID.fromString("0f2c8f5a-2a4e-4a1d-8f0a-3c5b9d1e77a1");
    private static final UUID SESSION_ID = UUID.fromString("6b1f0f4c-9a2d-4c3e-9b7a-1d2e3f4a5b6c");

    private static JwtTokenProvider providerAt(Instant now) {
        return providerAt(now, STRONG_SECRET);
    }

    private static JwtTokenProvider providerAt(Instant now, String secret) {
        return new JwtTokenProvider(new JwtProperties(secret, TTL, ISSUER, AUDIENCE, SKEW),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void GivenASecretShorterThan256Bits_WhenTheProviderIsConstructed_ThenItRefusesToStart() {
        // The check that keeps a weak secret from silently weakening every token the application issues.
        assertThatThrownBy(() -> providerAt(ISSUED_AT, "too-short"))
                .isInstanceOf(WeakKeyException.class);
    }

    @Test
    void GivenAnIssuedToken_WhenItIsVerified_ThenItNamesTheUserByIdNotByEmail() {
        // The subject is the account's identifier. An email in the token is personal data sitting in
        // browser storage, and it is not even a stable key: an address can change, an id cannot.
        JwtTokenProvider provider = providerAt(ISSUED_AT);

        String token = provider.issue(USER_ID, SESSION_ID).value();

        assertThat(provider.verify(token)).hasValueSatisfying(verified ->
                assertThat(verified.userId()).isEqualTo(USER_ID));
    }

    @Test
    void GivenAnIssuedToken_WhenItIsVerified_ThenItNamesTheSessionItWasIssuedWithin() {
        // The sid claim is what makes signing out mean something: without it a token belongs to no
        // session, and there is nothing that revoking could invalidate.
        JwtTokenProvider provider = providerAt(ISSUED_AT);

        String token = provider.issue(USER_ID, SESSION_ID).value();

        assertThat(provider.verify(token)).hasValueSatisfying(verified ->
                assertThat(verified.sessionId()).isEqualTo(SESSION_ID));
    }

    @Test
    void GivenATokenWithNoSessionClaim_WhenItIsVerified_ThenItIsRejected() {
        // Every token minted before sessions existed looks like this. Refusing them is the intended
        // outcome of the change, not a casualty of it: users sign in once more and get a revocable one.
        String legacy = Jwts.builder()
                .subject(USER_ID.toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(ISSUED_AT))
                .expiration(Date.from(ISSUED_AT.plus(TTL)))
                .signWith(Keys.hmacShaKeyFor(STRONG_SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();

        assertThat(providerAt(ISSUED_AT).verify(legacy)).isEmpty();
    }

    @Test
    void GivenATokenWithNoSubjectClaim_WhenItIsVerified_ThenItIsRejectedRatherThanThrowing() {
        // UUID.fromString(null) throws NullPointerException, which the provider's catch does not cover.
        // Escaping verify() means escaping the filter too - which runs outside the DispatcherServlet, so
        // GlobalExceptionHandler never sees it and a malformed token becomes a 500 rather than a 401.
        String noSubject = Jwts.builder()
                .claim("sid", SESSION_ID.toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(ISSUED_AT))
                .expiration(Date.from(ISSUED_AT.plus(TTL)))
                .signWith(Keys.hmacShaKeyFor(STRONG_SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();

        assertThatCode(() -> assertThat(providerAt(ISSUED_AT).verify(noSubject)).isEmpty())
                .doesNotThrowAnyException();
    }

    @Test
    void GivenAnIssuedToken_WhenItsExpiryIsRead_ThenItIsTheConfiguredLifetimeAfterIssue() {
        assertThat(providerAt(ISSUED_AT).issue(USER_ID, SESSION_ID).expiresAt()).isEqualTo(ISSUED_AT.plus(TTL));
    }

    @Test
    void GivenATokenPastExpiryByMoreThanTheSkew_WhenItIsVerified_ThenItIsRejected() {
        String token = providerAt(ISSUED_AT).issue(USER_ID, SESSION_ID).value();

        assertThat(providerAt(ISSUED_AT.plus(TTL).plus(SKEW).plusSeconds(1)).verify(token)).isEmpty();
    }

    @Test
    void GivenATokenPastExpiryByLessThanTheSkew_WhenItIsVerified_ThenItIsStillAccepted() {
        // Two hosts rarely agree on the time to the second; the skew keeps a token issued by one from
        // being refused by the other at the boundary.
        String token = providerAt(ISSUED_AT).issue(USER_ID, SESSION_ID).value();

        assertThat(providerAt(ISSUED_AT.plus(TTL).plus(SKEW).minusSeconds(1)).verify(token)).isPresent();
    }

    @Test
    void GivenATokenSignedWithAnotherSecret_WhenItIsVerified_ThenItIsRejected() {
        String forged = providerAt(ISSUED_AT, OTHER_SECRET).issue(USER_ID, SESSION_ID).value();

        assertThat(providerAt(ISSUED_AT).verify(forged)).isEmpty();
    }

    @Test
    void GivenATamperedToken_WhenItIsVerified_ThenItIsRejected() {
        String token = providerAt(ISSUED_AT).issue(USER_ID, SESSION_ID).value();

        // Flip one character of the payload segment; the signature no longer covers it.
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 1)
                + (parts[1].endsWith("A") ? "B" : "A") + "." + parts[2];

        assertThat(providerAt(ISSUED_AT).verify(tampered)).isEmpty();
    }

    @Test
    void GivenATokenFromAnotherIssuer_WhenItIsVerified_ThenItIsRejected() {
        String foreign = signed(Jwts.SIG.HS256, "someone-else", AUDIENCE, USER_ID.toString());

        assertThat(providerAt(ISSUED_AT).verify(foreign)).isEmpty();
    }

    @Test
    void GivenATokenForAnotherAudience_WhenItIsVerified_ThenItIsRejected() {
        // A secret shared with another service would otherwise let that service's tokens in here.
        String foreign = signed(Jwts.SIG.HS256, ISSUER, "some-other-client", USER_ID.toString());

        assertThat(providerAt(ISSUED_AT).verify(foreign)).isEmpty();
    }

    @Test
    void GivenATokenSignedWithHs512UnderTheSameSecret_WhenItIsVerified_ThenItIsRejected() {
        // The algorithm is pinned. Accepting whatever the header names is how algorithm-confusion attacks
        // begin, so a token that is genuinely signed with our key under another algorithm is still out.
        String otherAlgorithm = signed(Jwts.SIG.HS512, ISSUER, AUDIENCE, USER_ID.toString());

        assertThat(providerAt(ISSUED_AT).verify(otherAlgorithm)).isEmpty();
    }

    @Test
    void GivenAnUnsignedToken_WhenItIsVerified_ThenItIsRejected() {
        String unsigned = Jwts.builder()
                .subject(USER_ID.toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(ISSUED_AT))
                .expiration(Date.from(ISSUED_AT.plus(TTL)))
                .compact();

        assertThat(providerAt(ISSUED_AT).verify(unsigned)).isEmpty();
    }

    @Test
    void GivenACorrectlySignedTokenWhoseSubjectIsNotAnId_WhenItIsVerified_ThenItIsRejectedRatherThanThrowing() {
        // A token issued before the subject became the user id carries an email. It must be refused like
        // any other unusable token, not escape the filter as an IllegalArgumentException.
        String legacy = signed(Jwts.SIG.HS256, ISSUER, AUDIENCE, "someone@example.com");

        assertThat(providerAt(ISSUED_AT).verify(legacy)).isEmpty();
    }

    @Test
    void GivenSomethingThatIsNotAToken_WhenItIsVerified_ThenItIsRejectedRatherThanThrowing() {
        // The filter verifies whatever a caller put in the header, so garbage must come back empty
        // rather than as an exception escaping the filter chain.
        JwtTokenProvider provider = providerAt(ISSUED_AT);

        assertThat(provider.verify("not-a-jwt")).isEmpty();
        assertThat(provider.verify("")).isEmpty();
    }

    private static String signed(io.jsonwebtoken.security.MacAlgorithm algorithm,
                                 String issuer, String audience, String subject) {
        return Jwts.builder()
                .subject(subject)
                .issuer(issuer)
                .audience().add(audience).and()
                .issuedAt(Date.from(ISSUED_AT))
                .expiration(Date.from(ISSUED_AT.plus(TTL)))
                .signWith(Keys.hmacShaKeyFor(STRONG_SECRET.getBytes(StandardCharsets.UTF_8)), algorithm)
                .compact();
    }
}
