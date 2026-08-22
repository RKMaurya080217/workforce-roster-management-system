package com.weeklyroster.dto.response;

import com.weeklyroster.entity.HandoverPriority;
import com.weeklyroster.entity.HandoverStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record HandoverResponse(
        Long id,
        LocalDate handoverDate,
        Long shiftId,
        String shiftName,
        Long fromEmployeeId,
        String fromEmployeeCode,
        String fromEmployeeName,
        Long toEmployeeId,
        String toEmployeeCode,
        String toEmployeeName,
        String summary,
        String pendingTasks,
        String completedTasks,
        String importantNotes,
        HandoverPriority priority,
        HandoverStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
