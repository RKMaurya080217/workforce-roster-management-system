package com.weeklyroster.dto.response;

import com.weeklyroster.entity.NotificationType;
import java.time.LocalDateTime;

public record NotificationResponse(
        Long id,
        String recipientUsername,
        Long recipientEmployeeId,
        String title,
        String message,
        NotificationType type,
        String linkPage,
        Long linkId,
        LocalDateTime createdAt,
        boolean readStatus
) {}
