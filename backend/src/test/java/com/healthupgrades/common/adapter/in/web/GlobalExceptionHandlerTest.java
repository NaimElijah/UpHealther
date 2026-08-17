package com.healthupgrades.common.adapter.in.web;

import com.healthupgrades.common.domain.exception.BusinessRuleException;
import com.healthupgrades.common.domain.exception.DuplicateProgressException;
import com.healthupgrades.common.domain.exception.OptimisticLockException;
import com.healthupgrades.common.domain.exception.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the exception-to-status contract the module guide documents.
 *
 * <p>That mapping is the thing every client depends on to tell "you asked for something that is not
 * there" from "you asked for something the rules forbid", and it was asserted nowhere: throwing the
 * wrong domain exception, or adding a handler with the wrong status, changed the API silently.
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        MockHttpServletRequest mockRequest = new MockHttpServletRequest();
        mockRequest.setRequestURI("/api/upgrades/42");
        request = mockRequest;
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
    void unexpectedFailure_isInternalServerErrorAndSaysNothingElse() {
        // The catch-all must never surface an internal message to a caller.
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleGeneral(new IllegalStateException("jdbc://user:hunter2@db/prod"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Internal server error");
        assertThat(response.getBody().getMessage()).doesNotContain("hunter2");
    }
}
