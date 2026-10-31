package com.healthupgrades.upgrade.adapter.in.web;

import static com.healthupgrades.support.WebSliceSupport.bearer;
import static org.assertj.core.api.Assertions.assertThat;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.healthupgrades.common.domain.exception.BusinessRuleException;
import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.common.security.BearerTokenAuthenticator;
import com.healthupgrades.common.security.JwtAuthenticationFilter;
import com.healthupgrades.common.security.SecurityConfig;
import com.healthupgrades.common.security.UserDetailsServiceImpl;
import com.healthupgrades.support.AnUpgrade;
import com.healthupgrades.support.WebSliceSupport;
import com.healthupgrades.upgrade.application.UpgradeService;
import com.healthupgrades.upgrade.application.port.in.UpgradeDetails;
import com.healthupgrades.upgrade.application.port.out.UpgradeTrackingSummary;
import com.healthupgrades.upgrade.application.port.out.UpgradeTrackingSummaryPort;
import com.healthupgrades.upgrade.domain.model.Difficulty;
import com.healthupgrades.upgrade.domain.model.HealthUpgrade;
import com.healthupgrades.upgrade.domain.model.UpgradeStatus;
import com.healthupgrades.upgrade.domain.model.UpgradeTrackingFrequency;
import com.healthupgrades.upgrade.domain.model.UpgradeTrackingType;
import com.healthupgrades.upgrade.domain.model.UpgradeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, UpgradeWebMapper.class, WebSliceSupport.class})
class UpgradeControllerTest {

    private static final String BASE = "/api/upgrades";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    UpgradeService service;

    @MockitoBean
    UpgradeTrackingSummaryPort trackingSummaries;

    @MockitoBean
    BearerTokenAuthenticator authenticator;

    @MockitoBean
    UserDetailsServiceImpl userDetailsService;

    private UUID userId;
    private final UUID upgradeId = UUID.randomUUID();

