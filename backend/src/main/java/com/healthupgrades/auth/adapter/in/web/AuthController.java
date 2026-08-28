package com.healthupgrades.auth.adapter.in.web;

import com.healthupgrades.auth.application.AuthResult;
import com.healthupgrades.auth.application.AuthService;
import com.healthupgrades.common.security.SecurityUser;
import com.healthupgrades.user.adapter.in.web.UserDto;
import com.healthupgrades.user.domain.model.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Inbound web adapter for authentication. Drives {@link AuthService} (which returns domain results) and
 * maps them to the {@link TokenPair} / {@link UserDto} HTTP responses.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    /** Mirrors {@code users.name VARCHAR(255)}; see BR-16 for why the bound is here. */
    static final int NAME_MAX = 255;

    /** Mirrors {@code users.email VARCHAR(255)}. */
    static final int EMAIL_MAX = 255;

    /**
     * The shortest password the API accepts. Unlike the bounds above this mirrors no column - only the
     * hash is stored, and a BCrypt hash is always 60 characters - so it is policy, and it is enforced
     * here because a rule the browser alone applies is not enforced at all. BR-17.
     */
    static final int PASSWORD_MIN = 8;

    /**
     * Where BCrypt stops reading. {@code BCryptPasswordEncoder} guards only against null, so anything
     * past this is silently dropped and a longer password becomes indistinguishable from its own
     * prefix; refusing it is honest where truncating it is not. Note the mismatch this cannot close:
     * {@code @Size} counts UTF-16 code units and BCrypt counts bytes, so a password of multi-byte
     * characters can pass this bound and still be truncated.
     */
    static final int PASSWORD_MAX = 72;

    private final AuthService authService; // application service

    /**
     * Registers a new user and issues a token.
     *
     * @param request name, email and password; all validated as non-blank, the email as well-formed
     * @return 201 with the JWT and the new user's public view
     * @throws com.healthupgrades.common.domain.exception.BusinessRuleException if the email is already
     *         registered (surfaces as 422)
     */
    @PostMapping("/register")
    public ResponseEntity<TokenPair> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(toTokenPair(authService.register(request.name(), request.email(), request.password())));
    }

    /**
     * Authenticates credentials and issues a token.
     *
     * @param request email and password
     * @return 200 with the JWT and the user's public view
     * @throws org.springframework.security.core.AuthenticationException if the credentials do not match
     *         (surfaces as 401; the response never says which half was wrong)
     */
    @PostMapping("/login")
    public ResponseEntity<TokenPair> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(toTokenPair(authService.login(request.email(), request.password())));
    }

    /**
     * Returns the caller's own profile — the endpoint the frontend uses to restore a session from a
     * stored token.
     *
     * @param principal the authenticated principal, injected by Spring Security from the bearer token
     * @return 200 with the user's public view
     */
    @GetMapping("/me")
    public ResponseEntity<UserDto> me(@AuthenticationPrincipal SecurityUser principal) {
        return ResponseEntity.ok(toUserDto(authService.getMe(principal.getUsername())));
    }

    /** Maps an auth result to the token-pair response. */
    private TokenPair toTokenPair(AuthResult result) {
        return new TokenPair(result.token(), toUserDto(result.user()));
    }

    /** Maps a domain user to its public DTO. */
    private UserDto toUserDto(User user) {
        return new UserDto(user.getId(), user.getName(), user.getEmail(), user.getCreatedAt());
    }

    /** Registration request body. */
    public record RegisterRequest(
            @NotBlank @Size(max = NAME_MAX) String name,
            @NotBlank @Email @Size(max = EMAIL_MAX) String email,
            @NotBlank @Size(min = PASSWORD_MIN, max = PASSWORD_MAX) String password
    ) {}

    /** Login request body. */
    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {}
}
