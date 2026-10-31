package com.healthupgrades.auth.application;

import com.healthupgrades.auth.application.port.in.SessionCommand;
import com.healthupgrades.auth.application.port.in.SessionGrant;
import com.healthupgrades.auth.domain.port.out.AccessToken;
import com.healthupgrades.auth.domain.port.out.AccessTokenIssuerPort;
import com.healthupgrades.common.domain.audit.AuditAction;
import com.healthupgrades.common.domain.audit.AuditEvent;
import com.healthupgrades.common.domain.audit.AuditOutcome;
import com.healthupgrades.common.domain.exception.BusinessRuleException;
import com.healthupgrades.common.domain.port.out.AuditTrail;
import com.healthupgrades.user.application.port.in.UserCommand;
import com.healthupgrades.user.application.port.in.UserQuery;
import com.healthupgrades.user.domain.model.EmailAddress;
import com.healthupgrades.user.domain.model.EmailAlreadyRegisteredException;
import com.healthupgrades.user.domain.model.User;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for authentication.
 *
 * <p>Reads and writes users through the user context's inbound ports ({@link UserQuery} / {@link UserCommand})
 * and returns domain results ({@link AuthResult} / {@link User}); the web adapter maps them to HTTP responses.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    /**
     * The one wording for a taken address, whichever way it was discovered. It does not quote the
     * address back: the caller already knows it, and a message is one log line away from a file.
     */
    static final String EMAIL_TAKEN = "That email is already registered";

    private final UserQuery userQuery;
    private final UserCommand userCommand;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenIssuerPort tokenIssuer; // outbound token-signing port
    private final AuthenticationManager authenticationManager;
    private final SessionCommand sessions;
    private final AuditTrail auditTrail; // records who signed in, and who was turned away

    /**
     * Registers a new user and issues a token for them.
     *
     * <p>The password is BCrypt-encoded before the user is saved; the raw value never leaves this method.
     * The address is compared and stored normalised (FR-4), and the save flushes, so an address taken
     * by a concurrent registration after the existence check is refused here as the same 422 rather
     * than failing at commit as a 500.
     *
     * @param name     display name
     * @param rawEmail login identity as typed; unique across users once normalised
     * @param password raw password, encoded here
     * @return the issued JWT together with the persisted user
     * @throws BusinessRuleException if the email is already registered
     */
    @Transactional
    public AuthResult register(String name, String rawEmail, String password) {
        String email = EmailAddress.normalise(rawEmail);
        // Recorded by hand rather than through AuditTrail.recording, because until the save returns
        // there is no user id to name as the actor, and the email that would identify the attempt is
        // exactly what NFR-6 says must not be written down.
        try {
            if (userQuery.existsByEmail(email)) {
                throw new BusinessRuleException(EMAIL_TAKEN);
            }
            User user = User.register(name, email, passwordEncoder.encode(password)); // never the raw password
            try {
                user = userCommand.register(user);
            } catch (EmailAlreadyRegisteredException lostTheRace) {
                throw new BusinessRuleException(EMAIL_TAKEN);
            }
            // Recorded after the token exists, not before. Issuing it can fail - a secret too short to
            // sign with - and recording ALLOWED first would then put one attempt in the trail twice,
            // once as allowed and once as failed, with the counter double-counting to match.
            AuthResult result = startSession(user);
            auditTrail.record(AuditEvent.allowed(AuditAction.AUTH_REGISTER, user.getId(), user.getId()));
            return result;
        } catch (RuntimeException | Error thrown) {
            auditTrail.record(AuditEvent.from(AuditAction.AUTH_REGISTER, null, null, thrown));
            throw thrown;
        }
    }

    /**
     * Authenticates credentials and issues a token.
     *
     * @param rawEmail login identity as typed; normalised before it is matched
     * @param password raw password, matched against the stored hash by the authentication manager
     * @return the issued JWT together with the authenticated user
     * @throws org.springframework.security.authentication.BadCredentialsException if the email or password does
     *         not match
     * @throws org.springframework.security.authentication.AccountStatusException if the account is disabled
     * @throws org.springframework.security.core.AuthenticationException any other failure of the authentication
     *         manager, such as a database outage, which is not a rejection of the credentials
     * @throws BusinessRuleException if the credentials matched but the user has since disappeared —
     *         only reachable if the account is deleted mid-request
     */
    public AuthResult login(String rawEmail, String password) {
        String email = EmailAddress.normalise(rawEmail);
        try {
            authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, password));
            User user = userQuery.findByEmail(email).orElseThrow(() -> new BusinessRuleException("User not found"));
            // As in register: after the token, so a signing failure cannot produce two entries for one
            // sign-in attempt.
            AuthResult result = startSession(user);
            auditTrail.record(AuditEvent.allowed(AuditAction.AUTH_LOGIN, user.getId(), user.getId()));
            return result;
        } catch (BadCredentialsException | AccountStatusException refused) {
            // A refused login is recorded with no subject at all. The submitted email is personal data
            // (NFR-6) and there is no user id to name, because naming one would mean confirming that
            // the account exists. What survives is the count and the trace id — a rate signal, not an
            // attribution, which is the honest limit of auditing an anonymous endpoint.
            auditTrail.record(new AuditEvent(AuditAction.AUTH_LOGIN, null, null, AuditOutcome.REFUSED));
            throw refused;
        } catch (RuntimeException | Error thrown) {
            // Everything else is a fault, not a rejection - the same distinction GlobalExceptionHandler
            // draws when it refuses to report a database outage to the user as a wrong password.
            auditTrail.record(AuditEvent.from(AuditAction.AUTH_LOGIN, null, null, thrown));
            throw thrown;
        }
    }

    /**
     * Looks up the current user by the id the access token named.
     *
     * @param userId the authenticated principal's id
     * @return the domain user
     * @throws BusinessRuleException if the account has gone since the token was checked, which is only
     *         reachable if it is deleted mid-request
     */
    public User getMe(UUID userId) {
        return userQuery.findById(userId).orElseThrow(() -> new BusinessRuleException("User not found"));
    }

    /**
     * Issues a fresh access token for a session that has just rotated its credential.
     *
     * <p>The session was decided before this is called, and the account read inside the transaction that
     * decided it; all that is left is to mint the token. Nothing here touches the database: the rotation
     * has already committed, so a fault now would answer without the new credential, and the client's
     * next refresh would present the spent one as a replay (#342, D2.1). Token issuing stays here rather
     * than moving to the controller, so the web adapter never touches the signing key.
     *
     * @param user  the account the rotated session belongs to, as the refresh read it
     * @param grant the session and its new credential
     * @return the new access token, the account, and the grant to put back in the cookie
     */
    public AuthResult continueSession(User user, SessionGrant grant) {
        return issued(user, grant);
    }

    /**
     * Opens a session for an account that has just proved who it is, and issues the first token in it.
     *
     * <p>The session has to exist before the token, because the token names it. If issuing then fails,
     * the session is left behind on the login path, which is not transactional — harmless, because its
     * credential was never returned to anybody, and the cleanup sweep removes it.
     */
    private AuthResult startSession(User user) {
        return issued(user, sessions.open(user.getId()));
    }

    /** Mints a token within a session. The one place the two halves of a grant are put together. */
    private AuthResult issued(User user, SessionGrant grant) {
        AccessToken token = tokenIssuer.issue(user.getId(), grant.sessionId());
        return new AuthResult(token.value(), token.expiresAt(), user, grant);
    }
}
