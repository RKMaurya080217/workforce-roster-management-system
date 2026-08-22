package com.weeklyroster.dto.request;

import com.weeklyroster.entity.HandoverPriority;
import com.weeklyroster.entity.HandoverStatus;

public record UpdateHandoverRequest(
        Long toEmployeeId,
        String summary,
        String pendingTasks,
        String completedTasks,
        String importantNotes,
        HandoverPriority priority,
        HandoverStatus status
) {}
