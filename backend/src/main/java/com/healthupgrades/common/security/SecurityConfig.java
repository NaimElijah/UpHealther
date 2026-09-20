package com.healthupgrades.common.security;

import com.healthupgrades.common.observability.TraceIdResponseHeaderFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.Arrays;
import java.util.List;

/**
 * Web security wiring: a stateless, token-authenticated API.
 *
 * <p>Defines the filter chain, the password encoder, the DAO authentication provider used by the login
 * endpoint, and the CORS policy. Authorization is deliberately thin: a request is either on the small
 * permitted list, or it is an administration path that needs the ADMIN role, or it needs a valid token.
 * Ownership is <em>not</em> decided here: every query is scoped by user id at the repository, so one
 * user cannot read another's rows even though both are "authenticated", and an administrator is no
 * exception (see {@code backend/CLAUDE.md} and ADR-016).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final UserDetailsServiceImpl userDetailsService;

    @Value("${app.cors.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    /**
     * Builds the filter chain: stateless sessions, CORS from configuration, the JWT filter ahead of the
     * username/password filter, and refusals answered with the API's error body rather than the
     * framework's.
     *
     * @param http       Spring Security's chain builder
     * @param errorBodies the entry point and access-denied handler that hand refusals to the MVC resolver
     * @return the configured chain
     * @throws Exception if the chain cannot be built
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ErrorBodySecurityHandlers errorBodies)
            throws Exception {
        http
                // CSRF disabled intentionally: this is a stateless JWT REST API.
                // CSRF attacks require browser-managed session cookies; JWT in Authorization header is not
                // automatically sent by browsers, so CSRF protection is not applicable here.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Exactly the two endpoints a visitor without a token needs. A blanket
                        // /api/auth/** also opened /api/auth/me, which then dereferenced a null
                        // @AuthenticationPrincipal and answered 500 instead of refusing the request
                        // (FR-5) - see AuthenticatedBoundaryTest.
                        // Refresh and logout act on the refresh cookie alone, so they must answer a
                        // caller whose access token has already expired - which is the entire point
                        // of refresh. They are not unprotected: the cookie is HttpOnly and
                        // SameSite=Strict, and both endpoints additionally require an
                        // X-Requested-With header that a cross-site form post cannot set.
                        .requestMatchers("/api/auth/register", "/api/auth/login",
                                "/api/auth/refresh", "/api/auth/logout").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        // The WebSocket handshake is open; the STOMP CONNECT frame is authenticated by
                        // JwtChannelInterceptor (the JWT travels in the STOMP headers, not the handshake).
                        .requestMatchers("/ws/**").permitAll()
                        // Declared here as well as by @PreAuthorize on the controller. The annotation
                        // is the rule a reader of that class sees; this line is what refuses a path
                        // under /api/admin that somebody later adds and forgets to annotate.
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(errorBodies)
                        .accessDeniedHandler(errorBodies))
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * The refusal handlers, bound to Spring MVC's composite exception resolver.
     *
     * <p>Qualified by name because {@code DefaultErrorAttributes} also implements
     * {@link HandlerExceptionResolver}, and it is the wrong one: it records the error for Boot's error
     * page and resolves nothing.
     */
    @Bean
    public ErrorBodySecurityHandlers errorBodySecurityHandlers(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return new ErrorBodySecurityHandlers(resolver);
    }

    /**
     * Authentication provider used by the login endpoint to match a submitted password against the
     * stored hash.
     *
     * <p>The two account checks are deliberately swapped round. Spring runs its pre-authentication
     * checks — enabled, not locked, not expired — <em>before</em> the password is verified, so a
     * disabled account is refused without BCrypt ever running. That reply comes back in a fraction of
     * the time a wrong password takes, and the difference is measurable from outside: it tells an
     * anonymous caller which addresses belong to real accounts that happen to be switched off. Moving
     * the check after the password match costs a disabled account exactly what a wrong password costs,
     * and both answer 401 "Invalid credentials".
     *
     * <p>The pre-check is left empty rather than trimmed, because {@link SecurityUser} models none of
     * the other flags — it answers true to locked, expired and credentials-expired. A flag that starts
     * meaning something must be added to the post-check below, or it will not be enforced at all.
     */
    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        provider.setPreAuthenticationChecks(user -> { });
        provider.setPostAuthenticationChecks(user -> {
            if (!user.isEnabled()) {
                throw new DisabledException("Account is disabled");
            }
        });
        return provider;
    }

    /**
     * Exposes the {@link AuthenticationManager} so {@code AuthService} can authenticate a login attempt.
     *
     * @throws Exception if the manager cannot be resolved from the configuration
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /** BCrypt encoder — the algorithm the stored password hashes (including the seeded demo user) use. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * CORS policy for cross-origin browsers, from the comma-separated {@code app.cors.allowed-origins}.
     *
     * <p>Only needed when the frontend is served from another origin; the default setup proxies
     * {@code /api} same-origin (Vite in dev, nginx in prod) and never reaches this.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        CorsConfiguration config = new CorsConfiguration();
        if (origins.contains("*")) {
            // Browsers reject a wildcard origin combined with credentials, so use origin patterns
            // and disable credentials in that case (this API authenticates via a bearer token, not cookies).
            config.setAllowedOriginPatterns(List.of("*"));
            config.setAllowCredentials(false);
        } else {
            config.setAllowedOrigins(origins);
            config.setAllowCredentials(true);
        }
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // setAllowedHeaders governs the request; a response header is unreadable to cross-origin
        // JavaScript unless it is exposed as well, which would silently defeat X-Trace-Id.
        config.setExposedHeaders(List.of(TraceIdResponseHeaderFilter.TRACE_ID_HEADER));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
