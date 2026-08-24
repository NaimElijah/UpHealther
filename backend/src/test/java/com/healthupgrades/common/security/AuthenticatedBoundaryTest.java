package com.healthupgrades.common.security;

import com.healthupgrades.support.WebSliceSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FR-5, as one rule over the whole API rather than a line repeated in nine controller tests: every
 * endpoint except registration, login and the health checks requires a valid token.
 *
 * <p>This is the only test of that requirement, so the slice runs the real {@code SecurityConfig} and
 * the real {@code JwtAuthenticationFilter}. Every service below is mocked, which is the point — a
 * request that reaches a mocked service and gets a null back has still got past security, and that is
 * what the assertion is about.
 *
 * <p>The rejection status is <strong>403, not 401</strong>. No {@code AuthenticationEntryPoint} is
 * configured, so Spring Security's {@code Http403ForbiddenEntryPoint} answers an anonymous request to a
 * protected endpoint. This test asserts the behaviour as it is rather than as it arguably should be;
 * the discrepancy is recorded in {@code docs/architecture/architecture.md} under "Known constraints".
 *
 * <p>{@link #GivenTheApiSurface_WhenItIsEnumerated_ThenEveryProtectedRouteIsListedHere} is the guard
 * that keeps the table below honest: a new endpoint that nobody adds a row for fails this class rather
 * than quietly shipping unasserted.
 */
@WebMvcTest
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, WebSliceSupport.class,
        com.healthupgrades.upgrade.adapter.in.web.UpgradeWebMapper.class,
        com.healthupgrades.dashboard.adapter.in.web.DashboardWebMapper.class,
        com.healthupgrades.healtharea.adapter.in.web.HealthAreaWebMapper.class,
        com.healthupgrades.notification.adapter.in.web.NotificationWebMapper.class,
        com.healthupgrades.reflection.adapter.in.web.ReflectionWebMapper.class,
        com.healthupgrades.reminder.adapter.in.web.ReminderWebMapper.class,
        com.healthupgrades.tracking.adapter.in.web.TrackingWebMapper.class})
class AuthenticatedBoundaryTest {

    /** The routes that must work without a token. Everything else must not. */
    private static final Set<String> PUBLIC_ROUTES =
            Set.of("/api/auth/register", "/api/auth/login", "/actuator/", "/ws/");

    @Autowired MockMvc mockMvc;
    @Autowired RequestMappingHandlerMapping handlerMapping;

    @MockBean JwtTokenProvider tokenProvider;
    @MockBean UserDetailsServiceImpl userDetailsService;

