package com.weeklyroster.dto.response;

public record ComponentHealth(
        String component,
        String status,
        String message,
        String details
) {}