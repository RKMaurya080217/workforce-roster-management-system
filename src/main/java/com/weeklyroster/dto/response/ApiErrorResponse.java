package com.weeklyroster.dto.response;

import java.time.LocalDateTime;
import java.util.Map;

public record ApiErrorResponse(
        String timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> validationErrors
) {
    public ApiErrorResponse(LocalDateTime timestamp, int status, String error, String message, String path, Map<String, String> validationErrors) {
        this(timestamp != null ? timestamp.toString() : LocalDateTime.now().toString(), status, error, message, path, validationErrors);
    }
}
