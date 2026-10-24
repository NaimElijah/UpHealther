package com.healthupgrades.common.security;

import com.healthupgrades.common.domain.port.out.SessionStatusPort;
import com.healthupgrades.user.application.port.in.UserQuery;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Turns a bearer token into the principal it authenticates, or into nobody.
 *
 * <p>The single place the rules for "this token is usable" live, shared by the HTTP filter and the STOMP
 * interceptor so the two transports cannot drift apart. A token is usable when it verifies, the
 * session it names is still live and belongs to the account it names, that account still exists, and
 * it is enabled. All three are re-read on every call rather than trusted from the token, so signing out,
 * deleting the account or disabling it takes effect on the next request (NFR-5, NFR-34, NFR-35)
 * rather than whenever the token happens to expire.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BearerTokenAuthenticator {

    private final JwtTokenProvider tokenProvider;
    private final UserQuery userQuery; // inbound read port of the user context
    private final SessionStatusPort sessionStatus; // inverted port; the auth context supplies it

    /**
     * Authenticates a bearer token.
     *
     * @param token the compact JWT, without the {@code Bearer } prefix
     * @return the principal for the account the token names, with no password hash on it; empty when the
     *         token does not verify, its session has ended or is another account's, its account is gone,
     *         or that account has been disabled
     */
    public Optional<SecurityUser> authenticate(String token) {
        return authenticateWithSession(token).map(BearerAuthentication::user);
    }

    /**
     * Authenticates a bearer token by the same rules as {@link #authenticate}, and also says which
     * session it was issued within.
     *
     * @param token the compact JWT, without the {@code Bearer } prefix
     * @return the principal and the live session its {@code sid} names; empty in every case
     *         {@link #authenticate} is
     */
    public Optional<BearerAuthentication> authenticateWithSession(String token) {
        return tokenProvider.verify(token).flatMap(verified -> {
            if (!sessionStatus.isActive(verified.sessionId(), verified.userId())) {
                // Checked before the account is loaded: a signed-out token is the commonest reason to
                // land here, and there is no point reading a user to then discard the principal.
                // DEBUG, because a token outliving its session is what signing out is supposed to do.
                // A session that is another account's lands here too and reads the same (#286): the
                // port answers one boolean, so the two cannot be told apart at this line.
                log.debug("A token names a session that has ended or is another account's; left anonymous");
                return Optional.<BearerAuthentication>empty();
            }
            Optional<SecurityUser> principal =
                    userQuery.findById(verified.userId()).map(SecurityUser::from);
            if (principal.isEmpty()) {
                // WARN, not DEBUG: the signature verified, so this token was issued by us and is being
                // presented for an account that no longer exists. Ordinary expiry does not reach here.
                // The id is not logged either: it would join this line to an account that has been
                // deleted, which is exactly the record deletion was meant to end.
                log.warn("A validly signed token names an account that no longer exists; left anonymous");
                return Optional.<BearerAuthentication>empty();
            }
            if (!principal.get().isEnabled()) {
                // DEBUG, not WARN: a token issued before an account was switched off, still being
                // presented afterwards, is the expected consequence of disabling one rather than an
                // anomaly. Checking it here and not only at sign-in is what makes disabled mean now,
                // instead of meaning within the token's remaining lifetime.
                log.debug("A token names a disabled account; left anonymous");
                return Optional.<BearerAuthentication>empty();
            }
            principal.ifPresent(SecurityUser::eraseCredentials);
            return principal.map(user -> new BearerAuthentication(user, verified.sessionId()));
        });
    }
}
