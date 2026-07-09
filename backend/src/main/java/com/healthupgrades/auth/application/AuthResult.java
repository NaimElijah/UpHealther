package com.healthupgrades.auth.application;

import com.healthupgrades.user.domain.model.User; // the authenticated domain user

/**
 * Result of an authentication use case, expressed with domain objects: the issued JWT and the
 * authenticated {@link User}. The web adapter maps this to the HTTP response.
 */
public record AuthResult(String token, User user) {}
