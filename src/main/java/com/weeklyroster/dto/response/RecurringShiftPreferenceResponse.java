package com.weeklyroster.dto.response;

import com.weeklyroster.entity.ShiftType;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record RecurringShiftPreferenceResponse(
        Long id,
        Long employeeId,
        String employeeCode,
        String employeeName,
        DayOfWeek dayOfWeek,
        ShiftType shiftType,
        LocalDate startDate,
        LocalDate endDate,
        boolean active,
        String reason,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
