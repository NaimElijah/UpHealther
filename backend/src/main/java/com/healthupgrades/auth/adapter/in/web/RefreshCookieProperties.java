package com.healthupgrades.auth.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The refresh cookie's name and path, bound from {@code app.auth.cookie}.
 *
 * <p>No security attribute is configurable. {@code HttpOnly}, {@code Secure} and {@code SameSite} are
 * fixed in {@link RefreshCookies}: they are the security properties of the cookie, not preferences, and
 * a deployment that could turn one off is a deployment where somebody eventually will. {@code Secure}
 * was configurable once, defaulting to off, and that is how the credential came to travel in clear text
 * to any deployment that forgot to turn it on (#87, ADR-041).
 *
 * @param name the cookie's name
 * @param path the path it is scoped to; {@code /}, the only one its {@code __Host-} name allows
 */
@Validated
@ConfigurationProperties("app.auth.cookie")
public record RefreshCookieProperties(
        @NotBlank String name, @NotBlank String path) {}
