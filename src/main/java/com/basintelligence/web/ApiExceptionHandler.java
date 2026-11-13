package com.basintelligence.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.basintelligence.common.InvalidStateException;
import com.basintelligence.common.NotFoundException;

/** RFC 9457 problem responses for REST controllers. Internal details are never exposed. */
@RestControllerAdvice(annotations = RestController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

    private final io.micrometer.core.instrument.MeterRegistry meters;

    public ApiExceptionHandler(io.micrometer.core.instrument.MeterRegistry meters) {
        this.meters = meters;
    }

    /** Malformed edge JSON (unparseable body) counts as invalid telemetry; the response is Spring's usual 400. */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ProblemDetail unreadable(jakarta.servlet.http.HttpServletRequest request) {
        if (request.getRequestURI().startsWith("/api/edge/")) {
            meters.counter(com.basintelligence.common.BasMetrics.TELEMETRY_INVALID, "reason", "MALFORMED_BATCH").increment();
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request body is not valid JSON for this endpoint");
    }

    /** Retry hint for clients such as the BAS edge node, which keeps readings queued and retries. */
    static final String RETRY_AFTER_SECONDS = "30";

    /**
     * Database unreachable (for example Neon suspended, refusing connections or a network drop). Clients get a
     * retryable 503 instead of a 500 with internals; the edge node keeps its queue and resends.
     */
    @ExceptionHandler({org.springframework.dao.DataAccessResourceFailureException.class,
            org.springframework.dao.TransientDataAccessException.class,
            org.springframework.dao.RecoverableDataAccessException.class,
            org.springframework.transaction.CannotCreateTransactionException.class})
    org.springframework.http.ResponseEntity<ProblemDetail> databaseUnavailable(Exception e) {
        org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class).warn("Database unavailable: {}", e.getMessage());
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "The database is temporarily unavailable. Retry later.");
        return org.springframework.http.ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(org.springframework.http.HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS).body(p);
    }

    @ExceptionHandler(com.basintelligence.common.ServiceUnavailableException.class)
    org.springframework.http.ResponseEntity<ProblemDetail> unavailable(com.basintelligence.common.ServiceUnavailableException e) {
        return org.springframework.http.ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(org.springframework.http.HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ProblemDetail tooLarge() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, "The upload exceeds the 10 MB limit");
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(InvalidStateException.class)
    ProblemDetail conflict(InvalidStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    /** Method-level validation (@Validated parameters and collection elements). */
    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    ProblemDetail invalid(jakarta.validation.ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted()
                .collect(java.util.stream.Collectors.joining("; "));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** Bean Validation on a {@code @Valid @RequestBody}: every field error is listed, in a stable order. */
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    ProblemDetail invalidBody(org.springframework.web.bind.MethodArgumentNotValidException e,
            jakarta.servlet.http.HttpServletRequest request) {
        if (request.getRequestURI().startsWith("/api/edge/")) {
            meters.counter(com.basintelligence.common.BasMetrics.TELEMETRY_INVALID, "reason", "MALFORMED_BATCH").increment();
        }
        java.util.List<String> errors = new java.util.ArrayList<>();
        e.getBindingResult().getFieldErrors().forEach(f -> errors.add(f.getField() + ": " + f.getDefaultMessage()));
        e.getBindingResult().getGlobalErrors().forEach(g -> errors.add(g.getObjectName() + ": " + g.getDefaultMessage()));
        java.util.Collections.sort(errors);
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, String.join("; ", errors));
        p.setTitle("Invalid request");
        p.setProperty("errors", errors);
        return p;
    }

    /** Built-in method validation (constraints on parameters of controllers without {@code @Validated}). */
    @ExceptionHandler(org.springframework.web.method.annotation.HandlerMethodValidationException.class)
    ProblemDetail invalidParameters(org.springframework.web.method.annotation.HandlerMethodValidationException e) {
        java.util.List<String> errors = e.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(err -> r.getMethodParameter().getParameterName() + ": " + err.getDefaultMessage()))
                .sorted().toList();
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, String.join("; ", errors));
        p.setTitle("Invalid request");
        p.setProperty("errors", errors);
        return p;
    }

    @ExceptionHandler(com.basintelligence.common.RateLimitExceededException.class)
    org.springframework.http.ResponseEntity<ProblemDetail> tooManyRequests(com.basintelligence.common.RateLimitExceededException e) {
        return org.springframework.http.ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(org.springframework.http.HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage()));
    }
}
