package com.healthupgrades.auth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.healthupgrades.support.PostgresIT;
import com.healthupgrades.user.application.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

    private Logger rootLogger;
    private ListAppender<ILoggingEvent> appender;

    // The root logger rather than a named one: the leak this guards against came from a framework logger
    // nobody had configured, and the next one would too.
    @BeforeEach
    void captureEveryLogLine() {
        rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);
    }

    @AfterEach
    void releaseTheLog() {
        rootLogger.detachAppender(appender);
    }

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

    @Test
    void GivenTheDatabaseRefusesADuplicateEmail_WhenTheRegistrationIsAnswered_ThenNoLogLineCarriesTheAddress()
            throws Exception {
        // NFR-6. The refusal is handled and answered 422, so no line has a fault to excuse it - yet
        // Hibernate's SqlExceptionHelper wrote the driver's "Key (email)=(...) already exists" at ERROR (#142).
        String email = "racer-" + UUID.randomUUID() + "@example.com";
        register(email).andExpect(status().isCreated());
        doReturn(false).when(userService).existsByEmail(anyString());

        register(email.toUpperCase(Locale.ROOT)).andExpect(status().isUnprocessableEntity());

        // The existence check refuses with the same 422, so the answer alone cannot show that the database
        // refused: the second registration has to have reached the insert.
        verify(userService, times(2)).register(any());
        List<String> written = appender.list.stream().flatMap(RegistrationRaceIT::everythingWritten).toList();
        // An empty capture would pass the check below as well; the refusal's own audit line proves it saw.
        assertThat(written).anyMatch(text -> text.contains("action=auth.register outcome=REFUSED"));
        assertThat(written).noneMatch(text -> text.toLowerCase(Locale.ROOT).contains(email));
    }

    private org.springframework.test.web.servlet.ResultActions register(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY.formatted(email)));
    }

    /** The event's message and every message Logback prints with it. */
    private static Stream<String> everythingWritten(ILoggingEvent event) {
        return Stream.concat(Stream.of(event.getFormattedMessage()), messagesOf(event.getThrowableProxy()));
    }

    /** A throwable's message, then those of its cause and of every suppressed throwable, recursively. */
    private static Stream<String> messagesOf(IThrowableProxy proxy) {
        if (proxy == null) {
            return Stream.empty();
        }
        Stream<String> nested = Stream.concat(Stream.of(proxy.getCause()), Arrays.stream(proxy.getSuppressed()))
                .flatMap(RegistrationRaceIT::messagesOf);
        return Stream.concat(Stream.ofNullable(proxy.getMessage()), nested);
    }
}
