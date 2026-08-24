package com.healthupgrades.healtharea.adapter.in.web;

import com.healthupgrades.common.adapter.in.web.GlobalExceptionHandler;
import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.common.security.JwtAuthenticationFilter;
import com.healthupgrades.common.security.JwtTokenProvider;
import com.healthupgrades.common.security.SecurityConfig;
import com.healthupgrades.common.security.UserDetailsServiceImpl;
import com.healthupgrades.healtharea.application.HealthAreaService;
import com.healthupgrades.healtharea.domain.model.HealthArea;
import com.healthupgrades.support.WebSliceSupport;
import org.junit.jupiter.api.BeforeEach;
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
 * The HTTP contract for health areas: FR-6 (create, read, update, delete), FR-7 (the attributes an area
 * carries over the wire) and BR-15 (another user's area is 404, never 403).
 *
 * <p>The icon is asserted as an emoji on purpose. It is a user-supplied string the frontend renders
 * directly, and {@code V5__seed_health_area_icons_as_emoji.sql} exists because it once was not — a
 * response that mangles it is a broken glyph in the interface.
 */
@WebMvcTest(HealthAreaController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class,
        HealthAreaWebMapper.class, WebSliceSupport.class})
class HealthAreaControllerTest {

    private static final String BASE = "/api/health-areas";
    private static final String BODY = "{\"name\":\"Sleep\",\"description\":\"Bed on time\",\"priority\":1,"
            + "\"icon\":\"🌙\",\"color\":\"#4B6BFB\"}";

    @Autowired MockMvc mockMvc;

    @MockBean HealthAreaService service;
    @MockBean JwtTokenProvider tokenProvider;
    @MockBean UserDetailsServiceImpl userDetailsService;

    private UUID userId;
    private final UUID areaId = UUID.randomUUID();

    @BeforeEach
    void authenticate() {
        userId = UUID.randomUUID();
        WebSliceSupport.authenticateAs(tokenProvider, userDetailsService, userId);
    }

    @Test
    void GivenAValidBody_WhenAnAreaIsCreated_ThenItAnswers201CarryingEveryAttribute() throws Exception {
        when(service.create(eq(userId), any())).thenReturn(anArea());

        mockMvc.perform(bearer(post(BASE)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Sleep"))
                .andExpect(jsonPath("$.priority").value(1))
                .andExpect(jsonPath("$.icon").value("🌙"))
                .andExpect(jsonPath("$.color").value("#4B6BFB"));
    }

    @Test
    void GivenABodyWithNoName_WhenAnAreaIsCreated_ThenItAnswers400AndNothingIsCreated() throws Exception {
        mockMvc.perform(bearer(post(BASE)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").exists());

        verify(service, never()).create(any(), any());
    }

    @Test
    void GivenTheCallersAreas_WhenTheyAreListed_ThenTheyAnswer200ScopedToThePrincipal() throws Exception {
        when(service.listByUser(userId)).thenReturn(List.of(anArea()));

        mockMvc.perform(bearer(get(BASE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Sleep"));

        verify(service).listByUser(userId);
    }

    @Test
    void GivenAnAreaOwnedBySomebodyElse_WhenItIsRead_ThenItAnswers404AndNot403() throws Exception {
        when(service.findById(userId, areaId))
                .thenThrow(new ResourceNotFoundException("HealthArea not found: " + areaId));

        mockMvc.perform(bearer(get(BASE + "/" + areaId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void GivenAValidBody_WhenAnAreaIsUpdated_ThenItAnswers200WithTheSavedArea() throws Exception {
        when(service.update(eq(userId), eq(areaId), any())).thenReturn(anArea());

        mockMvc.perform(bearer(put(BASE + "/" + areaId)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(areaId.toString()));
    }

    @Test
    void GivenAnOwnedArea_WhenItIsDeleted_ThenItAnswers204WithNoBody() throws Exception {
        mockMvc.perform(bearer(delete(BASE + "/" + areaId)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(service).delete(userId, areaId);
    }

    @Test
    void GivenAnAreaOwnedBySomebodyElse_WhenItIsDeleted_ThenItAnswers404AndNothingIsDeleted() throws Exception {
        doThrow(new ResourceNotFoundException("HealthArea not found: " + areaId))
                .when(service).delete(userId, areaId);

        mockMvc.perform(bearer(delete(BASE + "/" + areaId)))
                .andExpect(status().isNotFound());
    }

    private HealthArea anArea() {
        return HealthArea.builder().id(areaId).userId(userId).name("Sleep").description("Bed on time")
                .priority(1).icon("🌙").color("#4B6BFB").build();
    }
}
