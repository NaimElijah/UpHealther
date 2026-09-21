package com.healthupgrades.auth.domain.port.out;

/**
 * Outbound port for the random half of a refresh credential.
 *
 * <p>A port rather than a call to {@code SecureRandom}, for one reason: the domain must not decide
 * where randomness comes from, and a test must be able to make a "random" value predictable without
 * seeding a global generator. The security of the scheme rests on the implementation, so
 * {@code SecureRandomSecretGenerator} is the only one that ships.
 */
public interface SecretGeneratorPort {

    /**
     * Generates a fresh secret.
     *
     * @return at least 256 bits of cryptographically secure randomness, base64url-encoded so it is safe
     *         in a cookie value
     */
    String generate();
}
