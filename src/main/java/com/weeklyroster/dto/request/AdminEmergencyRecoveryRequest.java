package com.weeklyroster.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminEmergencyRecoveryRequest(
        @NotBlank(message = "Emergency recovery key is required")
        String recoveryKey,

        String targetUsername,

        @NotBlank(message = "New password is required")
        @Size(min = 6, message = "New password must be at least 6 characters")
        String newPassword,

        @NotBlank(message = "Password confirmation is required")
        String confirmPassword
) {}
