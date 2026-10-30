package com.healthupgrades.common.domain.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port loading the credentials of an account, by the id a token names or by the address a
 * sign-in gives.
 *
 * <p>An inverted dependency, and the inversion is the point. The security adapter builds a principal on
 * every request and at every sign-in, and the {@code user} context is what holds accounts. Having
 * {@code common} call into {@code user} would put an arrow from the shared kernel into a bounded
 * context, which every other context already depends on, and would leave {@code user} unable to use the
 * kernel without closing a cycle. So {@code common} declares what it needs here, and
 * {@code user.adapter.in.composition.AccountCredentialsAdapter} supplies it. The same shape as
 * {@link SessionStatusPort}, for the same reason (ADR-062).
 *
 * <p>Both lookups are re-run on every request rather than cached, which is what makes a deleted account,
 * a disabled one and a revoked role take effect on the next call (NFR-5, NFR-33, NFR-34).
 */
public interface AccountCredentialsPort {

    /**
     * Loads the account an access token names.
     *
     * @param accountId the account named by a token's {@code sub} claim
     * @return the account's credentials, or empty when no such account exists
     */
    Optional<AccountCredentials> findById(UUID accountId);

    /**
     * Loads the account a sign-in names.
     *
     * <p>Takes the address as it was given. When two addresses are the same account is the supplier's
     * rule, not the kernel's, so the supplier applies it before looking the account up.
     *
     * @param email the address as typed, possibly null
     * @return the account's credentials, or empty when no account holds that address
     */
    Optional<AccountCredentials> findByEmail(String email);
}
