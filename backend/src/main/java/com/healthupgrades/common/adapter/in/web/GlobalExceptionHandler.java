package com.healthupgrades.common.adapter.in.web;
import com.healthupgrades.common.domain.exception.*;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.healthupgrades.common.observability.CorrelationId;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.LocalDateTime;
import java.util.Comparator;
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
 *   <tr><td>{@link HttpMessageNotReadableException}</td><td>400 Bad Request, parser detail withheld</td></tr>
 *   <tr><td>{@link TypeMismatchException}</td><td>400 Bad Request</td></tr>
 *   <tr><td>every other Spring MVC exception</td><td>the status Spring defines for it (405, 415, 406, 404, …)</td></tr>
 *   <tr><td>{@link BadCredentialsException} / {@link AccountStatusException}</td><td>401 Unauthorized, message withheld</td></tr>
 *   <tr><td>{@link AccessDeniedException}</td><td>403 Forbidden</td></tr>
 *   <tr><td>anything else</td><td>500 Internal Server Error, message withheld</td></tr>
 * </table>
 *
 * <p>The last two rows are why this class extends {@link ResponseEntityExceptionHandler}. An advice is
 * consulted before Spring's own {@code DefaultHandlerExceptionResolver}, so the catch-all below claims
 * every framework exception that has no more specific handler — which silently turned a bad path
 * variable, an unsupported method and an unsupported media type into 500s. Inheriting Spring's list of
 * handlers restores each one's proper status; {@link #handleExceptionInternal} then re-clothes it in
 * this API's {@link ErrorResponse} body, so the wire format stays the same whoever produced the status.
 * See {@code docs/ADRs/ADR-006-framework-exceptions-through-responseentityexceptionhandler.md}.
 *
 * <p>To change how a framework exception surfaces, override its {@code handleXxx} hook — do not add a
 * second {@code @ExceptionHandler} for a type the parent already maps, which is an ambiguous mapping
 * and fails at startup.
 *
 * <p>The mapping is pinned by {@code GlobalExceptionHandlerTest}; changing a status here is a wire
 * contract change and breaks that test on purpose.
 */
