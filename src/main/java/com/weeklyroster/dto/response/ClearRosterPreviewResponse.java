package com.weeklyroster.dto.response;

import java.util.List;

public record ClearRosterPreviewResponse(
        String mode,
        int affectedAssignmentsCount,
        int affectedEmployeesCount,
        int affectedDatesCount,
        String summary,
        String warningMessage,
        List<String> affectedSample,
        boolean canApply
) {}
