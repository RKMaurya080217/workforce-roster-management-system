package com.weeklyroster.dto.external.v1;

public record ExternalApiError(
        String code,
        String message,
        String details
) {
    public ExternalApiError(String code, String message) {
        this(code, message, null);
    }
}
