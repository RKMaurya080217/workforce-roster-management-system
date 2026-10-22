package com.weeklyroster.dto.request;

import com.weeklyroster.entity.ShiftType;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record ManualAssignmentRequest(
        Long cycleId,
        LocalDate startDate,
        @NotNull Long employeeId,
        @NotNull LocalDate date,
        @NotNull ShiftType shiftType,
        Boolean weeklyOff,
        String reason
) {}