    @BeforeEach
    void authenticate() {
        userId = UUID.randomUUID();
        WebSliceSupport.authenticateAs(authenticator, userId);
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
    void GivenAValidBody_WhenAnUpgradeIsCreated_ThenLocationNamesTheRouteThatReadsItBack() throws Exception {
        // RFC 9110 section 15.3.2: a 201 says where the created resource is, which is GET /api/upgrades/{id}.
        when(service.create(eq(userId), any())).thenReturn(anUpgrade(UpgradeStatus.IDEA));

        mockMvc.perform(bearer(post(BASE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Cold showers\",\"type\":\"HABIT\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, BASE + "/" + upgradeId));
    }

    @Test
    void GivenEveryField_WhenAnUpgradeIsCreated_ThenEachReachesTheUseCaseUnderItsOwnName() throws Exception {
        // Every text field holds a different value, so two of them swapped on the way down shows here.
        UUID areaId = UUID.randomUUID();
        when(service.create(eq(userId), any())).thenReturn(anUpgrade(UpgradeStatus.IDEA));

        mockMvc.perform(bearer(post(BASE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"areaId\":\"" + areaId + "\",\"title\":\"Cold showers\","
                                + "\"description\":\"Thirty seconds cold\",\"type\":\"EXPERIMENT\","
                                + "\"difficulty\":\"HARD\",\"plannedStartDate\":\"2026-04-01\","
                                + "\"targetEndDate\":\"2026-05-01\",\"motivation\":\"Wake up faster\","
                                + "\"successCriteria\":\"Thirty days in a row\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<UpgradeDetails> details = ArgumentCaptor.forClass(UpgradeDetails.class);
        verify(service).create(eq(userId), details.capture());
        assertThat(details.getValue())
                .isEqualTo(new UpgradeDetails(
                        areaId,
                        "Cold showers",
                        "Thirty seconds cold",
                        UpgradeType.EXPERIMENT,
                        Difficulty.HARD,
                        LocalDate.of(2026, 4, 1),
                        LocalDate.of(2026, 5, 1),
                        "Wake up faster",
                        "Thirty days in a row"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = UpgradeType.class,
            names = {
                "HABIT",
                "ONE_TIME_ACTION",
                "PRODUCT_REPLACEMENT",
                "ROUTINE",
                "GOAL",
                "EXPERIMENT",
                "LEARNING_TASK",
                "MEDICAL_PREVENTIVE"
            })
    void GivenEachKindFr9Lists_WhenAnUpgradeOfThatKindIsCreated_ThenItReachesTheUseCaseAsThatKind(UpgradeType kind)
            throws Exception {
        // FR-9's eight kinds by name rather than the whole enum: the deprecated PROTOCOL is not one of
        // them, and a kind dropped from the enum fails this source instead of silently leaving the list.
        when(service.create(eq(userId), any())).thenReturn(anUpgrade(UpgradeStatus.IDEA));

        mockMvc.perform(bearer(post(BASE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Cold showers\",\"type\":\"" + kind.name() + "\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<UpgradeDetails> details = ArgumentCaptor.forClass(UpgradeDetails.class);
        verify(service).create(eq(userId), details.capture());
        assertThat(details.getValue().type())
                .as("the kind the use case is asked to create")
                .isEqualTo(kind);
    }

    @Test
    void GivenTheRetiredProtocolType_WhenAnUpgradeIsCreated_ThenTheDomainsRefusalAnswers422() throws Exception {
        // FR-9: PROTOCOL still binds, since a row may carry it and a PUT may send it back, so the
        // refusal is the aggregate's, not the body's. Stubbed as HealthUpgrade.create refuses it; what
        // this pins is that it reaches the use case as PROTOCOL and that the refusal is a 422, not a 400.
        String refusal = "That kind of upgrade is retired; choose another";
        when(service.create(eq(userId), any())).thenThrow(new BusinessRuleException(refusal));

        mockMvc.perform(bearer(post(BASE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Cold showers\",\"type\":\"PROTOCOL\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(refusal));

        ArgumentCaptor<UpgradeDetails> details = ArgumentCaptor.forClass(UpgradeDetails.class);
        verify(service).create(eq(userId), details.capture());
        assertThat(details.getValue().type().isRetired())
                .as("the use case is asked for the retired kind")
                .isTrue();
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
    void GivenATitleCarryingANulCharacter_WhenAnUpgradeIsCreated_ThenItAnswers400BeforeTheUseCase() throws Exception {
        // S5.B5: PostgreSQL refuses U+0000 in text, so it reached the catch-all as a 500 and an ERROR
        // line, alert noise anybody signed in could make on demand. Refused at the body instead.
        mockMvc.perform(bearer(post(BASE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Walk\\u0000daily\",\"type\":\"HABIT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Malformed request body"));

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
    void GivenAnUpgradeWithATrackingConfig_WhenItIsRead_ThenTheResponseCarriesEveryFieldOfIt() throws Exception {
        // FR-15: a list view needs no second call, so the configuration rides on the upgrade. Each field
        // has a value no other field shares, so a field mapped into the wrong slot fails here.
        UUID configId = UUID.randomUUID();
        when(service.getOwnedUpgrade(userId, upgradeId)).thenReturn(anUpgrade(UpgradeStatus.ACTIVE));
        when(trackingSummaries.findByUpgradeIds(List.of(upgradeId)))
                .thenReturn(Map.of(
                        upgradeId,
                        new UpgradeTrackingSummary(
                                configId,
                                upgradeId,
                                UpgradeTrackingType.NUMERIC,
                                UpgradeTrackingFrequency.WEEKLY,
                                10_000.0,
                                "steps",
                                true)));

        mockMvc.perform(bearer(get(BASE + "/" + upgradeId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackingConfig.id").value(configId.toString()))
                .andExpect(jsonPath("$.trackingConfig.upgradeId").value(upgradeId.toString()))
                .andExpect(jsonPath("$.trackingConfig.trackingType").value("NUMERIC"))
                .andExpect(jsonPath("$.trackingConfig.frequency").value("WEEKLY"))
                .andExpect(jsonPath("$.trackingConfig.targetNumericValue").value(10_000.0))
                .andExpect(jsonPath("$.trackingConfig.targetUnit").value("steps"))
                .andExpect(jsonPath("$.trackingConfig.requiredDaily").value(true));
    }

    // The slice runs on the application's system clock, so the dates below sit far enough either side of
    // any day it could run on.

    @Test
    void GivenAnActiveUpgradeLongPastItsTargetDate_WhenItIsRead_ThenItIsReportedOverdue() throws Exception {
        when(service.getOwnedUpgrade(userId, upgradeId))
                .thenReturn(AnUpgrade.ownedBy(userId)
                        .id(upgradeId)
                        .status(UpgradeStatus.ACTIVE)
                        .targetEndDate(LocalDate.of(2000, 1, 1))
                        .build());

        mockMvc.perform(bearer(get(BASE + "/" + upgradeId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdue").value(true));
    }

    @Test
    void GivenAnActiveUpgradeLongBeforeItsTargetDate_WhenItIsRead_ThenItIsNotReportedOverdue() throws Exception {
        when(service.getOwnedUpgrade(userId, upgradeId))
                .thenReturn(AnUpgrade.ownedBy(userId)
                        .id(upgradeId)
                        .status(UpgradeStatus.ACTIVE)
                        .targetEndDate(LocalDate.of(2999, 1, 1))
                        .build());

        mockMvc.perform(bearer(get(BASE + "/" + upgradeId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdue").value(false));
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
        mockMvc.perform(bearer(get(BASE + "/not-a-uuid"))).andExpect(status().isBadRequest());
    }

    @Test
    void GivenAStatusFilter_WhenUpgradesAreListed_ThenItIsPassedThroughAsAnEnum() throws Exception {
        when(service.findAll(userId, UpgradeStatus.ACTIVE, null, null, null)).thenReturn(List.of());

        mockMvc.perform(bearer(get(BASE).param("status", "ACTIVE"))).andExpect(status().isOk());

        verify(service).findAll(userId, UpgradeStatus.ACTIVE, null, null, null);
    }

    @Test
    void GivenATypeFilter_WhenUpgradesAreListed_ThenItIsPassedThroughAsAnEnum() throws Exception {
        when(service.findAll(userId, null, UpgradeType.GOAL, null, null)).thenReturn(List.of());

        mockMvc.perform(bearer(get(BASE).param("type", "GOAL"))).andExpect(status().isOk());

        verify(service).findAll(userId, null, UpgradeType.GOAL, null, null);
    }

    @Test
    void GivenAnAreaFilter_WhenUpgradesAreListed_ThenItIsPassedThroughAsAnId() throws Exception {
        UUID areaId = UUID.randomUUID();
        when(service.findAll(userId, null, null, areaId, null)).thenReturn(List.of());

        mockMvc.perform(bearer(get(BASE).param("areaId", areaId.toString()))).andExpect(status().isOk());

        verify(service).findAll(userId, null, null, areaId, null);
    }

    @Test
    void GivenADifficultyFilter_WhenUpgradesAreListed_ThenItIsPassedThroughAsAnEnum() throws Exception {
        when(service.findAll(userId, null, null, null, Difficulty.HARD)).thenReturn(List.of());

        mockMvc.perform(bearer(get(BASE).param("difficulty", "HARD"))).andExpect(status().isOk());

        verify(service).findAll(userId, null, null, null, Difficulty.HARD);
    }

    @Test
    void GivenAFilterValueThatIsNotInTheEnum_WhenUpgradesAreListed_ThenItAnswers400RatherThan500() throws Exception {
        mockMvc.perform(bearer(get(BASE).param("status", "NOPE"))).andExpect(status().isBadRequest());
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
        when(service.pause(userId, upgradeId)).thenThrow(new BusinessRuleException("Cannot pause a COMPLETED upgrade"));

        mockMvc.perform(bearer(post(BASE + "/" + upgradeId + "/pause")))
                .andExpect(status().isUnprocessableContent())
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
                .andExpect(status().isUnprocessableContent());
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
    void GivenATitleLongerThanItsColumn_WhenAnUpgradeIsUpdated_ThenItAnswers400NamingTheField() throws Exception {
        // BR-16 on the PUT as well as the POST: the same column, reached by a different route.
        mockMvc.perform(bearer(put(BASE + "/" + upgradeId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + "a".repeat(UpgradeRequest.TITLE_MAX + 1) + "\",\"type\":\"HABIT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.title").exists());

        verify(service, never()).update(any(), any(), any());
    }

    @Test
    void GivenARescheduleWithNoDate_WhenItIsSent_ThenItAnswers400AndNothingIsRescheduled() throws Exception {
        mockMvc.perform(bearer(post(BASE + "/" + upgradeId + "/reschedule"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.newDate").exists());

        verify(service, never()).reschedule(any(), any(), any());
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
                .when(service)
                .delete(userId, upgradeId);

        mockMvc.perform(bearer(delete(BASE + "/" + upgradeId))).andExpect(status().isNotFound());
    }

    @Test
    void GivenTheAuthenticatedPrincipal_WhenUpgradesAreListed_ThenItsIdIsWhatTheServiceIsScopedTo() throws Exception {
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
