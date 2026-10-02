package com.shipyard.tracker.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/**
 * One place that decides what an error looks like to the client.
 * Messages we wrote ourselves (ResponseStatusException) are passed through; everything else gets a generic
 * message and a reference number, and the details go to the server log only. The JSON keeps the shape the
 * frontend already reads: { timestamp, status, error, message, path }.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException ex, HttpServletRequest request) {
        String reason = ex.getReason() != null ? ex.getReason() : reasonPhrase(ex.getStatusCode());
        return body(ex.getStatusCode(), reason, request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(OptimisticLockingFailureException ex, HttpServletRequest request) {
        return body(HttpStatus.CONFLICT, "Someone else changed this item at the same time. Reload and try again.", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Data integrity violation on {} {}: {}", request.getMethod(), request.getRequestURI(),
                ex.getMostSpecificCause().getMessage());
        return body(HttpStatus.CONFLICT,
                "That change conflicts with existing data (for example, the name is already taken). Reload and try again.", request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> handleBadInput(Exception ex, HttpServletRequest request) {
        return body(HttpStatus.BAD_REQUEST, "The request is malformed or has a field of the wrong type.", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception ex, HttpServletRequest request) throws Exception {
        if (ex instanceof ErrorResponse standard) {
            // Spring MVC's own errors (404 for unknown paths, 405, 415 ...): keep the status, drop the details.
            return body(standard.getStatusCode(), reasonPhrase(standard.getStatusCode()), request);
        }
        if (ex instanceof org.springframework.security.access.AccessDeniedException
                || ex instanceof org.springframework.security.core.AuthenticationException) {
            throw ex; // let Spring Security answer 401/403 as usual
        }
        String reference = UUID.randomUUID().toString().substring(0, 8);
        log.error("Unhandled error {} on {} {}", reference, request.getMethod(), request.getRequestURI(), ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong on the server. Reference: " + reference, request);
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatusCode status, String message, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", reasonPhrase(status));
        body.put("message", message);
        body.put("path", request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }

    private static String reasonPhrase(HttpStatusCode status) {
        HttpStatus known = HttpStatus.resolve(status.value());
        return known != null ? known.getReasonPhrase() : "Error";
    }
}
