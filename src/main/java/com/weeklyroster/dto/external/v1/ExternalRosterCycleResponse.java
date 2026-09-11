package com.weeklyroster.dto.external.v1;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record ExternalRosterCycleResponse(
        Long id,
        LocalDate startDate,
        LocalDate endDate,
        LocalDateTime generatedAt,
        int assignmentCount,
        List<ExternalRosterAssignmentResponse> assignments
) {}
