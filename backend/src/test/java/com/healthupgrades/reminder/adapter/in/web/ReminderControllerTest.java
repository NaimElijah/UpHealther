package com.healthupgrades.reminder.adapter.in.web;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.healthupgrades.common.domain.exception.BusinessRuleException;
import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.common.security.BearerTokenAuthenticator;
import com.healthupgrades.common.security.JwtAuthenticationFilter;
import com.healthupgrades.common.security.SecurityConfig;
import com.healthupgrades.common.security.UserDetailsServiceImpl;
import com.healthupgrades.reminder.application.ReminderService;
import com.healthupgrades.reminder.application.port.in.ReminderSchedule;
import com.healthupgrades.reminder.domain.model.Reminder;
import com.healthupgrades.reminder.domain.model.ReminderDays;
import com.healthupgrades.support.WebSliceSupport;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP contract for reminders: FR-25 (attach one with a time and a day filter) and FR-26
 * (reschedule, enable, disable, delete).
 *
 * <p>The day list is the interesting part of the wire format. It goes out as an array of tokens, and an
 * unrecognisable one is a 422 rather than a silently narrowed schedule — BR-12 says a bad day is
 * rejected, never ignored, and ignoring it would leave a reminder firing on days nobody chose.
 */
@WebMvcTest(ReminderController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, ReminderWebMapper.class, WebSliceSupport.class})
class ReminderControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ReminderService service;

    @MockitoBean
    BearerTokenAuthenticator authenticator;

    @MockitoBean
    UserDetailsServiceImpl userDetailsService;

    private static final LocalTime NINE_AM = LocalTime.of(9, 0);

    private UUID userId;
    private final UUID upgradeId = UUID.randomUUID();
    private final UUID reminderId = UUID.randomUUID();
    private String upgradeScoped;
    private String reminderScoped;

    @BeforeEach
    void authenticate() {
        userId = UUID.randomUUID();
        upgradeScoped = "/api/upgrades/" + upgradeId + "/reminders";
        reminderScoped = "/api/reminders/" + reminderId;
        WebSliceSupport.authenticateAs(authenticator, userId);
    }

    @Test
    void GivenATimeAndDays_WhenAReminderIsAttached_ThenLocationNamesTheReminderItself() throws Exception {
        // RFC 9110 section 15.3.2: Location names the created resource. A reminder is addressed by its own
        // id once it exists (PUT and DELETE /api/reminders/{id}), not by the upgrade it was attached to.
        when(service.create(eq(userId), eq(upgradeId), any())).thenReturn(aReminder(true));

        mockMvc.perform(bearer(post(upgradeScoped))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reminderTime\":\"09:00\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, reminderScoped));
    }

    @Test
    void GivenATimeAndDays_WhenAReminderIsAttached_ThenItAnswers201CarryingTheDayTokens() throws Exception {
        when(service.create(eq(userId), eq(upgradeId), any())).thenReturn(aReminder(true));

        mockMvc.perform(bearer(post(upgradeScoped))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reminderTime\":\"09:00\",\"daysOfWeek\":[\"MON\",\"WED\"],\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reminderTime").value("09:00:00"))
                .andExpect(jsonPath("$.daysOfWeek[0]").value("MON"))
                .andExpect(jsonPath("$.enabled").value(true));

        verify(service).create(userId, upgradeId, new ReminderSchedule(NINE_AM, List.of("MON", "WED"), true));
    }

    @Test
    void GivenNoTime_WhenAReminderIsAttached_ThenItAnswers400AndNothingIsAttached() throws Exception {
        mockMvc.perform(bearer(post(upgradeScoped))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"daysOfWeek\":[\"MON\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.reminderTime").exists());

        verify(service, never()).create(any(), any(), any());
    }

    @Test
    void GivenAnUnrecognisableDay_WhenAReminderIsAttached_ThenItAnswers422RatherThanNarrowingTheSchedule()
            throws Exception {
        when(service.create(eq(userId), eq(upgradeId), any()))
                .thenThrow(new BusinessRuleException("Unrecognised day: FUNDAY"));

        mockMvc.perform(bearer(post(upgradeScoped))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reminderTime\":\"09:00\",\"daysOfWeek\":[\"FUNDAY\"]}"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void GivenAnOwnedUpgrade_WhenItsRemindersAreListed_ThenTheyAnswer200() throws Exception {
        when(service.getForUpgrade(userId, upgradeId)).thenReturn(List.of(aReminder(true), aReminder(false)));

        mockMvc.perform(bearer(get(upgradeScoped)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)));
    }

    @Test
    void GivenAnOwnedReminder_WhenItIsRescheduled_ThenItAnswers200WithTheNewSchedule() throws Exception {
        when(service.update(eq(userId), eq(reminderId), any())).thenReturn(aReminder(false));

        mockMvc.perform(bearer(put(reminderScoped))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reminderTime\":\"09:00\",\"daysOfWeek\":[\"MON\"],\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        verify(service).update(userId, reminderId, new ReminderSchedule(NINE_AM, List.of("MON"), false));
    }

    @Test
    void GivenNoEnabledFlag_WhenAReminderIsRescheduled_ThenTheUseCaseIsToldToLeaveItsStateAlone() throws Exception {
        // A null flag is what keeps rescheduling a disabled reminder from switching it back on, so the
        // mapping must pass the absence through rather than default it.
        when(service.update(eq(userId), eq(reminderId), any())).thenReturn(aReminder(false));

        mockMvc.perform(bearer(put(reminderScoped))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reminderTime\":\"09:00\",\"daysOfWeek\":[\"SAT\"]}"))
                .andExpect(status().isOk());

        verify(service).update(userId, reminderId, new ReminderSchedule(NINE_AM, List.of("SAT"), null));
    }

    @Test
    void GivenAReminderOnAnotherUsersUpgrade_WhenItIsRescheduled_ThenItAnswers404AndNot403() throws Exception {
        when(service.update(eq(userId), eq(reminderId), any()))
                .thenThrow(new ResourceNotFoundException("Reminder not found: " + reminderId));

        mockMvc.perform(bearer(put(reminderScoped))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reminderTime\":\"09:00\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void GivenAnOwnedReminder_WhenItIsDeleted_ThenItAnswers204WithNoBody() throws Exception {
        mockMvc.perform(bearer(delete(reminderScoped)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(service).delete(userId, reminderId);
    }

    @Test
    void GivenAReminderOnAnotherUsersUpgrade_WhenItIsDeleted_ThenItAnswers404AndNothingIsDeleted() throws Exception {
        doThrow(new ResourceNotFoundException("Reminder not found: " + reminderId))
                .when(service)
                .delete(userId, reminderId);

        mockMvc.perform(bearer(delete(reminderScoped))).andExpect(status().isNotFound());
    }

    private Reminder aReminder(boolean enabled) {
        return Reminder.builder()
                .id(reminderId)
                .upgradeId(upgradeId)
                .reminderTime(NINE_AM)
                .daysOfWeek(ReminderDays.of(List.of("MON", "WED")).toStorageValue())
                .enabled(enabled)
                .build();
    }
}
