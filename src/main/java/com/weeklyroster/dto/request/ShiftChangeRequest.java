package com.weeklyroster.dto.request;

import com.weeklyroster.entity.ShiftType;
import jakarta.validation.constraints.NotNull;

public record ShiftChangeRequest(
        @NotNull ShiftType shiftType,
        String reason
) {
}
