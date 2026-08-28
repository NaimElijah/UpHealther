package com.healthupgrades.upgrade.adapter.in.web;

import com.healthupgrades.common.domain.exception.BusinessRuleException;
import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.common.security.JwtAuthenticationFilter;
import com.healthupgrades.common.security.JwtTokenProvider;
import com.healthupgrades.common.security.SecurityConfig;
import com.healthupgrades.common.security.UserDetailsServiceImpl;
import com.healthupgrades.support.AnUpgrade;
import com.healthupgrades.support.WebSliceSupport;
import com.healthupgrades.upgrade.application.UpgradeService;
import com.healthupgrades.upgrade.application.port.out.UpgradeTrackingSummaryPort;
import com.healthupgrades.upgrade.domain.model.Difficulty;
import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import com.healthupgrades.upgrade.domain.model.UpgradeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.healthupgrades.support.WebSliceSupport.bearer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of the API's central resource: the status each endpoint answers with, what a
 * malformed request gets, and the shape of the body.
 *
 * <p>Two of these are requirements rather than conveniences. <strong>BR-15</strong> — a row owned by
 * somebody else comes back <em>404, never 403</em>, so the response says nothing about what exists.
 * <strong>NFR-7</strong> — a failed constraint is a 400 carrying a field-to-message map, an illegal
 * lifecycle move is a 422, and an unbindable body is a 400 rather than the 500 that issue #22 was.
 *
 * <p>The real {@code SecurityConfig}, {@code JwtAuthenticationFilter}, {@code GlobalExceptionHandler}
 * and {@code UpgradeWebMapper} are all in the slice; only the application service is mocked. The
 * mapper matters: the response shape is what the frontend's {@code Upgrade} type mirrors, so a mocked
 * mapper would make every shape assertion here a tautology.
 */
@WebMvcTest(UpgradeController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class,
        UpgradeWebMapper.class, WebSliceSupport.class})
class UpgradeControllerTest {

    private static final String BASE = "/api/upgrades";

    @Autowired MockMvc mockMvc;

    @MockBean UpgradeService service;
    @MockBean UpgradeTrackingSummaryPort trackingSummaries;
    @MockBean JwtTokenProvider tokenProvider;
    @MockBean UserDetailsServiceImpl userDetailsService;

    private UUID userId;
    private final UUID upgradeId = UUID.randomUUID();

    @BeforeEach
    void authenticate() {
        userId = UUID.randomUUID();
        WebSliceSupport.authenticateAs(tokenProvider, userDetailsService, userId);
        when(trackingSummaries.findByUpgradeIds(any())).thenReturn(Map.of());
    }

    // ---- Creating ----

