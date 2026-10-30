package com.healthupgrades.auth.domain.port.out;

import java.util.UUID;

/**
 * Outbound port for minting the access token a session hands its account.
 *
 * <p>A port rather than a call to the JWT provider, for the reason {@link SecretGeneratorPort} is one:
 * the use cases decide <em>when</em> a token is issued and for whom, and must not know how it is signed,
 * with what key, or by which library. Holding the provider directly tied {@code AuthService} to JJWT and
 * the signing key through a class the adapter rule could not see, because it lives in
 * {@code common.security} rather than under an {@code adapter} package (ADR-002).
 * {@code JwtAccessTokenIssuer} is the only implementation that ships.
 */
public interface AccessTokenIssuerPort {

    /**
     * Issues an access token for an account, within a session.
     *
     * @param userId    the account the token is for
     * @param sessionId the session it belongs to, so ending that session stops the token working
     * @return the signed token and the instant it stops being accepted
     */
    AccessToken issue(UUID userId, UUID sessionId);
}
