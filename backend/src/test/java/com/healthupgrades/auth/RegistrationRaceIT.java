package com.healthupgrades.auth;

import com.healthupgrades.support.PostgresIT;
import com.healthupgrades.user.application.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Locale;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FR-4 when two registrations for one address race: both can pass the existence check, and only the
 * unique constraint decides.
 *
 * <p>Racing two threads would make this test flaky by construction. Instead the existence check is
 * forced to miss, which is exactly the state the losing request is in, and the real database is left to
 * refuse the insert. Before the save flushed, that refusal happened at commit, outside anything that
 * could translate it, and the caller was answered 500.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RegistrationRaceIT extends PostgresIT {

    private static final String BODY =
            "{\"name\":\"Racer\",\"email\":\"%s\",\"password\":\"long-enough-password\"}";

    @Autowired MockMvc mockMvc;
    @SpyBean UserService userService;

    @Test
    void GivenTheExistenceCheckMissedAConcurrentRegistration_WhenTheSecondIsSaved_ThenItIsRefusedWith422NotA500()
            throws Exception {
        // Unique per run: the container is shared by every IT in the build.
        String email = "racer-" + UUID.randomUUID() + "@example.com";
        register(email).andExpect(status().isCreated());
        doReturn(false).when(userService).existsByEmail(anyString());

        register(email.toUpperCase(Locale.ROOT))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("That email is already registered"));
    }

    private org.springframework.test.web.servlet.ResultActions register(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY.formatted(email)));
    }
}
