package com.weeklyroster.dto.response;

import com.weeklyroster.entity.ShiftType;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record DateShiftPreferenceResponse(
        Long id,
        Long employeeId,
        String employeeCode,
        String employeeName,
        LocalDate preferenceDate,
        ShiftType shiftType,
        String reason,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
