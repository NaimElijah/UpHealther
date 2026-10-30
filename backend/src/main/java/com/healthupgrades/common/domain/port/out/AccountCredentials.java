package com.healthupgrades.common.domain.port.out;

import java.util.UUID;

/**
 * What the security adapter needs to know about an account to authenticate as it, and nothing more.
 *
 * <p>Owned by the kernel, so that {@code common} names no type of the context that holds accounts
 * ({@link AccountCredentialsPort} says why). Deliberately not the account itself: no name, no creation
 * time, nothing a principal could be tempted to expose.
 *
 * @param id           the account's identifier, the owner id every user-scoped query is keyed by
 * @param email        the account's normalised address, its login identity
 * @param passwordHash the encoded password, matched at sign-in
 * @param roleName     the name of the role the account holds, as Spring Security's {@code hasRole}
 *                     matches it: {@code USER} or {@code ADMIN}, without the {@code ROLE_} prefix
 * @param enabled      false once an administrator has switched the account off
 */
public record AccountCredentials(UUID id, String email, String passwordHash, String roleName, boolean enabled) {

    /**
     * Omits the address and the hash, which a record's generated {@code toString} would print: the first is
     * personal data and the second a credential, and neither may reach a log line (NFR-6).
     */
    @Override
    public String toString() {
        return "AccountCredentials[id=" + id + ", roleName=" + roleName + ", enabled=" + enabled + "]";
    }
}