@Slf4j
@RequiredArgsConstructor
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /**
     * The only thing a 5xx ever says. An unanticipated failure can carry a stack detail, a SQL fragment
     * or a value that must not reach a client, so no 5xx body is ever built from the exception.
     */
    private static final String INTERNAL_ERROR_MESSAGE = "Internal server error";

    /** Supplies the trace id stamped on every error body; see {@link #body}. */
    private final Tracer tracer;

    /**
     * Builds an error body carrying the trace id of the request that failed.
     *
     * <p>Every response this advice returns goes through here, so the tracer is consulted in exactly
     * one place. The id is what turns "I got a 500 on /api/upgrades" into a single log line; when there
     * is no span it is simply absent from the JSON rather than null, because {@code ErrorResponse} is
     * {@code @JsonInclude(NON_NULL)}.
     */
    private ErrorResponse body(int status, String message, String path) {
        ErrorResponse body = new ErrorResponse(status, message, path);
        CorrelationId.of(tracer).ifPresent(body::setTraceId);
        return body;
    }

    /** Maps a missing (or foreign-owned) resource to 404. */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(body(HttpStatus.NOT_FOUND.value(), ex.getMessage(), req.getRequestURI()));
    }

    /**
     * Maps a violated domain rule to 422 — the request was understood and well-formed, but the domain
     * refuses it (illegal transition, HARD-slot limit).
     */
    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ErrorResponse> handleBusinessRule(BusinessRuleException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(body(HttpStatus.UNPROCESSABLE_ENTITY.value(), ex.getMessage(), req.getRequestURI()));
    }

    /** Maps a second progress entry for the same upgrade and day to 409. */
    @ExceptionHandler(DuplicateProgressException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateProgress(DuplicateProgressException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body(HttpStatus.CONFLICT.value(), ex.getMessage(), req.getRequestURI()));
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
                .body(body(HttpStatus.CONFLICT.value(), "Resource was modified concurrently. Please retry.", req.getRequestURI()));
    }

    /**
     * Maps a rejected credential to 401.
     *
     * <p>Reached only from the login endpoint, which authenticates inside a handler method rather than
     * in the security chain — so without this the most ordinary outcome there is, a wrong password,
     * fell through to {@link #handleGeneral} as a 500 and logged a stack trace for it.
     *
     * <p><strong>Deliberately not {@code AuthenticationException}.</strong> That supertype also covers
     * {@link org.springframework.security.authentication.AuthenticationServiceException} — which is what
     * Spring wraps a database outage in during a login. Catching the supertype would report an outage to
     * the user as "your password is wrong" and log nothing at all. The two subtypes named here are the
     * ones that genuinely mean "these credentials are not good"; everything else stays a 500 with a
     * stack trace, which is what NFR-7 asks for.
     *
     * <p>Nothing is logged here on purpose: a wrong password is an expected outcome on a public
     * endpoint, not a fault, and the submitted email is personal data (NFR-6).
     *
     * <p>The message is replaced with a single generic one: telling a caller that the email exists but
     * the password was wrong is free information for anyone enumerating accounts.
     */
    @ExceptionHandler({BadCredentialsException.class, AccountStatusException.class})
    public ResponseEntity<ErrorResponse> handleBadCredentials(AuthenticationException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(body(HttpStatus.UNAUTHORIZED.value(), "Invalid credentials", req.getRequestURI()));
    }

    /** Maps a Spring Security authorization failure to 403. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(body(HttpStatus.FORBIDDEN.value(), "Access denied", req.getRequestURI()));
    }

    /**
     * Maps bean-validation failures on a request body to 400, with a field → message map so the client
     * can attach each message to the input that produced it.
     *
     * <p>One field can violate several constraints at once — an empty password fails both
     * {@code @NotBlank} and {@code @Size(min = 8)} — and the map holds one message each. Hibernate
     * Validator does not specify the order of {@code getFieldErrors()}, so picking whichever arrives
     * last would let two identical requests answer differently. Sorting the messages and keeping the
     * first makes the choice arbitrary but stable, which is what a wire contract needs: the client
     * shows one message per input either way, and the same request always produces the same one.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField)
                        .thenComparing(fe -> String.valueOf(fe.getDefaultMessage())))
                .forEach(fe -> errors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        ErrorResponse body = body(HttpStatus.BAD_REQUEST.value(), "Validation failed", pathOf(request));
        body.setFieldErrors(errors);
        return respond(ex, HttpStatus.BAD_REQUEST, body, headers, request);
    }

    /**
     * Maps a request body that cannot be bound to 400 — malformed JSON, an unknown enum constant, a
     * string where a number is expected.
     *
     * <p>The client's input was wrong, not the server. The parser's own message is withheld: it names
     * the target type, the offending value and the byte offset, which describes the API's internals
     * rather than the caller's mistake.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        return respond(ex, HttpStatus.BAD_REQUEST, "Malformed request body", headers, request);
    }

    /**
     * Maps a path variable or query parameter that will not convert to 400 — an id that is not a UUID,
     * a filter that is not one of the enum's constants.
     */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        return respond(ex, HttpStatus.BAD_REQUEST, "Malformed request parameter", headers, request);
    }

    /**
     * The single exit for every exception Spring itself maps: keeps the status the parent chose and
     * replaces its {@code ProblemDetail} body with this API's {@link ErrorResponse}.
     *
     * <p>The message is the status' own reason phrase — accurate, and incapable of leaking the
     * exception's detail. A 4xx here is the caller's mistake and is logged at DEBUG; a 5xx is ours and
     * is logged at ERROR with the stack trace.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        String message = statusCode.is5xxServerError()
                ? INTERNAL_ERROR_MESSAGE
                : HttpStatus.valueOf(statusCode.value()).getReasonPhrase();
        return respond(ex, statusCode, message, headers, request);
    }

    /**
     * Catch-all for anything unmapped: 500 with a fixed message.
     *
     * <p>The exception is logged instead of returned, since withholding the cause from the client is
     * only safe if the server keeps it: a 500 nobody can diagnose is worse than the leak it avoids.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception for {} {}", req.getMethod(), req.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body(HttpStatus.INTERNAL_SERVER_ERROR.value(), INTERNAL_ERROR_MESSAGE, req.getRequestURI()));
    }

    private ResponseEntity<Object> respond(Exception ex, HttpStatusCode statusCode, String message,
                                           HttpHeaders headers, WebRequest request) {
        return respond(ex, statusCode, body(statusCode.value(), message, pathOf(request)), headers, request);
    }

    private ResponseEntity<Object> respond(Exception ex, HttpStatusCode statusCode, ErrorResponse body,
                                           HttpHeaders headers, WebRequest request) {
        // Kept from the overridden parent implementation: writing a body onto a committed response fails.
        HttpServletResponse response = ((ServletWebRequest) request).getResponse();
        if (response != null && response.isCommitted()) {
            log.warn("Response already committed for {}, dropping the error body", pathOf(request));
            return null;
        }
        if (statusCode.is5xxServerError()) {
            log.error("Unhandled exception for {}", pathOf(request), ex);
        } else {
            log.debug("Rejected {} with {}", pathOf(request), statusCode, ex);
        }
        return ResponseEntity.status(statusCode).headers(headers).body(body);
    }

    private static String pathOf(WebRequest request) {
        return ((ServletWebRequest) request).getRequest().getRequestURI();
    }

    /**
     * The error body every failed request returns.
     *
     * <p>{@code fieldErrors} is populated only for validation failures, and {@code traceId} only when
     * the request was traced; both are omitted from the JSON otherwise. The trace id is the key that
     * joins a failure a user reports to the log lines that produced it.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorResponse {
        private int status;
        private String message;
        private String path;
        private LocalDateTime timestamp;
        private Map<String, String> fieldErrors;
        private String traceId;

        /**
         * Private on purpose: {@link GlobalExceptionHandler#body} is the only way to make one, which is
         * what stops a future handler from returning an error body with no trace id on it.
         *
         * @param status  HTTP status code, repeated in the body for clients that only read the payload
         * @param message user-facing description of the failure
         * @param path    request URI that failed, for correlating a report with a log line
         */
        private ErrorResponse(int status, String message, String path) {
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
        public String getTraceId() { return traceId; }
        public void setTraceId(String traceId) { this.traceId = traceId; }
    }
}
