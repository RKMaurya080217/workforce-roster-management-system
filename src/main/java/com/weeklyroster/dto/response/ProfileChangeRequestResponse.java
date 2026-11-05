package com.weeklyroster.dto.response;

import com.weeklyroster.entity.ProfileChangeStatus;
import java.time.LocalDateTime;

public record ProfileChangeRequestResponse(
        Long id,
        Long employeeId,
        String employeeCode,
        String employeeName,
        String fieldName,
        String currentValue,
        String requestedValue,
        String reason,
        ProfileChangeStatus status,
        LocalDateTime requestedAt,
        LocalDateTime reviewedAt,
        String adminRemarks,
        String reviewedBy
) {
    public ProfileChangeRequestResponse(
            Long id,
            Long employeeId,
            String employeeCode,
            String employeeName,
            String fieldName,
            String currentValue,
            String requestedValue,
            ProfileChangeStatus status,
            LocalDateTime requestedAt,
            LocalDateTime reviewedAt,
            String adminRemarks
    ) {
        this(id, employeeId, employeeCode, employeeName, fieldName, currentValue, requestedValue, null, status, requestedAt, reviewedAt, adminRemarks, null);
    }
}
