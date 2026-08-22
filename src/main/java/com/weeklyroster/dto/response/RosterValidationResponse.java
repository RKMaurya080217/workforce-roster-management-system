package com.weeklyroster.dto.response;

import com.weeklyroster.entity.ValidationSeverity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record RosterValidationResponse(
        Long cycleId,
        LocalDate startDate,
        LocalDate endDate,
        ValidationSeverity overallStatus,
        int totalChecks,
        int passCount,
        int warningCount,
        int errorCount,
        List<RosterValidationFinding> findings,
        LocalDateTime validatedAt
) {}
