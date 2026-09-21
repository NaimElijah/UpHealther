package com.healthupgrades.admin.adapter.in.web;

import com.healthupgrades.admin.application.AccountPage;
import com.healthupgrades.admin.application.port.in.AdminUserCommand;
import com.healthupgrades.admin.application.port.in.AdminUserQuery;
import com.healthupgrades.common.domain.exception.BusinessRuleException;
import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.common.security.BearerTokenAuthenticator;
import com.healthupgrades.common.security.JwtAuthenticationFilter;
import com.healthupgrades.common.security.SecurityConfig;
import com.healthupgrades.common.security.UserDetailsServiceImpl;
import com.healthupgrades.support.AUser;
import com.healthupgrades.support.WebSliceSupport;
import com.healthupgrades.user.domain.model.Role;
import com.healthupgrades.user.domain.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static com.healthupgrades.support.WebSliceSupport.bearer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of account administration, and the wall around it.
 *
 * <p>Runs the real security chain, so the 403 below is Spring Security's answer rather than an
 * assertion about a mock. That matters more here than on any other controller: these are the only
 * endpoints in the application whose answer depends on something other than who owns the row, and the
 * only place where getting authorization wrong hands one user power over every other.
 *
 * <p>The response shape is asserted for what it does <em>not</em> carry as much as what it does. An
 * administrator sees that an account exists, what it may do and whether it is on — never a password
 * hash, and nothing at all about the person's health records (ADR-016).
 */
@WebMvcTest(AdminUserController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, WebSliceSupport.class})
class AdminUserControllerTest {

    private static final UUID ADMIN_ID = UUID.fromString("0f2c8f5a-2a4e-4a1d-8f0a-3c5b9d1e77a1");
    private static final UUID TARGET_ID = UUID.fromString("6b1f0f4c-9a2d-4c3e-9b7a-1d2e3f4a5b6c");

    @Autowired MockMvc mockMvc;

    @MockBean AdminUserQuery adminUserQuery;
    @MockBean AdminUserCommand adminUserCommand;
    @MockBean BearerTokenAuthenticator authenticator;
    @MockBean UserDetailsServiceImpl userDetailsService;

    @Test
    void GivenAnAdministrator_WhenAccountsAreListed_ThenItAnswers200WithThePageAndItsTotal() throws Exception {
        asAdmin();
        when(adminUserQuery.list(0, 25))
                .thenReturn(new AccountPage(List.of(AUser.withId(TARGET_ID)), 0, 25, 137L));

        mockMvc.perform(bearer(get("/api/admin/users")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts[0].id").value(TARGET_ID.toString()))
                .andExpect(jsonPath("$.accounts[0].role").value("USER"))
                .andExpect(jsonPath("$.accounts[0].enabled").value(true))
                .andExpect(jsonPath("$.accounts[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$.total").value(137));
    }

    @Test
    void GivenAnOrdinaryUser_WhenAccountsAreListed_ThenItIsRefusedAs403AndTheServiceIsNotCalled()
            throws Exception {
        // The wall, and the assertion that matters as much as the status: the refusal happens before
        // any account is read, not after.
        WebSliceSupport.authenticateAs(authenticator, ADMIN_ID, Role.USER);

        mockMvc.perform(bearer(get("/api/admin/users")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        verify(adminUserQuery, never()).list(anyPage(), anyPage());
    }

    @Test
    void GivenNoToken_WhenAccountsAreListed_ThenItIsRefusedAs401RatherThan403() throws Exception {
        // 401, not 403: nobody has identified themselves yet, so "sign in" is the honest answer and the
        // one the SPA reacts to.
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void GivenAnAdministrator_WhenAnAccountIsDisabled_ThenItAnswers200WithTheAccountSwitchedOff()
            throws Exception {
        asAdmin();
        User disabled = AUser.withId(TARGET_ID);
        disabled.disable();
        when(adminUserCommand.disable(ADMIN_ID, TARGET_ID)).thenReturn(disabled);

        mockMvc.perform(bearer(post("/api/admin/users/" + TARGET_ID + "/disable")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void GivenAnAdministrator_WhenAnAccountIsEnabled_ThenItAnswers200WithTheAccountSwitchedOn()
            throws Exception {
        asAdmin();
        when(adminUserCommand.enable(ADMIN_ID, TARGET_ID)).thenReturn(AUser.withId(TARGET_ID));

        mockMvc.perform(bearer(post("/api/admin/users/" + TARGET_ID + "/enable")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void GivenAnAdministrator_WhenARoleIsGranted_ThenItAnswers200WithTheNewRole() throws Exception {
        asAdmin();
        when(adminUserCommand.changeRole(ADMIN_ID, TARGET_ID, Role.ADMIN))
                .thenReturn(AUser.withRole(TARGET_ID, Role.ADMIN));

        mockMvc.perform(bearer(put("/api/admin/users/" + TARGET_ID + "/role"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void GivenNoRoleInTheBody_WhenARoleChangeIsSubmitted_ThenItIsRefusedAs400() throws Exception {
        asAdmin();

        mockMvc.perform(bearer(put("/api/admin/users/" + TARGET_ID + "/role"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(adminUserCommand, never()).changeRole(any(), any(), any());
    }

    @Test
    void GivenTheirOwnAccount_WhenAnAdministratorDisablesIt_ThenTheRefusalSurfacesAs422() throws Exception {
        asAdmin();
        when(adminUserCommand.disable(ADMIN_ID, ADMIN_ID))
                .thenThrow(new BusinessRuleException("An administrator cannot change their own account"));

        mockMvc.perform(bearer(post("/api/admin/users/" + ADMIN_ID + "/disable")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    void GivenAnAccountThatDoesNotExist_WhenItIsDisabled_ThenTheRefusalSurfacesAs404() throws Exception {
        asAdmin();
        when(adminUserCommand.disable(eq(ADMIN_ID), any()))
                .thenThrow(new ResourceNotFoundException("User not found"));

        mockMvc.perform(bearer(post("/api/admin/users/" + UUID.randomUUID() + "/disable")))
                .andExpect(status().isNotFound());
    }

    @Test
    void GivenAPageSizeBeyondTheMaximum_WhenAccountsAreListed_ThenItIsRefusedAs400() throws Exception {
        // The bound exists because the parameter is a caller's. Without it, one request reads the whole
        // account table — a slow query, and a generous thing to hand anybody holding an admin token.
        asAdmin();

        mockMvc.perform(bearer(get("/api/admin/users").param("size", "1000")))
                .andExpect(status().isBadRequest());

        verify(adminUserQuery, never()).list(anyPage(), anyPage());
    }

    /** Authenticates the slice's bearer token as an administrator. */
    private void asAdmin() {
        WebSliceSupport.authenticateAs(authenticator, ADMIN_ID, Role.ADMIN);
    }

    private static int anyPage() {
        return org.mockito.ArgumentMatchers.anyInt();
    }
}
