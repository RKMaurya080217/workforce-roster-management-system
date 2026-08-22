package com.weeklyroster.dto.response;

import java.time.LocalDateTime;
import java.util.List;

public record VersionComparisonResponse(
        Long cycleId,
        int version1Number,
        int version2Number,
        LocalDateTime v1Timestamp,
        LocalDateTime v2Timestamp,
        String v1Action,
        String v2Action,
        int totalChanges,
        List<VersionAssignmentDiff> diffs
) {}
