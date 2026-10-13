package com.weeklyroster.dto.request;

import com.weeklyroster.entity.ShiftType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public record BulkDateShiftPreferenceRequest(
        @NotEmpty(message = "At least one employee must be selected")
        List<Long> employeeIds,

        @NotNull(message = "Preference date is required")
        LocalDate preferenceDate,

        @NotNull(message = "Shift type is required")
        ShiftType shiftType,

        String reason
) {}