    @Test
    void GivenAValidBody_WhenAnUpgradeIsCreated_ThenItAnswers201WithTheCreatedUpgrade() throws Exception {
        when(service.create(eq(userId), any())).thenReturn(anUpgrade(UpgradeStatus.IDEA));

        mockMvc.perform(bearer(post(BASE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Cold showers\",\"type\":\"HABIT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value(AnUpgrade.TITLE))
                .andExpect(jsonPath("$.status").value("IDEA"));
    }

    @Test
    void GivenABodyWithNoTitle_WhenAnUpgradeIsCreated_ThenItAnswers400NamingTheField() throws Exception {
        // NFR-7: a failed constraint is a 400 whose body maps the field to its message, so a form can
        // show the error next to the input rather than as a banner.
        mockMvc.perform(bearer(post(BASE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"HABIT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.title").exists());

        verify(service, never()).create(any(), any());
    }

    @Test
    void GivenATitleLongerThanItsColumn_WhenAnUpgradeIsCreated_ThenItAnswers400NamingTheField() throws Exception {
        // health_upgrades.title is VARCHAR(255), which is where the 255 comes from. Unbounded here, an
        // over-long title reached the flush in production and came back 500 - a server fault reported
        // for input the caller could have corrected. This slice has no database and cannot see that
        // flush; what it pins is the boundary contract that makes it unreachable. BR-16.
        mockMvc.perform(bearer(post(BASE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + "a".repeat(UpgradeRequest.TITLE_MAX + 1) + "\",\"type\":\"HABIT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.title").exists());

        verify(service, never()).create(any(), any());
    }

    @Test
    void GivenATitleExactlyAsLongAsItsColumn_WhenAnUpgradeIsCreated_ThenItIsAccepted() throws Exception {
        // The bound is inclusive: 255 is the longest the column holds, so refusing it would be a bug of
        // the opposite sign.
        when(service.create(eq(userId), any())).thenReturn(anUpgrade(UpgradeStatus.IDEA));

        mockMvc.perform(bearer(post(BASE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + "a".repeat(UpgradeRequest.TITLE_MAX) + "\",\"type\":\"HABIT\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void GivenABodyThatIsNotJson_WhenAnUpgradeIsCreated_ThenItAnswers400RatherThan500() throws Exception {
        // The shape of issue #22: this used to be swallowed as a server fault.
        mockMvc.perform(bearer(post(BASE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void GivenAnUnknownUpgradeType_WhenAnUpgradeIsCreated_ThenItAnswers400RatherThan500() throws Exception {
        // The eight types the UI offers must all bind, and anything else must be refused cleanly —
        // issue #19 was five of them failing with a 500.
        mockMvc.perform(bearer(post(BASE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Cold showers\",\"type\":\"NOT_A_TYPE\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---- Reading ----

    @Test
    void GivenAnOwnedUpgrade_WhenItIsRead_ThenTheResponseCarriesTheFieldsTheFrontendReads() throws Exception {
        when(service.getOwnedUpgrade(userId, upgradeId)).thenReturn(anUpgrade(UpgradeStatus.ACTIVE));

        mockMvc.perform(bearer(get(BASE + "/" + upgradeId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(upgradeId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.type").value("HABIT"))
                .andExpect(jsonPath("$.overdue").exists())
                .andExpect(jsonPath("$.trackingConfig").doesNotExist());
    }

    @Test
    void GivenAnUpgradeOwnedBySomebodyElse_WhenItIsRead_ThenItAnswers404AndNot403() throws Exception {
        // BR-15. A 403 would confirm the row exists; a 404 says nothing at all.
        when(service.getOwnedUpgrade(userId, upgradeId))
                .thenThrow(new ResourceNotFoundException("Upgrade not found: " + upgradeId));

        mockMvc.perform(bearer(get(BASE + "/" + upgradeId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void GivenAnIdThatIsNotAUuid_WhenAnUpgradeIsRead_ThenItAnswers400RatherThan500() throws Exception {
        mockMvc.perform(bearer(get(BASE + "/not-a-uuid")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void GivenAStatusFilter_WhenUpgradesAreListed_ThenItIsPassedThroughAsAnEnum() throws Exception {
        when(service.findAll(userId, UpgradeStatus.ACTIVE, null, null, null)).thenReturn(List.of());

        mockMvc.perform(bearer(get(BASE).param("status", "ACTIVE")))
                .andExpect(status().isOk());

        verify(service).findAll(userId, UpgradeStatus.ACTIVE, null, null, null);
    }

    @Test
    void GivenAFilterValueThatIsNotInTheEnum_WhenUpgradesAreListed_ThenItAnswers400RatherThan500() throws Exception {
        mockMvc.perform(bearer(get(BASE).param("status", "NOPE")))
                .andExpect(status().isBadRequest());
    }

    // ---- The lifecycle sub-resources ----

    @Test
    void GivenAnOwnedIdea_WhenItIsPlanned_ThenItAnswers200WithTheNewState() throws Exception {
        when(service.plan(eq(userId), eq(upgradeId), any())).thenReturn(anUpgrade(UpgradeStatus.PLANNED));

        mockMvc.perform(bearer(post(BASE + "/" + upgradeId + "/plan"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plannedStartDate\":\"2026-04-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PLANNED"));
    }

    @Test
    void GivenAPlanRequestWithNoDate_WhenItIsSent_ThenItAnswers400AndNothingIsPlanned() throws Exception {
        mockMvc.perform(bearer(post(BASE + "/" + upgradeId + "/plan"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).plan(any(), any(), any());
    }

    @Test
    void GivenAnActivationWithNoStartDate_WhenItIsSent_ThenItIsAcceptedAndTheServerChoosesTheDate() throws Exception {
        // ActivateRequest's date is optional by design — the common case is "starting now".
        when(service.activate(eq(userId), eq(upgradeId), eq(null))).thenReturn(anUpgrade(UpgradeStatus.ACTIVE));

        mockMvc.perform(bearer(post(BASE + "/" + upgradeId + "/activate"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void GivenAnIllegalLifecycleMove_WhenItIsAttempted_ThenItAnswers422WithTheReason() throws Exception {
        // BR-2 and BR-3 reach the client as 422 — the request was understood and the rules forbid it,
        // which is a different thing from a malformed request (400) or a missing row (404).
        when(service.pause(userId, upgradeId))
                .thenThrow(new BusinessRuleException("Cannot pause a COMPLETED upgrade"));

        mockMvc.perform(bearer(post(BASE + "/" + upgradeId + "/pause")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Cannot pause a COMPLETED upgrade"));
    }

    @Test
    void GivenTheHardLimitWouldBeExceeded_WhenAnUpgradeIsActivated_ThenItAnswers422() throws Exception {
        // BR-5 reaching the client. The limit is a rule, not a validation failure.
        when(service.activate(eq(userId), eq(upgradeId), any()))
                .thenThrow(new BusinessRuleException("At most 3 HARD upgrades may be active"));

        mockMvc.perform(bearer(post(BASE + "/" + upgradeId + "/activate"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---- Updating and deleting ----

    @Test
    void GivenAValidBody_WhenAnUpgradeIsUpdated_ThenItAnswers200WithTheSavedUpgrade() throws Exception {
        when(service.update(eq(userId), eq(upgradeId), any())).thenReturn(anUpgrade(UpgradeStatus.ACTIVE));

        mockMvc.perform(bearer(put(BASE + "/" + upgradeId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Cold showers\",\"type\":\"HABIT\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void GivenAnOwnedUpgrade_WhenItIsDeleted_ThenItAnswers204WithNoBody() throws Exception {
        mockMvc.perform(bearer(delete(BASE + "/" + upgradeId)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(service).delete(userId, upgradeId);
    }

    @Test
    void GivenAnUpgradeOwnedBySomebodyElse_WhenItIsDeleted_ThenItAnswers404() throws Exception {
        doThrow(new ResourceNotFoundException("Upgrade not found: " + upgradeId))
                .when(service).delete(userId, upgradeId);

        mockMvc.perform(bearer(delete(BASE + "/" + upgradeId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void GivenTheAuthenticatedPrincipal_WhenAnyEndpointIsCalled_ThenItsIdIsWhatTheServiceIsScopedTo() throws Exception {
        // The one thing every endpoint shares: ownership comes from the token's principal, never from
        // the request. A controller reading a user id out of the body or a header would break BR-15
        // everywhere at once, and this is the assertion that would notice.
        when(service.findAll(any(), any(), any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(bearer(get(BASE)).param("userId", UUID.randomUUID().toString()))
                .andExpect(status().isOk());

        verify(service).findAll(eq(userId), any(), any(), any(), any());
    }

    private HealthUpgrade anUpgrade(UpgradeStatus status) {
        return AnUpgrade.ownedBy(userId)
                .id(upgradeId)
                .type(UpgradeType.HABIT)
                .difficulty(Difficulty.MEDIUM)
                .status(status)
                .build();
    }
}
