package com.weeklyroster.dto.request;

import com.weeklyroster.entity.ShiftType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SubmitRosterChangeRequest(
        @NotNull(message = "Assignment ID is required")
        Long assignmentId,

        ShiftType requestedShiftType,

        boolean requestedWeeklyOff,

        @NotBlank(message = "Reason is mandatory for shift change requests")
        String reason
) {}
