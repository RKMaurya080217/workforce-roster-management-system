package com.weeklyroster.dto.request;

import jakarta.validation.constraints.NotBlank;

public record AdminRecoveryRequest(
        @NotBlank(message = "Username or registered email is required")
        String loginOrEmail
) {}
