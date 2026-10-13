package com.weeklyroster.dto.response;

import com.weeklyroster.entity.ShiftType;
import java.time.LocalDate;

public record DatePreferencePreviewResponse(
        Long employeeId,
        String employeeCode,
        String employeeName,
        LocalDate preferenceDate,
        ShiftType requestedShift,
        String currentPlannedShift,
        String expectedFinalShift,
        String status,
        String reason,
        boolean canApply
) {}
