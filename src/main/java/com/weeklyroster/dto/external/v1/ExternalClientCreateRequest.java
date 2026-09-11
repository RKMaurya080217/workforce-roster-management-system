package com.weeklyroster.dto.external.v1;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ExternalClientCreateRequest(
        @NotBlank(message = "Client name cannot be blank")
        @Size(max = 100, message = "Client name must be at most 100 characters")
        String clientName,

        String scopes, // e.g. "ROSTER_READ,EMPLOYEE_READ,SHIFT_READ,LEAVE_READ"

        Integer rateLimitPerMinute
) {}
