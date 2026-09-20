package com.healthupgrades.auth.adapter.in.web;

import com.healthupgrades.auth.application.SessionGrant;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * Builds and reads the refresh cookie.
 *
 * <p>The attributes are decided here rather than at each call site, because a cookie that is
 * {@code HttpOnly} on one response and not on another is a cookie that is not {@code HttpOnly}:
 *
 * <ul>
 *   <li><strong>{@code HttpOnly}</strong> — script cannot read it. This is the whole reason the
 *       long-lived credential lives in a cookie and the short-lived access token lives in memory:
 *       injected script can take what it can read, and it cannot read this.</li>
 *   <li><strong>{@code Secure}</strong> — TLS only, except on a local machine that has none.</li>
 *   <li><strong>{@code SameSite=Strict}</strong> — never sent on a cross-site request, which is the
 *       first of the two things standing between this cookie and CSRF. The second is the
 *       {@code X-Requested-With} header the endpoints require: a form post from another origin cannot
 *       set a header, and a cross-origin {@code fetch} that tries is stopped by preflight.</li>
 *   <li><strong>{@code Path}</strong> — scoped to the auth endpoints, so the credential is simply not
 *       attached to the hundred ordinary API calls that have no use for it.</li>
 *   <li><strong>{@code Max-Age}</strong> — the session's absolute cap, so the browser discards it at
 *       the moment the server stops honouring it rather than sending a dead credential for a month.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RefreshCookies {

    /**
     * The header a caller must send on refresh and sign-out.
     *
     * <p>Not authentication — it proves nothing about who is calling. It proves the call was made by
     * script rather than by a form, because a cross-origin form post cannot set a header at all and a
     * cross-origin {@code fetch} that sets one triggers a preflight the server does not allow. Paired
     * with {@code SameSite=Strict} it is the CSRF defence for the two endpoints that act on a cookie
     * alone.
     */
    public static final String REQUESTED_WITH = "X-Requested-With";

    private final RefreshCookieProperties properties;
    private final Clock clock;

    /**
     * The {@code Set-Cookie} value that installs a session's credential.
     *
     * @param grant the session and the credential to store
     * @return the cookie, ready to put on a response
     */
    public ResponseCookie issue(SessionGrant grant) {
        return base(grant.refreshToken())
                .maxAge(Duration.between(clock.instant(), grant.expiresAt()))
                .build();
    }

    /**
     * The {@code Set-Cookie} value that removes the credential from the browser.
     *
     * <p>Every attribute must match the cookie being replaced or the browser stores a second one beside
     * it, which is why this goes through the same builder rather than writing its own.
     *
     * @return an empty cookie that expires immediately
     */
    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    /**
     * Reads the credential a request carries.
     *
     * @param request the inbound request
     * @return the raw cookie value, or empty when there is none
     */
    public Optional<String> read(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(cookie -> properties.name().equals(cookie.getName()))
                .map(jakarta.servlet.http.Cookie::getValue)
                .findFirst();
    }

    /** The name the cookie is stored under, for a caller that has to name it on a response. */
    public String name() {
        return properties.name();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(properties.name(), value)
                .httpOnly(true)
                .secure(properties.secure())
                .sameSite("Strict")
                .path(properties.path());
    }
}
