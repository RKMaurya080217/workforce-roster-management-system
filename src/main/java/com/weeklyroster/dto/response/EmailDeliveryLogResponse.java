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
        GenerationMode mode
) {
}
