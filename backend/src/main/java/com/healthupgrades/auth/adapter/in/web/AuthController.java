package com.healthupgrades.auth.adapter.in.web;

import com.healthupgrades.auth.application.AuthResult;
import com.healthupgrades.auth.application.AuthService;
import com.healthupgrades.common.security.SecurityUser;
import com.healthupgrades.user.adapter.in.web.UserDto;
import com.healthupgrades.user.domain.model.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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

    private final AuthService authService; // application service

    /** Registers a new user and returns a token pair. */
    @PostMapping("/register")
    public ResponseEntity<TokenPair> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(toTokenPair(authService.register(request.name(), request.email(), request.password())));
    }

    /** Authenticates and returns a token pair. */
    @PostMapping("/login")
    public ResponseEntity<TokenPair> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(toTokenPair(authService.login(request.email(), request.password())));
    }

    /** Returns the authenticated user's public view. */
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
            @NotBlank String name,
            @NotBlank @Email String email,
            @NotBlank String password
    ) {}

    /** Login request body. */
    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {}
}
