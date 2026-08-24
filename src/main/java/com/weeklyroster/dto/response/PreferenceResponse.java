package com.weeklyroster.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
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
) {
    @JsonProperty("preferredShifts")
    public String preferredShifts() {
        return preferredShiftTypes;
    }

    @JsonProperty("avoidShifts")
    public String avoidShifts() {
        return avoidShiftTypes;
    }

    @JsonProperty("temporaryConstraints")
    public String temporaryConstraints() {
        return temporaryRestrictions;
    }
}

