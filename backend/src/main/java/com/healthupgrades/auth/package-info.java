/**
 * Authentication and server-side sessions: registration, sign-in, a refresh credential that rotates on every
 * use, and sign-out that ends a session instead of waiting for a token to expire.
 *
 * <p>The context owns one aggregate, {@code AuthSession}, and the {@code SpentCredential}s its rotations retire.
 * Accounts belong to the {@code user} context and are reached only through its {@code UserQuery} and
 * {@code UserCommand} ports.
 *
 * <p>There are two credentials, kept apart. The access token is a JWT that names its session in a {@code sid}
 * claim; it travels in the response body and the client holds it in memory. The refresh credential is an opaque
 * {@code <sessionId>.<secret>} value in an {@code HttpOnly}, {@code Secure}, {@code SameSite=Strict} cookie
 * whose attributes are fixed in {@code RefreshCookies} and not configurable (ADR-041, ADR-065). The database
 * holds only its SHA-256 digest.
 *
 * <p>Rules a maintainer must keep:
 *
 * <ul>
 *   <li><strong>One use per credential.</strong> A refresh mints a new secret and keeps the digest of every
 *       credential it retired. Presenting a spent credential after {@code app.auth.session.rotation-grace}
 *       revokes the session as reuse; inside the window the answer is {@code RefreshOutcome.Stale}, a
 *       retryable 409, and nothing is revoked.
 *   <li><strong>A verdict, not an exception.</strong> {@code SessionCommand.refresh} returns a
 *       {@code RefreshOutcome}, because refusing by exception would roll back the revocation that reuse
 *       detection has just made.
 *   <li><strong>{@code AuthSessionService} depends on no security bean.</strong> {@code AuthService} holds
 *       the {@code AuthenticationManager}, which comes from {@code SecurityConfig}, whose filter chain reads
 *       session state on every request. Merging the two closes a bean cycle.
 *   <li><strong>{@code AuthSession} has no {@code @Version}.</strong> {@code findForUpdate} takes a row lock,
 *       so two refreshes of one session wait for each other.
 *   <li><strong>Refresh and sign-out need no access token.</strong> They are defended by the cookie's
 *       {@code SameSite=Strict} and the required {@code X-Requested-With} header, and are rate-limited apart
 *       from sign-in in {@code common.ratelimit}.
 *   <li><strong>Every attempt is audited</strong> through {@code AuditTrail} (NFR-23), and a refusal names
 *       only what it can prove.
 *   <li><strong>The shared kernel never names this context.</strong> {@code SessionStatusAdapter} answers
 *       {@code common}'s {@code SessionStatusPort}, and {@code JwtAccessTokenIssuer} implements
 *       {@code AccessTokenIssuerPort} over {@code common}'s {@code JwtTokenProvider} (ADR-062). When a session
 *       ends, {@code AuthSessionEnded} or {@code UserSessionsEnded} is published, and
 *       {@code SessionEndSocketListener} closes the matching notification sockets once the end commits
 *       (ADR-054).
 *   <li><strong>The password rules are bean-validation constraints on the registration request</strong>
 *       (BR-17, ADR-071): the bounds on {@code AuthController}, {@code @MaxUtf8Bytes} and
 *       {@code @NotCommonPassword}. The common-password list is read once at startup, and the application
 *       does not start without it.
 * </ul>
 *
 * <p>Configuration: {@code app.auth.session} ({@code AuthSessionProperties}), {@code app.auth.cookie}
 * ({@code RefreshCookieProperties}) and {@code app.auth.schedules.session-cleanup}. The first two are validated
 * at startup, and {@code AuthConfiguration} binds them.
 */
package com.healthupgrades.auth;
