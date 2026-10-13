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
        LocalDateTime updatedAt,
        String status,
        String conflictReason
) {
    public DateShiftPreferenceResponse(Long id, Long employeeId, String employeeCode, String employeeName,
                                       LocalDate preferenceDate, ShiftType shiftType, String reason,
                                       String createdBy, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, employeeId, employeeCode, employeeName, preferenceDate, shiftType, reason, createdBy, createdAt, updatedAt, "VALID", null);
    }
}
