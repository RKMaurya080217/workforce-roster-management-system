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
        String snapshotData
) {}
