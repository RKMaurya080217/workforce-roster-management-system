package com.weeklyroster.dto.external.v1;

import java.time.LocalDateTime;

public record ExternalApiResponse<T>(
        boolean success,
        T data,
        ExternalApiError error,
        ExternalApiMeta meta
) {
    public static <T> ExternalApiResponse<T> success(T data, String requestId) {
        return new ExternalApiResponse<>(
                true,
                data,
                null,
                new ExternalApiMeta(LocalDateTime.now(), requestId, "v1")
        );
    }

    public static <T> ExternalApiResponse<T> error(String code, String message, String details, String requestId) {
        return new ExternalApiResponse<>(
                false,
                null,
                new ExternalApiError(code, message, details),
                new ExternalApiMeta(LocalDateTime.now(), requestId, "v1")
        );
    }
}
