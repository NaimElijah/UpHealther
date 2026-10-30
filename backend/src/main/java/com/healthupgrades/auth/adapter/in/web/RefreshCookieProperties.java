package com.healthupgrades.auth.adapter.in.web;

import jakarta.validation.constraints.AssertTrue;
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
        @NotBlank String name, @NotBlank String path) {

    /** The prefix a browser accepts only with {@code Secure}, no {@code Domain} and {@link #ROOT_PATH}. */
    static final String HOST_PREFIX = "__Host-";

    /** The one path a {@link #HOST_PREFIX} name may be set on. */
    static final String ROOT_PATH = "/";

    /**
     * Whether the path is one the name's prefix allows. A browser refuses a {@code __Host-} cookie set on
     * any other path, silently, so the pair would leave every browser without a session while the server
     * answered as usual. Checked at startup instead, as the lifetimes are (ADR-065).
     *
     * @return false only for a {@code __Host-} name on a path other than {@code /}
     */
    @AssertTrue(message = "must be / for a cookie whose name carries the __Host- prefix")
    public boolean isPathAllowedByName() {
        return name == null || path == null || !name.startsWith(HOST_PREFIX) || ROOT_PATH.equals(path);
    }
}
