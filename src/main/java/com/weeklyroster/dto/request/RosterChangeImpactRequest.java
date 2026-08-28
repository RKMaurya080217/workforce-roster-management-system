package com.weeklyroster.dto.request;

import com.weeklyroster.entity.ShiftType;
import jakarta.validation.constraints.NotNull;

public record RosterChangeImpactRequest(
        @NotNull(message = "New shift type is required")
        ShiftType newShiftType,
        boolean weeklyOff,
        String reason
) {}
