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
        ProfileChangeStatus status,
        LocalDateTime requestedAt,
        LocalDateTime reviewedAt,
        String adminRemarks
) {
}
