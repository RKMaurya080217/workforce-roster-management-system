package com.weeklyroster.dto.response;

import java.time.LocalDateTime;

public record RosterVersionResponse(
        Long id,
        Long cycleId,
        int versionNumber,
        String action,
        String actionReason,
        LocalDateTime createdTimestamp,
        String createdBy,
        String generationMode,
        String status,
        Integer affectedAssignmentsCount,
        Integer healthScore,
        String impactSummary,
        String snapshotData
) {}