package com.weeklyroster.dto.request;

import jakarta.validation.constraints.Size;

public record LeaveDecisionRequest(
        @Size(max = 500) String remarks
) {
}
