package com.healthupgrades.common.security;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Spring Security principal that carries the essential identity of an authenticated user (id, email,
 * password hash).
 *
 * <p>This is the wrapper that keeps Spring Security out of the domain: the {@code User} entity is a plain
 * JPA aggregate, and this adapter type is what implements {@link UserDetails} and is exposed to
 * controllers via {@code @AuthenticationPrincipal}. It implements {@link CredentialsContainer} so Spring
 * Security clears the stored password hash once authentication has completed.
 */
public class SecurityUser implements UserDetails, CredentialsContainer {

    private final UUID id; // the user's identifier, used for ownership scoping
    private final String email; // the username in Spring Security terms
    private String passwordHash; // encoded password; non-final so it can be erased after authentication

    /** Creates a principal from the identity fields of a user. */
    public SecurityUser(UUID id, String email, String passwordHash) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    /** The authenticated user's id — controllers thread this through as the owner id. */
    public UUID getId() {
        return id;
    }

    /** {@inheritDoc} No roles are modelled, so authorities are always empty. */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    /** {@inheritDoc} The encoded password used for credential matching (null once erased). */
    @Override
    public String getPassword() {
        return passwordHash;
    }

    /** {@inheritDoc} The username is the user's email (also the JWT subject). */
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

    /** {@inheritDoc} Accounts are always enabled. */
    @Override
    public boolean isEnabled() {
        return true;
    }

    /** {@inheritDoc} Clears the stored password hash once Spring Security no longer needs it. */
    @Override
    public void eraseCredentials() {
        this.passwordHash = null;
    }
}
