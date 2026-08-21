package com.weeklyroster.dto.request;

import jakarta.validation.constraints.NotBlank;

public record UnlockRosterRequest(
        @NotBlank(message = "Unlock reason is mandatory")
        String reason
) {}
