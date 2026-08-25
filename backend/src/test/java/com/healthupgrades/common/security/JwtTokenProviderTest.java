package com.healthupgrades.common.security;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the token contract every authenticated request depends on: NFR-3 (the signing secret must be at
 * least 256 bits or the application refuses to start) and NFR-4 (a token expires and is not refreshable).
 *
 * <p>Expiry is exercised by constructing a provider whose lifetime has already elapsed rather than by
 * waiting. {@code JwtTokenProvider} stamps {@code exp} from {@code System.currentTimeMillis()} and takes
 * no {@code Clock}, so a negative lifetime is the only way to observe an expired token without a
 * {@code sleep} — and a sleeping test is a slow test that eventually flakes.
 */
class JwtTokenProviderTest {

    /** Exactly 64 ASCII characters — 512 bits, comfortably over the HS256 minimum. */
    private static final String STRONG_SECRET = "a-signing-secret-long-enough-for-hs256-at-least-256-bits-long!!!!";

    private static final String OTHER_SECRET = "a-completely-different-secret-also-long-enough-for-hs256-hmac!!!!";

    private static final long ONE_DAY_MS = 86_400_000L;
    private static final String EMAIL = "someone@example.com";

    @Test
    void GivenASecretShorterThan256Bits_WhenTheProviderIsConstructed_ThenItRefusesToStart() {
        // The check that keeps a weak secret from silently weakening every token the application issues.
        assertThatThrownBy(() -> new JwtTokenProvider("too-short", ONE_DAY_MS))
                .isInstanceOf(WeakKeyException.class);
    }

    @Test
    void GivenAValidSecret_WhenATokenIsIssued_ThenItCarriesTheEmailAsItsSubject() {
        JwtTokenProvider provider = new JwtTokenProvider(STRONG_SECRET, ONE_DAY_MS);

        String token = provider.generateToken(EMAIL);

        assertThat(provider.validateToken(token)).isTrue();
        assertThat(provider.extractEmail(token)).isEqualTo(EMAIL);
    }

    @Test
    void GivenATokenWhoseLifetimeHasElapsed_WhenItIsValidated_ThenItIsRejected() {
        JwtTokenProvider expiringImmediately = new JwtTokenProvider(STRONG_SECRET, -1_000L);

        String token = expiringImmediately.generateToken(EMAIL);

        assertThat(expiringImmediately.validateToken(token))
                .as("a token is not refreshable, so an elapsed one must stop working")
                .isFalse();
    }

    @Test
    void GivenATokenSignedWithAnotherSecret_WhenItIsValidated_ThenItIsRejected() {
        String forged = new JwtTokenProvider(OTHER_SECRET, ONE_DAY_MS).generateToken(EMAIL);

        assertThat(new JwtTokenProvider(STRONG_SECRET, ONE_DAY_MS).validateToken(forged)).isFalse();
    }

    @Test
    void GivenATamperedToken_WhenItIsValidated_ThenItIsRejected() {
        JwtTokenProvider provider = new JwtTokenProvider(STRONG_SECRET, ONE_DAY_MS);
        String token = provider.generateToken(EMAIL);

        // Flip one character of the payload segment; the signature no longer covers it.
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 1)
                + (parts[1].endsWith("A") ? "B" : "A") + "." + parts[2];

        assertThat(provider.validateToken(tampered)).isFalse();
    }

    @Test
    void GivenSomethingThatIsNotAToken_WhenItIsValidated_ThenItIsRejectedRatherThanThrowing() {
        JwtTokenProvider provider = new JwtTokenProvider(STRONG_SECRET, ONE_DAY_MS);

        // The filter calls validateToken on whatever a caller put in the header, so garbage must come
        // back as false rather than as an exception escaping the filter chain.
        assertThat(provider.validateToken("not-a-jwt")).isFalse();
        assertThat(provider.validateToken("")).isFalse();
    }

    @Test
    void GivenARejectedToken_WhenItsSubjectIsRead_ThenTheReadThrowsRatherThanReturningIt() {
        // extractEmail verifies the signature itself, so a caller that skips validateToken still cannot
        // read a subject out of an untrusted token.
        JwtTokenProvider provider = new JwtTokenProvider(STRONG_SECRET, ONE_DAY_MS);
        String forged = new JwtTokenProvider(OTHER_SECRET, ONE_DAY_MS).generateToken(EMAIL);

        assertThatThrownBy(() -> provider.extractEmail(forged)).isInstanceOf(JwtException.class);
    }
}
