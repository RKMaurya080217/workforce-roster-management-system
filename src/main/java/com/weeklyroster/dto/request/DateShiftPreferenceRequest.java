package com.weeklyroster.dto.request;

import com.weeklyroster.entity.ShiftType;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record DateShiftPreferenceRequest(
        @NotNull(message = "Employee ID is required")
        Long employeeId,

        @NotNull(message = "Preference date is required")
        LocalDate preferenceDate,

        @NotNull(message = "Shift type is required")
        ShiftType shiftType,

        String reason
) {}
