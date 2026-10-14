package com.weeklyroster.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminRecoveryVerifyRequest(
        @NotBlank(message = "Username or registered email is required")
        String loginOrEmail,

        @NotBlank(message = "Recovery code / OTP is required")
        @Size(min = 6, max = 8, message = "Recovery code must be between 6 and 8 characters")
        String otpCode,

        @NotBlank(message = "New password is required")
        @Size(min = 6, message = "New password must be at least 6 characters")
        String newPassword,

        @NotBlank(message = "Password confirmation is required")
        String confirmPassword
) {}
