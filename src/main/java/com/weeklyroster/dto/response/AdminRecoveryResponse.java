package com.weeklyroster.dto.response;

public record AdminRecoveryResponse(
        boolean success,
        String message,
        String maskedEmail
) {
    public AdminRecoveryResponse(boolean success, String message) {
        this(success, message, null);
    }
}
