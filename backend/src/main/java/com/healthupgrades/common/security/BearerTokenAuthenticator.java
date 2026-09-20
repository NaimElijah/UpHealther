package com.healthupgrades.common.security;

import com.healthupgrades.user.application.port.in.UserQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Turns a bearer token into the principal it authenticates, or into nobody.
 *
 * <p>The single place the rules for "this token is usable" live, shared by the HTTP filter and the STOMP
 * interceptor so the two transports cannot drift apart. A token is usable when it verifies and the
 * account it names still exists. The account is re-read on every call rather than trusted from the
 * token, so deleting it takes effect on the next request (NFR-5).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BearerTokenAuthenticator {

    private final JwtTokenProvider tokenProvider;
    private final UserQuery userQuery; // inbound read port of the user context

    /**
     * Authenticates a bearer token.
     *
     * @param token the compact JWT, without the {@code Bearer } prefix
     * @return the principal for the account the token names, with no password hash on it; empty when the
     *         token does not verify or its account is gone
     */
    public Optional<SecurityUser> authenticate(String token) {
        return tokenProvider.verify(token).flatMap(verified -> {
            Optional<SecurityUser> principal = userQuery.findById(verified.userId()).map(SecurityUser::from);
            if (principal.isEmpty()) {
                // WARN, not DEBUG: the signature verified, so this token was issued by us and is being
                // presented for an account that no longer exists. Ordinary expiry does not reach here.
                // The id is not logged either: it would join this line to an account that has been
                // deleted, which is exactly the record deletion was meant to end.
                log.warn("A validly signed token names an account that no longer exists; left anonymous");
            }
            principal.ifPresent(SecurityUser::eraseCredentials);
            return principal;
        });
    }
}
