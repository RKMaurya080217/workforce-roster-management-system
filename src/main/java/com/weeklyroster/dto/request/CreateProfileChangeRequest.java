package com.weeklyroster.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProfileChangeRequest(
        @NotBlank(message = "Field name is required")
        @Size(max = 60, message = "Field name must not exceed 60 characters")
        String fieldName,

        @NotBlank(message = "Requested value is required")
        @Size(max = 255, message = "Requested value must not exceed 255 characters")
        String requestedValue
) {
}