    // The application services behind the controllers. Mocked: this class is about reaching them at all.
    @MockBean com.healthupgrades.auth.application.AuthService authService;
    @MockBean com.healthupgrades.upgrade.application.UpgradeService upgradeService;
    @MockBean com.healthupgrades.tracking.application.TrackingService trackingService;
    @MockBean com.healthupgrades.healtharea.application.HealthAreaService healthAreaService;
    @MockBean com.healthupgrades.reflection.application.ReflectionService reflectionService;
    @MockBean com.healthupgrades.reminder.application.ReminderService reminderService;
    @MockBean com.healthupgrades.notification.application.NotificationService notificationService;
    @MockBean com.healthupgrades.dashboard.application.port.in.DashboardQuery dashboardQuery;
    @MockBean com.healthupgrades.upgrade.application.port.out.UpgradeTrackingSummaryPort trackingSummaryPort;
    // No mock for TrackingConfigQuery / ProgressQuery / StreakQuery: TrackingService implements all
    // three, and a @MockBean of an interface an existing mock already satisfies *replaces* that mock
    // rather than adding one — which would leave TrackingService itself unresolvable.

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "GET,     /api/dashboard",
            "GET,     /api/upgrades",
            "POST,    /api/upgrades",
            "GET,     /api/upgrades/11111111-1111-1111-1111-111111111111",
            "PUT,     /api/upgrades/11111111-1111-1111-1111-111111111111",
            "DELETE,  /api/upgrades/11111111-1111-1111-1111-111111111111",
            "POST,    /api/upgrades/11111111-1111-1111-1111-111111111111/plan",
            "POST,    /api/upgrades/11111111-1111-1111-1111-111111111111/activate",
            "POST,    /api/upgrades/11111111-1111-1111-1111-111111111111/pause",
            "POST,    /api/upgrades/11111111-1111-1111-1111-111111111111/complete",
            "POST,    /api/upgrades/11111111-1111-1111-1111-111111111111/abandon",
            "POST,    /api/upgrades/11111111-1111-1111-1111-111111111111/reschedule",
            "GET,     /api/upgrades/11111111-1111-1111-1111-111111111111/progress",
            "POST,    /api/upgrades/11111111-1111-1111-1111-111111111111/progress",
            "GET,     /api/upgrades/11111111-1111-1111-1111-111111111111/streak",
            "GET,     /api/upgrades/11111111-1111-1111-1111-111111111111/tracking-config",
            "PUT,     /api/upgrades/11111111-1111-1111-1111-111111111111/tracking-config",
            "GET,     /api/upgrades/11111111-1111-1111-1111-111111111111/reflections",
            "POST,    /api/upgrades/11111111-1111-1111-1111-111111111111/reflections",
            "GET,     /api/upgrades/11111111-1111-1111-1111-111111111111/reminders",
            "POST,    /api/upgrades/11111111-1111-1111-1111-111111111111/reminders",
            "PUT,     /api/reminders/11111111-1111-1111-1111-111111111111",
            "DELETE,  /api/reminders/11111111-1111-1111-1111-111111111111",
            "GET,     /api/progress/today",
            "GET,     /api/progress/week",
            "GET,     /api/health-areas",
            "POST,    /api/health-areas",
            "GET,     /api/health-areas/11111111-1111-1111-1111-111111111111",
            "PUT,     /api/health-areas/11111111-1111-1111-1111-111111111111",
            "DELETE,  /api/health-areas/11111111-1111-1111-1111-111111111111",
            "GET,     /api/notifications",
            "GET,     /api/notifications/unread-count",
            "POST,    /api/notifications/11111111-1111-1111-1111-111111111111/read",
            "POST,    /api/notifications/read-all",
            "GET,     /api/auth/me",
    })
    void GivenNoToken_WhenAProtectedEndpointIsCalled_ThenTheRequestIsRejected(String method, String path)
            throws Exception {
        mockMvc.perform(json(request(HttpMethod.valueOf(method), path)))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "POST, /api/auth/register",
            "POST, /api/auth/login",
    })
    void GivenNoToken_WhenARegistrationOrLoginEndpointIsCalled_ThenSecurityLetsItThrough(String method, String path)
            throws Exception {
        // A visitor has no token by definition, so these two must never be behind the wall. The body is
        // empty and therefore invalid, which is exactly the point: a 400 proves the request reached the
        // controller's validation instead of being stopped by security.
        mockMvc.perform(json(request(HttpMethod.valueOf(method), path)).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void GivenAValidToken_WhenAProtectedEndpointIsCalled_ThenTheRequestIsLetThrough() {
        // The other half of the rule. Without this, a chain that rejected everything would pass every
        // case above.
        WebSliceSupport.authenticateAs(tokenProvider, userDetailsService, java.util.UUID.randomUUID());

        org.assertj.core.api.Assertions.assertThatCode(() ->
                mockMvc.perform(WebSliceSupport.bearer(
                                json(request(HttpMethod.GET, "/api/notifications"))))
                        .andExpect(status().isOk()))
                .doesNotThrowAnyException();
    }

    @Test
    void GivenAnInvalidToken_WhenAProtectedEndpointIsCalled_ThenTheRequestIsStillRejected() throws Exception {
        // A token the provider refuses leaves the request anonymous rather than authenticating it.
        org.mockito.Mockito.when(tokenProvider.validateToken(WebSliceSupport.VALID_TOKEN)).thenReturn(false);

        mockMvc.perform(WebSliceSupport.bearer(json(request(HttpMethod.GET, "/api/notifications"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void GivenTheApiSurface_WhenItIsEnumerated_ThenEveryProtectedRouteIsListedHere() {
        // Keeps the table above from going stale. A controller method added without a row here means an
        // endpoint whose authenticated boundary nothing checks, and this is what says so.
        Set<String> mapped = handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream())
                .filter(pattern -> !"/error".equals(pattern)) // Boot's error dispatch, not an API route
                .filter(pattern -> PUBLIC_ROUTES.stream().noneMatch(pattern::startsWith))
                .collect(Collectors.toSet());

        Set<String> listed = Set.of(
                "/api/dashboard",
                "/api/upgrades", "/api/upgrades/{id}",
                "/api/upgrades/{id}/plan", "/api/upgrades/{id}/activate", "/api/upgrades/{id}/pause",
                "/api/upgrades/{id}/complete", "/api/upgrades/{id}/abandon", "/api/upgrades/{id}/reschedule",
                "/api/upgrades/{upgradeId}/progress", "/api/upgrades/{upgradeId}/streak",
                "/api/upgrades/{upgradeId}/tracking-config",
                "/api/upgrades/{upgradeId}/reflections", "/api/upgrades/{upgradeId}/reminders",
                "/api/reminders/{id}",
                "/api/progress/today", "/api/progress/week",
                "/api/health-areas", "/api/health-areas/{id}",
                "/api/notifications", "/api/notifications/unread-count",
                "/api/notifications/{id}/read", "/api/notifications/read-all",
                "/api/auth/me");

        assertThat(mapped)
                .as("an endpoint exists that this class does not check the authenticated boundary of")
                .isEqualTo(listed);
    }

    /** Every request carries a JSON content type, so a body-taking endpoint is not refused for that. */
    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request) {
        return request.contentType(MediaType.APPLICATION_JSON).content("{}");
    }
}
