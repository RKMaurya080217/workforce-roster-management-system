package com.weeklyroster.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CancelLeaveRequest(
        @NotBlank(message = "Reason for cancellation is required")
        String reason
) {
}
