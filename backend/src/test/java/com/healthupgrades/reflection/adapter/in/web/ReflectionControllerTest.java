package com.healthupgrades.reflection.adapter.in.web;

import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.common.security.JwtAuthenticationFilter;
import com.healthupgrades.common.security.JwtTokenProvider;
import com.healthupgrades.common.security.SecurityConfig;
import com.healthupgrades.common.security.UserDetailsServiceImpl;
import com.healthupgrades.reflection.application.ReflectionService;
import com.healthupgrades.reflection.domain.model.Reflection;
import com.healthupgrades.support.WebSliceSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract for reflections: FR-23 (write one) and FR-24 (read an upgrade's, newest first).
 *
 * <p>The ratings are bounded one to five, so an out-of-range value must be a 400 with the field named
 * rather than a row nobody can render — a five-star control has no way to display a 9.
 *
 * <p>BR-13 shows up here as an absence: the resource exposes POST and GET and nothing else, so a PUT or
 * DELETE is refused by the framework as an unsupported method (405) rather than by a handler.
 */
@WebMvcTest(ReflectionController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class,
        ReflectionWebMapper.class, WebSliceSupport.class})
class ReflectionControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean ReflectionService service;
    @MockBean JwtTokenProvider tokenProvider;
    @MockBean UserDetailsServiceImpl userDetailsService;

    private UUID userId;
    private final UUID upgradeId = UUID.randomUUID();
    private String base;

    @BeforeEach
    void authenticate() {
        userId = UUID.randomUUID();
        base = "/api/upgrades/" + upgradeId + "/reflections";
        WebSliceSupport.authenticateAs(tokenProvider, userDetailsService, userId);
    }

    @Test
    void GivenAValidBody_WhenAReflectionIsWritten_ThenItAnswers201WithWhatWasStored() throws Exception {
        when(service.create(eq(userId), eq(upgradeId), any())).thenReturn(aReflection());

        mockMvc.perform(bearer(post(base)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"difficultyRating\":4,\"benefitRating\":5,\"whatWorked\":\"Phone out of the bedroom\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.difficultyRating").value(4))
                .andExpect(jsonPath("$.benefitRating").value(5))
                .andExpect(jsonPath("$.whatWorked").value("Phone out of the bedroom"));
    }

    @Test
    void GivenARatingOutsideOneToFive_WhenAReflectionIsWritten_ThenItAnswers400NamingTheField() throws Exception {
        mockMvc.perform(bearer(post(base)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"difficultyRating\":9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.difficultyRating").exists());

        verify(service, never()).create(any(), any(), any());
    }

    @Test
    void GivenAnUpgradeOwnedBySomebodyElse_WhenAReflectionIsWritten_ThenItAnswers404AndNot403() throws Exception {
        when(service.create(eq(userId), eq(upgradeId), any()))
                .thenThrow(new ResourceNotFoundException("Upgrade not found: " + upgradeId));

        mockMvc.perform(bearer(post(base)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void GivenAnOwnedUpgrade_WhenItsReflectionsAreRead_ThenTheyAnswer200InTheOrderTheServiceReturns()
            throws Exception {
        when(service.getForUpgrade(userId, upgradeId)).thenReturn(List.of(aReflection()));

        mockMvc.perform(bearer(get(base)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].upgradeId").value(upgradeId.toString()));

        verify(service).getForUpgrade(userId, upgradeId);
    }

    @Test
    void GivenTheReflectionResource_WhenADeleteIsAttempted_ThenTheMethodIsRefused() throws Exception {
        // BR-13 at the wire: reflections are append-only, so no delete route exists to reach a handler.
        mockMvc.perform(bearer(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(base)))
                .andExpect(status().isMethodNotAllowed());
    }

    private Reflection aReflection() {
        return Reflection.builder().id(UUID.randomUUID()).upgradeId(upgradeId).userId(userId)
                .date(LocalDate.of(2026, 3, 15)).difficultyRating(4).benefitRating(5)
                .whatWorked("Phone out of the bedroom").build();
    }
}
