package com.healthupgrades.auth.adapter.out.security;

import com.healthupgrades.auth.domain.port.out.SecretGeneratorPort;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * The shipped {@link SecretGeneratorPort}: 256 bits from {@link SecureRandom}, base64url-encoded.
 *
 * <p>256 bits because the secret is the entire proof of possession — there is no password behind it and
 * no second factor beside it, so guessing one must be impossible rather than merely hard.
 *
 * <p>Base64url without padding, because the value travels in a cookie: the URL-safe alphabet avoids
 * {@code +} and {@code /}, and dropping {@code =} avoids a character that cookie parsers have opinions
 * about.
 *
 * <p>One {@link SecureRandom} for the life of the application, not one per call. It is thread-safe, and
 * constructing them repeatedly is how a process ends up re-seeding on a busy path.
 */
@Component
public class SecureRandomSecretGenerator implements SecretGeneratorPort {

    /** 32 bytes — 256 bits — of entropy per secret. */
    private static final int SECRET_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

    /** {@inheritDoc} */
    @Override
    public String generate() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return encoder.encodeToString(bytes);
    }
}
