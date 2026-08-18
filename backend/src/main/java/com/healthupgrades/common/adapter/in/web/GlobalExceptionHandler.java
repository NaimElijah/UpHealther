package com.healthupgrades.common.adapter.in.web;
import com.healthupgrades.common.domain.exception.*;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Single place where an exception becomes an HTTP status and an error body.
 *
 * <p>This is the whole exception → status contract, so controllers and services never build a status
 * by hand; they throw the domain exception that says what went wrong and this advice decides how it
 * surfaces:
 *
 * <table>
 *   <caption>Exception to status mapping</caption>
 *   <tr><th>Exception</th><th>Status</th></tr>
 *   <tr><td>{@link ResourceNotFoundException}</td><td>404 Not Found</td></tr>
 *   <tr><td>{@link BusinessRuleException}</td><td>422 Unprocessable Entity</td></tr>
 *   <tr><td>{@link DuplicateProgressException}</td><td>409 Conflict</td></tr>
 *   <tr><td>{@link OptimisticLockException} and the JPA one</td><td>409 Conflict</td></tr>
 *   <tr><td>{@link MethodArgumentNotValidException}</td><td>400 Bad Request, with field errors</td></tr>
 *   <tr><td>{@link AccessDeniedException}</td><td>403 Forbidden</td></tr>
 *   <tr><td>anything else</td><td>500 Internal Server Error, message withheld</td></tr>
 * </table>
 *
 * <p>The mapping is pinned by {@code GlobalExceptionHandlerTest}; changing a status here is a wire
 * contract change and breaks that test on purpose.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Maps a missing (or foreign-owned) resource to 404. */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(HttpStatus.NOT_FOUND.value(), ex.getMessage(), req.getRequestURI()));
    }

    /**
     * Maps a violated domain rule to 422 — the request was understood and well-formed, but the domain
     * refuses it (illegal transition, HARD-slot limit).
     */
    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ErrorResponse> handleBusinessRule(BusinessRuleException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse(HttpStatus.UNPROCESSABLE_ENTITY.value(), ex.getMessage(), req.getRequestURI()));
    }

    /** Maps a second progress entry for the same upgrade and day to 409. */
    @ExceptionHandler(DuplicateProgressException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateProgress(DuplicateProgressException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(HttpStatus.CONFLICT.value(), ex.getMessage(), req.getRequestURI()));
    }

    /**
     * Maps a concurrent-edit conflict to 409, from either the domain or the JPA exception.
     *
     * <p>The original message is replaced: a version clash is meaningless to a client, whereas "retry"
     * is the action it can actually take.
     */
    @ExceptionHandler({OptimisticLockException.class, jakarta.persistence.OptimisticLockException.class})
    public ResponseEntity<ErrorResponse> handleOptimisticLock(Exception ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(HttpStatus.CONFLICT.value(), "Resource was modified concurrently. Please retry.", req.getRequestURI()));
    }

    /**
     * Maps bean-validation failures on a request body to 400, with a field → message map so the client
     * can attach each message to the input that produced it.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        Map<String, String> errors = new HashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.put(fe.getField(), fe.getDefaultMessage());
        }
        ErrorResponse response = new ErrorResponse(HttpStatus.BAD_REQUEST.value(), "Validation failed", req.getRequestURI());
        response.setFieldErrors(errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /** Maps a Spring Security authorization failure to 403. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse(HttpStatus.FORBIDDEN.value(), "Access denied", req.getRequestURI()));
    }

    /**
     * Catch-all for anything unmapped: 500 with a fixed message.
     *
     * <p>The exception's own message is deliberately withheld — an unanticipated failure can carry a
     * stack detail, a SQL fragment or a value that must not reach a client.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal server error", req.getRequestURI()));
    }

    /**
     * The error body every failed request returns.
     *
     * <p>{@code fieldErrors} is populated only for validation failures and is omitted from the JSON
     * otherwise (it is null, and null fields are not serialized).
     */
    public static class ErrorResponse {
        private int status;
        private String message;
        private String path;
        private LocalDateTime timestamp;
        private Map<String, String> fieldErrors;

        /**
         * @param status  HTTP status code, repeated in the body for clients that only read the payload
         * @param message user-facing description of the failure
         * @param path    request URI that failed, for correlating a report with a log line
         */
        public ErrorResponse(int status, String message, String path) {
            this.status = status;
            this.message = message;
            this.path = path;
            this.timestamp = LocalDateTime.now();
        }

        public int getStatus() { return status; }
        public String getMessage() { return message; }
        public String getPath() { return path; }
        public LocalDateTime getTimestamp() { return timestamp; }
        public Map<String, String> getFieldErrors() { return fieldErrors; }
        public void setFieldErrors(Map<String, String> fieldErrors) { this.fieldErrors = fieldErrors; }
    }
}
