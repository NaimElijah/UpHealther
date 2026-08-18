package com.healthupgrades.auth.application;

import com.healthupgrades.common.domain.exception.BusinessRuleException;
import com.healthupgrades.common.security.JwtTokenProvider;
import com.healthupgrades.user.application.port.in.UserCommand;
import com.healthupgrades.user.application.port.in.UserQuery;
import com.healthupgrades.user.domain.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
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

    private final UserQuery userQuery; // inbound read port of the user context
    private final UserCommand userCommand; // inbound write port of the user context
    private final PasswordEncoder passwordEncoder; // BCrypt encoder
    private final JwtTokenProvider tokenProvider; // issues JWTs
    private final AuthenticationManager authenticationManager; // verifies credentials on login

    /**
     * Registers a new user and issues a token for them.
     *
     * <p>The password is BCrypt-encoded before the user is saved; the raw value never leaves this method.
     *
     * @param name     display name
     * @param email    login identity, unique across users
     * @param password raw password, encoded here
     * @return the issued JWT together with the persisted user
     * @throws BusinessRuleException if the email is already registered
     */
    @Transactional
    public AuthResult register(String name, String email, String password) {
        if (userQuery.existsByEmail(email)) {
            throw new BusinessRuleException("Email already registered: " + email);
        }
        User user = User.builder()
                .name(name)
                .email(email)
                .passwordHash(passwordEncoder.encode(password)) // never store the raw password
                .build();
        user = userCommand.save(user);
        return new AuthResult(tokenProvider.generateToken(user.getEmail()), user);
    }

    /**
     * Authenticates credentials and issues a token.
     *
     * @param email    login identity
     * @param password raw password, matched against the stored hash by the authentication manager
     * @return the issued JWT together with the authenticated user
     * @throws org.springframework.security.core.AuthenticationException if the credentials do not match
     * @throws BusinessRuleException if the credentials matched but the user has since disappeared —
     *         only reachable if the account is deleted mid-request
     */
    public AuthResult login(String email, String password) {
        authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, password)); // throws on bad creds
        User user = userQuery.findByEmail(email)
                .orElseThrow(() -> new BusinessRuleException("User not found"));
        return new AuthResult(tokenProvider.generateToken(user.getEmail()), user);
    }

    /**
     * Looks up the current user by the email carried as the JWT subject.
     *
     * @param email the authenticated principal's email
     * @return the domain user
     * @throws BusinessRuleException if no user has that email
     */
    public User getMe(String email) {
        return userQuery.findByEmail(email)
                .orElseThrow(() -> new BusinessRuleException("User not found"));
    }
}
