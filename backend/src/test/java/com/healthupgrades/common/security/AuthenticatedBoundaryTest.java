package com.healthupgrades.common.security;

import com.healthupgrades.support.WebSliceSupport;
import com.healthupgrades.user.domain.model.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FR-5, as one rule over the whole API rather than a line repeated in nine controller tests: every
 * endpoint except registration, login and the health checks requires a valid token.
 *
 * <p>This is the only test of that requirement, so the slice runs the real {@code SecurityConfig} and
 * the real {@code JwtAuthenticationFilter}; only the token check behind the filter is stubbed. Every service below is mocked, which is the point — a
 * request that reaches a mocked service and gets a null back has still got past security, and that is
 * what the assertion is about.
 *
 * <p>The rejection is <strong>401</strong> with a {@code WWW-Authenticate: Bearer} challenge and the
 * API's own error body, whether the request carried no token or one the server refused. It used to be a
 * bare 403, because no {@code AuthenticationEntryPoint} was configured, which left clients unable to tell
 * "sign in again" from "this is not yours" (#58).
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
            Set.of("/api/auth/register", "/api/auth/login", "/api/auth/refresh",
                    "/api/auth/logout", "/actuator/", "/ws/");

    /** The administration path used where one stands for the prefix. */
    private static final String ADMIN_PATH = "/api/admin/users";

    @Autowired MockMvc mockMvc;
    @Autowired RequestMappingHandlerMapping handlerMapping;

    @MockBean BearerTokenAuthenticator authenticator;
    @MockBean UserDetailsServiceImpl userDetailsService;

    // The application services behind the controllers. Mocked: this class is about reaching them at all.
    @MockBean com.healthupgrades.auth.application.AuthService authService;
    @MockBean com.healthupgrades.auth.application.port.in.SessionCommand sessionCommand;
    @MockBean com.healthupgrades.admin.application.port.in.AdminUserQuery adminUserQuery;
    @MockBean com.healthupgrades.admin.application.port.in.AdminUserCommand adminUserCommand;
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
            "GET,     /api/admin/users",
            "POST,    /api/admin/users/11111111-1111-1111-1111-111111111111/disable",
            "POST,    /api/admin/users/11111111-1111-1111-1111-111111111111/enable",
            "PUT,     /api/admin/users/11111111-1111-1111-1111-111111111111/role",
    })
    void GivenNoToken_WhenAProtectedEndpointIsCalled_ThenItIsRefusedAs401WithAChallenge(String method, String path)
            throws Exception {
        mockMvc.perform(json(request(HttpMethod.valueOf(method), path)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Authentication required"))
                .andExpect(jsonPath("$.path").value(path));
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "POST, /api/auth/register",
            "POST, /api/auth/login",
            "POST, /api/auth/refresh",
            "POST, /api/auth/logout",
    })
    void GivenNoToken_WhenAPublicAuthEndpointIsCalled_ThenSecurityLetsItThrough(String method, String path)
            throws Exception {
        // A visitor has no token by definition, and neither does a caller whose token has expired and
        // who is trying to refresh - so none of these four may sit behind the wall. Each is sent
        // something the controller itself refuses: an empty body for the first two, no
        // X-Requested-With header for the last two. A 400 proves the request reached the controller
        // rather than being stopped by security, which is the only thing this class is asserting.
        mockMvc.perform(json(request(HttpMethod.valueOf(method), path)).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void GivenAValidToken_WhenAProtectedEndpointIsCalled_ThenTheRequestIsLetThrough() {
        // The other half of the rule. Without this, a chain that rejected everything would pass every
        // case above.
        WebSliceSupport.authenticateAs(authenticator, java.util.UUID.randomUUID());

        org.assertj.core.api.Assertions.assertThatCode(() ->
                mockMvc.perform(WebSliceSupport.bearer(
                                json(request(HttpMethod.GET, "/api/notifications"))))
                        .andExpect(status().isOk()))
                .doesNotThrowAnyException();
    }

    @Test
    void GivenAnInvalidToken_WhenAProtectedEndpointIsCalled_ThenItIsRefusedAsAnInvalidToken() throws Exception {
        // A token the provider refuses leaves the request anonymous rather than authenticating it, and
        // the challenge says the token was the problem, which is what tells a client to sign in again.
        org.mockito.Mockito.when(authenticator.authenticate(WebSliceSupport.VALID_TOKEN))
                .thenReturn(java.util.Optional.empty());

        mockMvc.perform(WebSliceSupport.bearer(json(request(HttpMethod.GET, "/api/notifications"))))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""))
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    @Test
    void GivenTheApiSurface_WhenItIsEnumerated_ThenEveryProtectedMethodAndPathIsListedHere() {
        // Keeps the table above from going stale. Compared as METHOD + path rather than path alone:
        // adding a DELETE to an already-listed path leaves the set of paths unchanged, so a
        // path-only guard would stay green while a new endpoint shipped with its authenticated
        // boundary unasserted — which is the one thing this case exists to prevent.
        Set<String> mapped = handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getMethodsCondition().getMethods().stream()
                        .flatMap(method -> info.getPatternValues().stream()
                                .map(pattern -> method.name() + " " + pattern)))
                .filter(route -> !route.endsWith(" /error")) // Boot's error dispatch, not an API route
                .filter(route -> PUBLIC_ROUTES.stream().noneMatch(route.split(" ")[1]::startsWith))
                .collect(Collectors.toSet());

        Set<String> listed = Set.of(
                "GET /api/dashboard",
                "GET /api/upgrades", "POST /api/upgrades",
                "GET /api/upgrades/{id}", "PUT /api/upgrades/{id}", "DELETE /api/upgrades/{id}",
                "POST /api/upgrades/{id}/plan", "POST /api/upgrades/{id}/activate",
                "POST /api/upgrades/{id}/pause", "POST /api/upgrades/{id}/complete",
                "POST /api/upgrades/{id}/abandon", "POST /api/upgrades/{id}/reschedule",
                "GET /api/upgrades/{upgradeId}/progress", "POST /api/upgrades/{upgradeId}/progress",
                "GET /api/upgrades/{upgradeId}/streak",
                "GET /api/upgrades/{upgradeId}/tracking-config",
                "PUT /api/upgrades/{upgradeId}/tracking-config",
                "GET /api/upgrades/{upgradeId}/reflections", "POST /api/upgrades/{upgradeId}/reflections",
                "GET /api/upgrades/{upgradeId}/reminders", "POST /api/upgrades/{upgradeId}/reminders",
                "PUT /api/reminders/{id}", "DELETE /api/reminders/{id}",
                "GET /api/progress/today", "GET /api/progress/week",
                "GET /api/health-areas", "POST /api/health-areas",
                "GET /api/health-areas/{id}", "PUT /api/health-areas/{id}",
                "DELETE /api/health-areas/{id}",
                "GET /api/notifications", "GET /api/notifications/unread-count",
                "POST /api/notifications/{id}/read", "POST /api/notifications/read-all",
                "GET /api/auth/me",
                "GET /api/admin/users",
                "POST /api/admin/users/{id}/disable", "POST /api/admin/users/{id}/enable",
                "PUT /api/admin/users/{id}/role");

        assertThat(mapped)
                .as("an endpoint exists that this class does not check the authenticated boundary of")
                .isEqualTo(listed);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "GET,     /api/admin/users",
            "POST,    /api/admin/users/11111111-1111-1111-1111-111111111111/disable",
            "POST,    /api/admin/users/11111111-1111-1111-1111-111111111111/enable",
            "PUT,     /api/admin/users/11111111-1111-1111-1111-111111111111/role",
    })
    void GivenAnOrdinaryUser_WhenAnAdministrationPathIsCalled_ThenItIsRefusedAs403WithTheApiErrorBody(
            String method, String path) throws Exception {
        // Authenticated, and still refused. These four are the only endpoints in the API where
        // authorization is decided by something other than who owns the row, so the whole prefix is
        // checked rather than one path standing in for the rest.
        WebSliceSupport.authenticateAs(authenticator, UUID.randomUUID(), Role.USER);

        mockMvc.perform(WebSliceSupport.bearer(json(request(HttpMethod.valueOf(method), path))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.path").value(path));
    }

    @Test
    void GivenAnAdministrator_WhenAnAdministrationPathIsCalled_ThenSecurityLetsItThrough()
            throws Exception {
        // The other half of the matrix: the 403s above are about the role, not about the path being
        // unreachable. The service behind this one is mocked, so reaching it at all is the assertion.
        WebSliceSupport.authenticateAs(authenticator, UUID.randomUUID(), Role.ADMIN);
        when(adminUserQuery.list(anyInt(), anyInt()))
                .thenReturn(new com.healthupgrades.admin.application.AccountPage(List.of(), 0, 25, 0));

        mockMvc.perform(WebSliceSupport.bearer(json(request(HttpMethod.GET, ADMIN_PATH))))
                .andExpect(status().isOk());
    }

    /** Every request carries a JSON content type, so a body-taking endpoint is not refused for that. */
    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request) {
        return request.contentType(MediaType.APPLICATION_JSON).content("{}");
    }
}
