package com.weeklyroster.dto.response;

import com.weeklyroster.entity.ShiftType;
import java.time.LocalDate;

public record ConflictItem(
        LocalDate date,
        Long employeeId,
        String employeeName,
        ShiftType shiftType,
        String ruleName,
        String currentValue,
        String expectedValue,
        String reason,
        String severity, // CRITICAL, HIGH, MEDIUM, LOW, INFO
        String recommendedAction,
        boolean resolved
) {}
