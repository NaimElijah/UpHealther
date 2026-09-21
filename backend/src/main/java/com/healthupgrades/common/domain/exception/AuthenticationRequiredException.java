package com.healthupgrades.common.domain.exception;

/**
 * A request reached a protected resource without a credential the server accepts; surfaces as 401.
 *
 * <p>Carries one fact, whether a bearer token was presented at all, because RFC 6750 answers the two
 * cases differently: a request with no credential gets a bare {@code Bearer} challenge, and one whose
 * token was refused gets {@code error="invalid_token"}. It deliberately does not say <em>why</em> a
 * token was refused. Expired, forged and revoked look the same from outside, so a stolen token's
 * holder learns nothing about whether it is worth retrying.
 */
public class AuthenticationRequiredException extends RuntimeException {

    private final boolean tokenPresented;

    private AuthenticationRequiredException(boolean tokenPresented) {
        super("Authentication required");
        this.tokenPresented = tokenPresented;
    }

    /** The request carried no bearer token. */
    public static AuthenticationRequiredException noCredential() {
        return new AuthenticationRequiredException(false);
    }

    /** The request carried a bearer token that was not accepted, for whatever reason. */
    public static AuthenticationRequiredException rejectedToken() {
        return new AuthenticationRequiredException(true);
    }

    /** Whether a bearer token was presented and refused, as opposed to absent. */
    public boolean tokenPresented() {
        return tokenPresented;
    }
}
