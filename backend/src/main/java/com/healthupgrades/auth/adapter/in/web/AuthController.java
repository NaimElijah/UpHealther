package com.healthupgrades.auth.adapter.in.web;

import com.healthupgrades.auth.application.AuthResult;
import com.healthupgrades.auth.application.AuthService;
import com.healthupgrades.auth.application.RefreshOutcome;
import com.healthupgrades.auth.application.port.in.SessionCommand;
import com.healthupgrades.common.domain.exception.AuthenticationRequiredException;
import com.healthupgrades.common.domain.exception.RetryableConflictException;
import com.healthupgrades.common.security.SecurityUser;
import com.healthupgrades.user.adapter.in.web.UserDto;
import com.healthupgrades.user.domain.model.EmailAddress;
import com.healthupgrades.user.domain.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Inbound web adapter for authentication. Drives {@link AuthService} and {@link SessionCommand}
 * (which return domain results) and maps them to the {@link TokenPair} / {@link UserDto} HTTP
 * responses.
 *
 * <p>Every response that starts or continues a session also carries a {@code Set-Cookie}: the access
 * token goes in the body for the client to hold in memory, and the long-lived refresh credential goes
 * in an {@code HttpOnly} cookie that script cannot read. Splitting them that way is what stops
 * injected script from stealing a credential that would outlive the page it ran on.
 *
 * <p>{@code /refresh} and {@code /logout} are reachable without an access token — the first would be
 * pointless otherwise — and are defended instead by the cookie being {@code SameSite=Strict} and by
 * the {@code X-Requested-With} header both require. A cross-site form post can send the cookie
 * nowhere and can set no header at all.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    /** Mirrors {@code users.name VARCHAR(255)}; see BR-16 for why the bound is here. */
    public static final int NAME_MAX = 255;

    /** Mirrors {@code users.email VARCHAR(255)}. */
    public static final int EMAIL_MAX = 255;

    /**
     * The shortest password the API accepts. Unlike the bounds above this mirrors no column - only the
     * hash is stored, and a BCrypt hash is always 60 characters - so it is policy, and it is enforced
     * here because a rule the browser alone applies is not enforced at all. BR-17.
     */
    public static final int PASSWORD_MIN = 8;

    /**
     * Where BCrypt stops reading. {@code BCryptPasswordEncoder} guards only against null, so anything
     * past this is silently dropped and a longer password becomes indistinguishable from its own
     * prefix; refusing it is honest where truncating it is not. Note the mismatch this cannot close:
     * {@code @Size} counts UTF-16 code units and BCrypt counts bytes, so a password of multi-byte
     * characters can pass this bound and still be truncated.
     */
    public static final int PASSWORD_MAX = 72;

    private final AuthService authService; // application service
    private final SessionCommand sessions; // inbound write port for server-side sessions
    private final RefreshCookies cookies; // builds and reads the refresh cookie

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
        AuthResult result = authService.register(request.name(), request.email(), request.password());
        return withRefreshCookie(ResponseEntity.status(HttpStatus.CREATED), result);
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
        AuthResult result = authService.login(request.email(), request.password());
        return withRefreshCookie(ResponseEntity.ok(), result);
    }

    /**
     * Exchanges the refresh cookie for a new access token, and a new cookie.
     *
     * @param requestedWith the {@code X-Requested-With} header. Never read — its presence is the
     *                      assertion. Declared as a required header so a request without it is
     *                      refused as a 400 by the framework, before this method runs and before any
     *                      session is touched; a cross-site form post cannot set it, which is what
     *                      makes this endpoint safe to expose to a cookie alone
     * @param request       the inbound request, read for its cookie
     * @param response      the outbound response, so a refusal can clear a cookie on its way out
     * @return 200 with a fresh access token and a rotated cookie
     * @throws RetryableConflictException       409, when the credential was rotated out moments ago and
     *                                          the caller should simply try again
     * @throws AuthenticationRequiredException  401, when it cannot be used at all; the cookie is
     *                                          cleared so the browser stops sending a dead credential
     */
    @PostMapping("/refresh")
    public ResponseEntity<TokenPair> refresh(
            @RequestHeader(RefreshCookies.REQUESTED_WITH) String requestedWith,
            HttpServletRequest request, HttpServletResponse response) {
        RefreshOutcome outcome = sessions.refresh(cookies.read(request).orElse(null));
        return switch (outcome) {
            case RefreshOutcome.Rotated rotated ->
                    withRefreshCookie(ResponseEntity.ok(),
                            authService.continueSession(rotated.userId(), rotated.grant()));
            case RefreshOutcome.Stale ignored ->
                    throw new RetryableConflictException("Session was refreshed concurrently. Please retry.");
            case RefreshOutcome.Rejected ignored -> {
                // Cleared on the way out rather than in the handler: the exception carries a status and
                // a body, not a cookie, and leaving a dead credential in the browser means it is sent
                // with every subsequent attempt.
                response.addHeader(HttpHeaders.SET_COOKIE, cookies.clear().toString());
                throw AuthenticationRequiredException.rejectedToken();
            }
        };
    }

    /**
     * Ends this session, and only this one: the account stays signed in on its other devices.
     *
     * <p>Answers 204 whether or not a session was actually ended. A caller signing out has nothing to
     * do differently on being told its credential was already dead, and saying so would let an
     * unauthenticated caller test session ids.
     *
     * @param requestedWith see {@link #refresh}
     * @param request       the inbound request, read for its cookie
     * @return 204 with the cookie cleared
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(RefreshCookies.REQUESTED_WITH) String requestedWith,
            HttpServletRequest request) {
        cookies.read(request).ifPresent(sessions::revoke);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
                .build();
    }

    /**
     * Returns the caller's own profile — the endpoint the frontend calls after a reload has renewed
     * the session from the refresh cookie. The access token itself is never stored (NFR-38).
     *
     * @param principal the authenticated principal, injected by Spring Security from the bearer token
     * @return 200 with the user's public view
     */
    @GetMapping("/me")
    public ResponseEntity<UserDto> me(@AuthenticationPrincipal SecurityUser principal) {
        return ResponseEntity.ok(toUserDto(authService.getMe(principal.getId())));
    }

    /**
     * Puts the session's credential in a {@code Set-Cookie} and the access token in the body.
     *
     * <p>One place, so no response that opens or continues a session can forget the cookie, and none
     * can set it with attributes of its own.
     */
    private ResponseEntity<TokenPair> withRefreshCookie(ResponseEntity.BodyBuilder builder,
                                                        AuthResult result) {
        return builder
                .header(HttpHeaders.SET_COOKIE, cookies.issue(result.session()).toString())
                .body(toTokenPair(result));
    }

    /** Maps an auth result to the token-pair response. */
    private TokenPair toTokenPair(AuthResult result) {
        return new TokenPair(result.token(), result.expiresAt(), toUserDto(result.user()));
    }

    /** Maps a domain user to its public DTO. */
    private UserDto toUserDto(User user) {
        return new UserDto(user.getId(), user.getName(), user.getEmail(), user.getRole(),
                user.getCreatedAt());
    }

    /**
     * Registration request body.
     *
     * <p>The email is normalised in the compact constructor, which Jackson runs before bean validation:
     * {@code @Email} refuses surrounding whitespace, so normalising any later would turn a pasted
     * address with a trailing space into a 400.
     */
    public record RegisterRequest(
            @NotBlank @Size(max = NAME_MAX) String name,
            @NotBlank @Email @Size(max = EMAIL_MAX) String email,
            @NotBlank @Size(min = PASSWORD_MIN, max = PASSWORD_MAX) String password
    ) {
        public RegisterRequest {
            email = EmailAddress.normalise(email);
        }
    }

    /** Login request body; the email is normalised before validation, as in {@link RegisterRequest}. */
    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {
        public LoginRequest {
            email = EmailAddress.normalise(email);
        }
    }
}
