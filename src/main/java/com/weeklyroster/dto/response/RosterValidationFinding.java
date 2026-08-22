package com.weeklyroster.dto.response;

import com.weeklyroster.entity.ValidationSeverity;
import java.time.LocalDate;

public record RosterValidationFinding(
        String ruleCode,
        String ruleName,
        ValidationSeverity severity,
        String employeeCode,
        String employeeName,
        LocalDate date,
        String message,
        String details
) {}
