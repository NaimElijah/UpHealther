package com.healthupgrades.auth.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The refresh cookie's attributes, bound from {@code app.auth.cookie}.
 *
 * <p>Only {@code secure} is configurable, and only because a developer on {@code http://localhost} has
 * no TLS for the browser to send it over. Everything else is fixed in {@link RefreshCookies}: they are
 * the security properties of the cookie, not preferences, and a deployment that could turn
 * {@code HttpOnly} off is a deployment where somebody eventually will.
 *
 * @param name   the cookie's name
 * @param path   the path it is scoped to; narrow enough that it is not sent with ordinary API calls
 * @param secure whether the browser may only send it over TLS. True everywhere real; false locally,
 *               where there is no TLS to send it over
 */
@Validated
@ConfigurationProperties("app.auth.cookie")
public record RefreshCookieProperties(
        @NotBlank String name,
        @NotBlank String path,
        boolean secure
) {
}
