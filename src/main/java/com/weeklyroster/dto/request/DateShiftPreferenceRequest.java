package com.weeklyroster.dto.request;

import com.weeklyroster.entity.RecurringPreferenceType;
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

        RecurringPreferenceType preferenceType,

        String reason
) {
    public DateShiftPreferenceRequest(Long employeeId, LocalDate preferenceDate, ShiftType shiftType, String reason) {
        this(employeeId, preferenceDate, shiftType, RecurringPreferenceType.PREFERRED, reason);
    }
}
