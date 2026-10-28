package com.weeklyroster.dto.request;

import com.weeklyroster.entity.RecurringPreferenceType;
import com.weeklyroster.entity.ShiftType;
import jakarta.validation.constraints.NotNull;

import java.time.DayOfWeek;
import java.time.LocalDate;

public record RecurringShiftPreferenceRequest(
        @NotNull(message = "Employee ID is required")
        Long employeeId,

        @NotNull(message = "Day of week is required")
        DayOfWeek dayOfWeek,

        @NotNull(message = "Shift type is required")
        ShiftType shiftType,

        RecurringPreferenceType preferenceType,

        LocalDate startDate,

        LocalDate endDate,

        Boolean active,

        String reason
) {
    public RecurringShiftPreferenceRequest(Long employeeId, DayOfWeek dayOfWeek, ShiftType shiftType,
                                          LocalDate startDate, LocalDate endDate, Boolean active, String reason) {
        this(employeeId, dayOfWeek, shiftType, RecurringPreferenceType.PREFERRED, startDate, endDate, active, reason);
    }
}
