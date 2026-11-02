package com.ivorovi.platform.web;

import com.ivorovi.platform.audit.AuditLog;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Maps every failure to application/problem+json with a stable {@code code}. Never leaks internals. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final AuditLog audit;

    GlobalExceptionHandler(AuditLog audit) {
        this.audit = audit;
    }

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApi(ApiException ex) {
        return ProblemResponses.problem(ex.status(), ex.code(), ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleDenied(AccessDeniedException ex, WebRequest request) {
        String path = request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : "";
        String method = request instanceof ServletWebRequest swr ? swr.getRequest().getMethod() : "";
        audit.recordDenied("security.access_denied", Map.of("method", method, "path", path));
        return ProblemResponses.problem(HttpStatus.FORBIDDEN, "forbidden", "You do not have permission for this action.");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleConcurrent(OptimisticLockingFailureException ex) {
        return ProblemResponses.problem(HttpStatus.CONFLICT, "concurrent_modification",
                "The resource was modified by someone else. Reload and try again.");
    }

    @ExceptionHandler({ConstraintViolationException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail handleBadParam(Exception ex) {
        return ProblemResponses.problem(HttpStatus.BAD_REQUEST, "invalid_parameter", "One or more request parameters are invalid.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ProblemResponses.problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "An unexpected error occurred. Quote the traceId when contacting support.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        ProblemDetail pd = ProblemResponses.problem(HttpStatus.BAD_REQUEST, "validation_failed", "The request contains invalid fields.");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(), "message", String.valueOf(fe.getDefaultMessage())))
                .toList();
        pd.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(pd);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(org.springframework.http.converter.HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Throwable cause = ex.getCause();
        while (cause != null && !(cause instanceof tools.jackson.databind.exc.UnrecognizedPropertyException)) cause = cause.getCause();
        if (cause instanceof tools.jackson.databind.exc.UnrecognizedPropertyException u) {
            return ResponseEntity.badRequest().body(ProblemResponses.problem(HttpStatus.BAD_REQUEST, "unknown_field",
                    "Unknown field '" + u.getPropertyName() + "' in the request body."));
        }
        return handleExceptionInternal(ex, null, headers, status, request);
    }

    /** Framework-detected errors (malformed JSON, 404 routes, 405...): keep status, add our code/traceId. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        String code = switch (status) {
            case NOT_FOUND -> "not_found";
            case METHOD_NOT_ALLOWED -> "method_not_allowed";
            case UNSUPPORTED_MEDIA_TYPE -> "unsupported_media_type";
            case NOT_ACCEPTABLE -> "not_acceptable";
            case BAD_REQUEST -> "malformed_request";
            default -> "request_error";
        };
        String detail = status == HttpStatus.BAD_REQUEST ? "The request could not be read." : status.getReasonPhrase();
        return ResponseEntity.status(status).headers(headers).body(ProblemResponses.problem(status, code, detail));
    }
}
