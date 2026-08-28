package com.weeklyroster.dto.response;

import java.util.List;

public record RollbackPreviewResponse(
        Long cycleId,
        int currentVersionNumber,
        int targetVersionNumber,
        int affectedAssignmentsCount,
        int affectedEmployeesCount,
        Integer currentHealthScore,
        Integer projectedHealthScore,
        Integer healthDelta,
        boolean canRollback,
        String verdict,
        String verdictBadgeLabel,
        List<String> blockers,
        List<String> warnings,
        List<VersionAssignmentDiff> diffs
) {}