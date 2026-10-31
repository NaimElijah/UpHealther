package com.healthupgrades.common.security;

import com.healthupgrades.common.domain.port.out.AccountCredentials;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Spring Security principal that carries the essential identity of an authenticated user (id, email,
 * password hash) and what authorisation reads off it (the account's role and whether it is enabled).
 *
 * <p>This is the wrapper that keeps Spring Security out of the domain: the {@code User} entity is a plain
 * JPA aggregate, and this adapter type is what implements {@link UserDetails} and is exposed to
 * controllers via {@code @AuthenticationPrincipal}. It is built from the kernel's own
 * {@link AccountCredentials}, never from {@code User}, so the shared kernel names no type of the
 * {@code user} context (ADR-062). It implements {@link CredentialsContainer} so Spring
 * Security clears the stored password hash once authentication has completed.
 */
public class SecurityUser implements UserDetails, CredentialsContainer {

    /** The prefix Spring Security's hasRole() expects on an authority it matches by role name. */
    private static final String ROLE_PREFIX = "ROLE_";

    private final UUID id; // the user's identifier, used for ownership scoping
    private final String email; // the username in Spring Security terms
    private String passwordHash; // encoded password; non-final so it can be erased after authentication
    private final String roleName; // read from the row on every request, never carried in the token
    private final boolean enabled; // false once an administrator has switched the account off

    /**
     * Creates a principal from the identity and authorisation fields of an account. Private, so
     * {@link #from} stays the one way a principal is built.
     *
     * @param roleName the role's name without the {@code ROLE_} prefix, as {@code hasRole} matches it
     */
    private SecurityUser(UUID id, String email, String passwordHash, String roleName, boolean enabled) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.roleName = roleName;
        this.enabled = enabled;
    }

    /**
     * Wraps an account's credentials. The one way principals are built, so a field added to the principal
     * is filled in everywhere at once.
     *
     * @param account the account to authenticate as
     * @return a principal carrying the account's id, email, password hash, role and enabled flag
     */
    public static SecurityUser from(AccountCredentials account) {
        return new SecurityUser(
                account.id(), account.email(), account.passwordHash(), account.roleName(), account.enabled());
    }

    /** The authenticated user's id — controllers thread this through as the owner id. */
    public UUID getId() {
        return id;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Exactly one authority: the account's role under Spring Security's {@code ROLE_} prefix. The
     * role came from the row this principal was built from, and a principal is built on every
     * request, so a revoked ADMIN stops being one on the next call rather than when its token lapses.
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(ROLE_PREFIX + roleName));
    }

    /** {@inheritDoc} The encoded password used for credential matching (null once erased). */
    @Override
    public String getPassword() {
        return passwordHash;
    }

    /** {@inheritDoc} The username is the user's email, the login identity; tokens name the id instead. */
    @Override
    public String getUsername() {
        return email;
    }

    /** {@inheritDoc} Accounts never expire in this application. */
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    /** {@inheritDoc} Accounts are never locked. */
    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    /** {@inheritDoc} Credentials never expire. */
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /** {@inheritDoc} False once an administrator has disabled the account. */
    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /** {@inheritDoc} Clears the stored password hash once Spring Security no longer needs it. */
    @Override
    public void eraseCredentials() {
        this.passwordHash = null;
    }
}
