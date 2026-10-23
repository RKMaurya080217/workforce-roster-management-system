package com.weeklyroster.dto.response;

import java.time.LocalDate;
import java.util.List;

public record CopyWeekPreviewResponse(
        LocalDate sourceStartDate,
        LocalDate sourceEndDate,
        LocalDate targetStartDate,
        LocalDate targetEndDate,
        int totalAssignmentsToCopy,
        int affectedEmployeesCount,
        int affectedDatesCount,
        int protectedManualCount,
        int conflictCount,
        List<String> conflicts,
        boolean canApply
) {}
