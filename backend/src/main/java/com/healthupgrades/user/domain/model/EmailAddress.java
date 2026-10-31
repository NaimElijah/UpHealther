package com.healthupgrades.user.domain.model;

import java.util.Locale;

/**
 * The single rule for what makes two email addresses the same login identity (FR-4).
 *
 * <p>An address is compared trimmed and lowercased. Applying that in one function, rather than at each
 * call site, is what keeps registration and login (the request records, {@code AuthService} and the
 * sign-in lookup in {@code AccountCredentialsAdapter}) from disagreeing about whether
 * {@code Someone@Example.com} and {@code someone@example.com} are one account. The database backs the
 * part of the rule every collation agrees on, no surrounding spaces and no ASCII capitals, with a check
 * constraint ({@code V7__normalise_user_emails.sql}). A row written around this class with other
 * whitespace or a non-ASCII capital is not refused.
 *
 * <p>The local part is lowercased too, although RFC 5321 lets a server treat it case-sensitively. No
 * mainstream provider does, and a user who types their address differently on two visits expects one
 * account.
 */
public final class EmailAddress {

    private EmailAddress() {}

    /**
     * Normalises an address for storage and comparison.
     *
     * <p>{@link Locale#ROOT} rather than the JVM default, which under {@code tr-TR} lowercases a capital
     * I to a dotless i and would make one address two identities depending on the host.
     *
     * @param email the address as typed, possibly null
     * @return the address stripped of surrounding whitespace and lowercased, or null when given null so
     *         that bean validation still reports the missing field by name
     */
    public static String normalise(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }
}
