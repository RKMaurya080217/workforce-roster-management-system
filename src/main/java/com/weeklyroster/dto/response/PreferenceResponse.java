package com.weeklyroster.dto.response;

import com.weeklyroster.entity.PreferenceStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record PreferenceResponse(
        Long id,
        Long employeeId,
        String employeeCode,
        String employeeName,
        String preferredShiftTypes,
        String preferredOffDays,
        String preferredWorkingDays,
        String avoidShiftTypes,
        String temporaryRestrictions,
        String remarks,
        PreferenceStatus status,
        String adminRemarks,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        LocalDateTime createdAt,
        LocalDateTime reviewedAt,
        String reviewedBy
) {}
