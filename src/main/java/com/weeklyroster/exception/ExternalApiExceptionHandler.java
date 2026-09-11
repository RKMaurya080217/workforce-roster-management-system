package com.weeklyroster.exception;

import com.weeklyroster.dto.external.v1.ExternalApiResponse;
import com.weeklyroster.security.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.format.DateTimeParseException;
import java.util.stream.Collectors;

@RestControllerAdvice(basePackages = "com.weeklyroster.controller.external")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ExternalApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ExternalApiExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ExternalApiResponse<Void>> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        String reqId = resolveRequestId(request);
        log.warn("External API 404 on {} [reqId={}]: {}", request.getRequestURI(), reqId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ExternalApiResponse.error("RESOURCE_NOT_FOUND", ex.getMessage(), null, reqId));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ExternalApiResponse<Void>> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        String reqId = resolveRequestId(request);
        log.warn("External API 403 on {} [reqId={}]: {}", request.getRequestURI(), reqId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ExternalApiResponse.error(
                        "FORBIDDEN",
                        "Insufficient permissions. Client lacks the required scope for this endpoint.",
                        ex.getMessage(),
                        reqId
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ExternalApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String reqId = resolveRequestId(request);
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ExternalApiResponse.error("VALIDATION_FAILED", "Request validation failed", details, reqId));
    }

    @ExceptionHandler({IllegalArgumentException.class, DateTimeParseException.class, MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ExternalApiResponse<Void>> handleBadRequest(Exception ex, HttpServletRequest request) {
        String reqId = resolveRequestId(request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ExternalApiResponse.error("BAD_REQUEST", ex.getMessage(), null, reqId));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ExternalApiResponse<Void>> handleGeneric(Exception ex, HttpServletRequest request) {
        String reqId = resolveRequestId(request);
        log.error("Unhandled exception in External API on {} [reqId={}]", request.getRequestURI(), reqId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ExternalApiResponse.error(
                        "INTERNAL_SERVER_ERROR",
                        "An unexpected internal error occurred. Please contact support with the request ID.",
                        null,
                        reqId
                ));
    }

    private String resolveRequestId(HttpServletRequest request) {
        String reqId = (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE_REQUEST_ID);
        if (reqId == null) reqId = request.getHeader(CorrelationIdFilter.HEADER_REQUEST_ID);
        return reqId != null ? reqId : "n/a";
    }
}
