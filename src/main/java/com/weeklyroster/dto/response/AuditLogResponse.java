package com.weeklyroster.dto.response;

import com.weeklyroster.entity.AuditAction;
import java.time.LocalDateTime;

public record AuditLogResponse(
        Long id,
        LocalDateTime timestamp,
        String actor,
        AuditAction action,
        String entityType,
        Long entityId,
        Long cycleId,
        Long employeeId,
        String employeeName,
        String oldValue,
        String newValue,
        String reason,
        String source
) {}
