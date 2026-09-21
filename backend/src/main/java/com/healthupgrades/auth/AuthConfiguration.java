package com.healthupgrades.auth;

import com.healthupgrades.auth.adapter.in.web.RefreshCookieProperties;
import com.healthupgrades.auth.application.AuthSessionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds and validates this context's configuration.
 *
 * <p>It lives in the {@code auth} context rather than beside {@code SecurityConfig}, and that is a
 * boundary decision rather than a filing one: {@code SecurityConfig} is in {@code common}, and having
 * the shared kernel name a bounded context's properties class would point an arrow from the kernel into
 * a context that every other context depends on.
 */
@Configuration
@EnableConfigurationProperties({AuthSessionProperties.class, RefreshCookieProperties.class})
public class AuthConfiguration {
}
