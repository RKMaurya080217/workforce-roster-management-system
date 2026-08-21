package com.weeklyroster.dto.response;

import com.weeklyroster.entity.LeaveStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record LeaveResponse(
        Long id,
        Long employeeId,
        String employeeCode,
        String employeeName,
        LocalDate startDate,
        LocalDate endDate,
        String reason,
        LeaveStatus status,
        String adminRemarks,
        LocalDateTime requestedAt,
        LocalDateTime reviewedAt,
        LocalDate originalStartDate,
        LocalDate originalEndDate,
        LocalDate pendingStartDate,
        LocalDate pendingEndDate,
        String modificationReason,
        String cancellationReason,
        LocalDateTime modifiedAt
) {
}
