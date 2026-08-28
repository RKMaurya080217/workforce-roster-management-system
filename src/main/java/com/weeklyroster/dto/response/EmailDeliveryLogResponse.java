package com.weeklyroster.dto.response;

import com.weeklyroster.entity.EmailDeliveryStatus;
import com.weeklyroster.entity.GenerationMode;
import java.time.LocalDateTime;

public record EmailDeliveryLogResponse(
        Long id,
        Long cycleId,
        Long employeeId,
        String employeeCode,
        String employeeName,
        String recipientEmail,
        String sentAt,
        EmailDeliveryStatus status,
        String errorMessage,
        GenerationMode mode,
        com.weeklyroster.entity.EmailType emailType
) {
    public EmailDeliveryLogResponse(
            Long id, Long cycleId, Long employeeId, String employeeCode,
            String employeeName, String recipientEmail, String sentAt,
            EmailDeliveryStatus status, String errorMessage, GenerationMode mode
    ) {
        this(id, cycleId, employeeId, employeeCode, employeeName, recipientEmail, sentAt, status, errorMessage, mode, com.weeklyroster.entity.EmailType.WEEKLY_ROSTER_DISTRIBUTION);
    }
}
