package com.weeklyroster.dto.request;

import jakarta.validation.constraints.Size;

public record ProfileChangeDecisionRequest(
        @Size(max = 500, message = "Admin remarks must not exceed 500 characters")
        String adminRemarks
) {
}
