package com.healthupgrades.auth.adapter.in.web;

import static net.logstash.logback.argument.StructuredArguments.keyValue;

import com.healthupgrades.auth.application.port.in.SessionGrant;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

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
 *   <li><strong>{@code Secure}</strong> — TLS only, everywhere. A developer on {@code http://localhost}
 *       needs no exception: browsers waive the TLS requirement for a {@code Secure} cookie set by
 *       {@code localhost} (ADR-041).</li>
 *   <li><strong>{@code SameSite=Strict}</strong> — never sent on a cross-site request, which is the
 *       first of the two things standing between this cookie and CSRF. The second is the
 *       {@code X-Requested-With} header the endpoints require: a form post from another origin cannot
 *       set a header, and a cross-origin {@code fetch} that tries is stopped by preflight.</li>
 *   <li><strong>The {@code __Host-} name</strong> — the name {@code application.yml} ships, which a
 *       browser accepts only with {@code Secure}, from a secure origin, with {@code Path=/} and with no
 *       {@code Domain}. So no other host can set a cookie of that name: not a plain-HTTP one, and not a
 *       sibling or parent host served over HTTPS, which could otherwise plant its own credential and
 *       move this tab into its account (S5.A8). A request carrying two of them is still refused, as a
 *       defence (see {@link #read}).</li>
 *   <li><strong>{@code Path=/}</strong> — the only path the {@code __Host-} prefix allows. Its cost is
 *       that the credential goes with every request to the origin, not only to the auth endpoints;
 *       {@code HttpOnly} and {@code SameSite=Strict} still keep it from script and from every other
 *       site, and only the two cookie endpoints ever read it.</li>
 *   <li><strong>{@code Max-Age}</strong> — the session's absolute cap, so the browser discards it at
 *       the moment the server stops honouring it rather than sending a dead credential for a month.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
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
     * <p>The name and path must match the cookie being replaced or the browser stores a second one
     * beside it, and {@code Secure} must match too, or over plain HTTP the browser ignores the
     * replacement. That is why this goes through the same builder rather than writing its own.
     *
     * @return an empty cookie that expires immediately
     */
    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    /**
     * Reads the credential a request carries.
     *
     * <p>Two cookies of this name are no credential at all. The {@code __Host-} prefix means no other
     * host can set one, so no browser should ever send two; but if one does, nothing says which is
     * whose, and reading either could move this tab into a session somebody else opened (S5.A8). The
     * caller answers as it does to no cookie, which clears ours. The duplicate is logged at WARN, with
     * the count and never a value, because something planting cookies should not go unseen.
     *
     * @param request the inbound request
     * @return the raw cookie value, or empty when there is none, or more than one
     */
    public Optional<String> read(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        List<String> values = Arrays.stream(request.getCookies())
                .filter(cookie -> properties.name().equals(cookie.getName()))
                .map(jakarta.servlet.http.Cookie::getValue)
                .toList();
        if (values.size() > 1) {
            log.warn(
                    "A request carried more than one refresh cookie, so none was read {}",
                    keyValue("cookies", values.size()));
        }
        return values.size() == 1 ? Optional.of(values.getFirst()) : Optional.empty();
    }

    /** The name the cookie is stored under, for a caller that has to name it on a response. */
    public String name() {
        return properties.name();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(properties.name(), value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(properties.path());
    }
}
