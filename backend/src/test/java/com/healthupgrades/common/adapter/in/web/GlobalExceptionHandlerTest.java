package com.healthupgrades.common.adapter.in.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.healthupgrades.common.domain.exception.BusinessRuleException;
import com.healthupgrades.common.domain.exception.DuplicateProgressException;
import com.healthupgrades.common.domain.exception.OptimisticLockException;
import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import com.healthupgrades.upgrade.adapter.in.web.UpgradeController;
import com.healthupgrades.upgrade.adapter.in.web.UpgradeWebMapper;
import com.healthupgrades.upgrade.application.UpgradeService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the exception-to-status contract the module guide documents.
 *
 * <p>That mapping is the thing every client depends on to tell "you asked for something that is not
 * there" from "you asked for something the rules forbid", and it was asserted nowhere: throwing the
 * wrong domain exception, or adding a handler with the wrong status, changed the API silently.
 *
 * <p>Most cases call the advice directly, which is enough when the exception is one a controller
 * throws. An unbindable body is not: it is raised by the framework before any handler method runs, and
 * which of the advice's handlers claims it is decided by Spring's resolver rather than by the call
 * site. Those cases therefore go through {@link MockMvc} in front of a real controller.
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest request;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        MockHttpServletRequest mockRequest = new MockHttpServletRequest();
        mockRequest.setRequestURI("/api/upgrades/42");
        request = mockRequest;

        // The service and mapper are never reached: binding fails before the handler method is called.
        mockMvc = MockMvcBuilders
                .standaloneSetup(new UpgradeController(mock(UpgradeService.class), mock(UpgradeWebMapper.class)))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(handler)
                .build();
    }

    @Test
    void resourceNotFound_isNotFound() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleNotFound(new ResourceNotFoundException("Upgrade not found: 42"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(404);
        assertThat(response.getBody().getMessage()).isEqualTo("Upgrade not found: 42");
        assertThat(response.getBody().getPath()).isEqualTo("/api/upgrades/42");
    }

    @Test
    void businessRuleViolation_isUnprocessableEntity() {
        // 422 rather than 400: the request was well formed, the domain refused it.
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleBusinessRule(new BusinessRuleException("Only ACTIVE upgrades can be paused"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Only ACTIVE upgrades can be paused");
    }

    @Test
    void duplicateProgress_isConflict() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleDuplicateProgress(new DuplicateProgressException("Already recorded"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void optimisticLock_isConflict() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleOptimisticLock(new OptimisticLockException("stale version"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void optimisticLock_doesNotLeakTheUnderlyingMessage() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleOptimisticLock(new OptimisticLockException("Row was updated by transaction 8123"), request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).doesNotContain("8123");
    }

    @Test
    void accessDenied_isForbidden() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleAccessDenied(new AccessDeniedException("nope"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unknownEnumValueInBody_isBadRequest() throws Exception {
        // A syntactically valid body the API cannot bind is a bad request, not a server failure: the
        // catch-all must not claim it.
        mockMvc.perform(post("/api/upgrades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Walk daily\",\"type\":\"NOT_A_TYPE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void malformedJsonBody_isBadRequest() throws Exception {
        mockMvc.perform(post("/api/upgrades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unbindableBody_saysNothingAboutTheParser() throws Exception {
        // Jackson names the target type, the offending value and the byte offset; none of that is a
        // client's business.
        String body = mockMvc.perform(post("/api/upgrades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Walk daily\",\"type\":\"NOT_A_TYPE\"}"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("NOT_A_TYPE")
                .doesNotContain("com.fasterxml")
                .doesNotContain("UpgradeType");
    }

    @Test
    void unexpectedFailure_isInternalServerErrorAndSaysNothingElse() {
        // The catch-all must never surface an internal message to a caller.
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleGeneral(new IllegalStateException("jdbc://user:hunter2@db/prod"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Internal server error");
        assertThat(response.getBody().getMessage()).doesNotContain("hunter2");
    }

    @Test
    void unexpectedFailure_isLoggedWithItsStackTrace() {
        // Withholding the cause from the client is only safe if the server keeps it: otherwise a 500
        // leaves no trace beyond the access log.
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            handler.handleGeneral(new IllegalStateException("jdbc://user:hunter2@db/prod"), request);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy()).isNotNull();
        });
    }
}
