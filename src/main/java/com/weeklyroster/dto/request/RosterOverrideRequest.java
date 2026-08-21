package com.weeklyroster.dto.request;

import com.weeklyroster.entity.ShiftType;
import jakarta.validation.constraints.NotNull;

public record RosterOverrideRequest(
        @NotNull Long assignmentId,
        @NotNull ShiftType shiftType,
        Boolean weeklyOff,
        String reason
) {
}
