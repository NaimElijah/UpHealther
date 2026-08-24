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
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.test.simple.SimpleTraceContext;
import io.micrometer.tracing.test.simple.SimpleTracer;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the exception-to-status contract the module guide documents.
 *
 * <p>That mapping is the thing every client depends on to tell "you asked for something that is not
 * there" from "you asked for something the rules forbid", and it was asserted nowhere: throwing the
 * wrong domain exception, or adding a handler with the wrong status, changed the API silently.
 *
 * <p>A domain exception is raised by a controller, so calling the advice directly tests it honestly.
 * A framework exception is not: it is raised before any handler method runs, and which handler claims
 * it is decided by Spring's resolver rather than by the call site — those go through {@link MockMvc}
 * in {@link ThroughTheDispatcher}.
 */
class GlobalExceptionHandlerTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private GlobalExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler(Tracer.NOOP);
        MockHttpServletRequest mockRequest = new MockHttpServletRequest();
        mockRequest.setRequestURI("/api/upgrades/42");
        request = mockRequest;
    }

    @Test
    void GivenAResourceNotFoundException_WhenItIsHandled_ThenTheStatusIsNotFound() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleNotFound(new ResourceNotFoundException("Upgrade not found: 42"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(404);
        assertThat(response.getBody().getMessage()).isEqualTo("Upgrade not found: 42");
        assertThat(response.getBody().getPath()).isEqualTo("/api/upgrades/42");
    }

    @Test
    void GivenABusinessRuleViolation_WhenItIsHandled_ThenTheStatusIsUnprocessableEntity() {
        // 422 rather than 400: the request was well formed, the domain refused it.
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleBusinessRule(new BusinessRuleException("Only ACTIVE upgrades can be paused"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Only ACTIVE upgrades can be paused");
    }

    @Test
    void GivenADuplicateProgressEntry_WhenItIsHandled_ThenTheStatusIsConflict() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleDuplicateProgress(new DuplicateProgressException("Already recorded"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void GivenAnOptimisticLockFailure_WhenItIsHandled_ThenTheStatusIsConflict() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleOptimisticLock(new OptimisticLockException("stale version"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void GivenAnOptimisticLockFailure_WhenItIsHandled_ThenTheUnderlyingMessageIsNotLeaked() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleOptimisticLock(new OptimisticLockException("Row was updated by transaction 8123"), request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).doesNotContain("8123");
    }

    @Test
    void GivenAFailedAuthentication_WhenItIsHandled_ThenTheStatusIsUnauthorized() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleAuthentication(new BadCredentialsException("Bad credentials"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(401);
    }

    @Test
    void GivenAFailedAuthentication_WhenItIsHandled_ThenTheBodyDoesNotSayWhichHalfWasWrong() {
        // A caller learning that the email exists but the password did not match is free information
        // for anyone enumerating accounts, so the original message is dropped rather than passed on.
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleAuthentication(
                        new BadCredentialsException("No user found for someone@example.com"), request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Invalid credentials");
    }

    @Test
    void GivenAccessIsDenied_WhenItIsHandled_ThenTheStatusIsForbidden() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleAccessDenied(new AccessDeniedException("nope"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void GivenAnUnexpectedFailure_WhenItIsHandled_ThenTheStatusIsInternalServerErrorAndTheBodySaysNothingElse() {
        // The catch-all must never surface an internal message to a caller.
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleGeneral(new IllegalStateException("jdbc://user:hunter2@db/prod"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Internal server error");
        assertThat(response.getBody().getMessage()).doesNotContain("hunter2");
    }

    @Test
    void GivenAnUnexpectedFailure_WhenTheCatchAllHandlesIt_ThenItIsLoggedWithItsStackTrace() throws Exception {
        // Withholding the cause from the client is only safe if the server keeps it: otherwise a 500
        // leaves no trace beyond the access log.
        List<ILoggingEvent> logged = logsFromHandler(() ->
                handler.handleGeneral(new IllegalStateException("jdbc://user:hunter2@db/prod"), request));

        assertThat(logged).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy()).isNotNull();
        });
    }

    @Test
    void GivenTheRequestIsBeingTraced_WhenItFails_ThenTheErrorBodyCarriesTheTraceId() {
        // The whole point of the id: a user reporting this failure can quote something that identifies
        // one log line rather than a path that matches thousands.
        SimpleTracer tracer = new SimpleTracer();
        Span span = tracer.nextSpan().start();
        // SimpleTracer does not generate a trace id, so an assertion against whatever it produced would
        // compare "" with "" - and CorrelationId rejects a blank id, so it would compare "" with null.
        ((SimpleTraceContext) span.context()).setTraceId(TRACE_ID);

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response;
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            response = new GlobalExceptionHandler(tracer)
                    .handleNotFound(new ResourceNotFoundException("Upgrade not found: 42"), request);
        }

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTraceId()).isEqualTo(TRACE_ID);
    }

    @Test
    void GivenNothingIsBeingTraced_WhenARequestFails_ThenTheErrorBodyOmitsTheTraceId() {
        // Absent, not null: ErrorResponse is @JsonInclude(NON_NULL), so no "traceId": null reaches a
        // client that would then look for a log line that does not exist.
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleNotFound(new ResourceNotFoundException("Upgrade not found: 42"), request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTraceId()).isNull();
    }

    @Test
    void GivenTheAdviceAsSpringSeesIt_WhenItsHandlerMappingsAreBuilt_ThenNoTypeIsMappedTwice() {
        // What Spring does at startup. Adding an @ExceptionHandler for a type ResponseEntityExceptionHandler
        // already maps is an ambiguous mapping: it fails the boot, and the only other test that would
        // notice needs a database.
        assertThatCode(() -> new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class))
                .doesNotThrowAnyException();
    }

    /**
     * The advice in front of a real controller, so a framework exception is raised and resolved the way
     * it is in production.
     *
     * <p>Standalone MockMvc builds its own {@code ObjectMapper} rather than the application's, so this
     * rig pins statuses and the error body's own fields — not how a domain type is serialized, which
     * {@code UpgradeDtoSerializationTest} covers.
     */
    @Nested
    class ThroughTheDispatcher {

        // The service and mapper are never reached: every request below fails before the handler method.
        private final MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new UpgradeController(mock(UpgradeService.class), mock(UpgradeWebMapper.class)))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(Tracer.NOOP))
                .build();

        @Test
        void GivenAnUnknownEnumConstant_WhenTheBodyIsBound_ThenTheRequestIsRejectedWith400() throws Exception {
            // A syntactically valid body the API cannot bind is a bad request, not a server failure.
            mockMvc.perform(post("/api/upgrades")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Walk daily\",\"type\":\"NOT_A_TYPE\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Malformed request body"))
                    .andExpect(jsonPath("$.path").value("/api/upgrades"));
        }

        @Test
        void GivenMalformedJson_WhenTheBodyIsBound_ThenTheRequestIsRejectedWith400() throws Exception {
            mockMvc.perform(post("/api/upgrades")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\": "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Malformed request body"));
        }

        @Test
        void GivenAnUnbindableBody_WhenItIsRejected_ThenTheResponseRepeatsNoParserDetail() throws Exception {
            // Jackson names the target type, the offending value and the byte offset; none of that is a
            // client's business.
            String body = mockMvc.perform(post("/api/upgrades")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Walk daily\",\"type\":\"NOT_A_TYPE\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors").doesNotExist())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body)
                    .doesNotContain("NOT_A_TYPE")
                    .doesNotContain("com.fasterxml")
                    .doesNotContain("UpgradeType");
        }

        @Test
        void GivenARequiredFieldIsMissing_WhenTheBodyIsValidated_ThenTheFieldErrorIsNamedWith400() throws Exception {
            mockMvc.perform(post("/api/upgrades")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"HABIT\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.fieldErrors.title").exists());
        }

        @Test
        void GivenAPathVariableOfTheWrongType_WhenItIsBound_ThenTheRequestIsRejectedWith400() throws Exception {
            mockMvc.perform(get("/api/upgrades/not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Malformed request parameter"));
        }

        @Test
        void GivenAQueryParameterOutsideItsEnum_WhenItIsBound_ThenTheRequestIsRejectedWith400() throws Exception {
            mockMvc.perform(get("/api/upgrades").param("status", "NOT_A_STATUS"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void GivenAMethodTheRouteDoesNotOffer_WhenItIsCalled_ThenTheResponseIs405() throws Exception {
            mockMvc.perform(patch("/api/upgrades/" + UUID.randomUUID()))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.message").value("Method Not Allowed"));
        }

        @Test
        void GivenABodyInAnUnsupportedMediaType_WhenItIsRead_ThenTheResponseIs415() throws Exception {
            mockMvc.perform(post("/api/upgrades").contentType(MediaType.TEXT_PLAIN).content("not json"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.message").value("Unsupported Media Type"));
        }

        @Test
        void GivenAClientMistake_WhenItIsRejected_ThenNothingIsLoggedAtError() throws Exception {
            // ERROR means "actionable now". A caller sending a bad id is not an incident, and burying
            // real 500s under that noise is the reason this handler was changed in the first place.
            List<ILoggingEvent> logged = logsFromHandler(() ->
                    mockMvc.perform(get("/api/upgrades/not-a-uuid")).andExpect(status().isBadRequest()));

            assertThat(logged).noneMatch(event -> event.getLevel() == Level.ERROR);
        }
    }

    /** Collects what {@link GlobalExceptionHandler} logs while {@code action} runs. */
    private static List<ILoggingEvent> logsFromHandler(Action action) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list;
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }
}
