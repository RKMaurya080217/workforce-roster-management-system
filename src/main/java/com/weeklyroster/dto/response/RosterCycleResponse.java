package com.weeklyroster.dto.response;

import com.weeklyroster.entity.GenerationMode;
import com.weeklyroster.entity.RosterStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record RosterCycleResponse(
        Long id,
        LocalDate startDate,
        LocalDate endDate,
        LocalDateTime generatedAt,
        GenerationMode generationMode,
        RosterStatus status,
        LocalDateTime publishedAt,
        String publishedBy,
        LocalDateTime lockedAt,
        String lockedBy,
        LocalDateTime unlockedAt,
        String unlockedBy,
        String unlockReason,
        String emailStatus,
        List<RosterAssignmentResponse> assignments,
        CoverageReportResponse coverageReport
) {
    public RosterCycleResponse(
            Long id,
            LocalDate startDate,
            LocalDate endDate,
            LocalDateTime generatedAt,
            List<RosterAssignmentResponse> assignments
    ) {
        this(id, startDate, endDate, generatedAt, GenerationMode.MANUAL, RosterStatus.GENERATED,
                null, null, null, null, null, null, null, "SENT", assignments, null);
    }

    public RosterCycleResponse(
            Long id,
            LocalDate startDate,
            LocalDate endDate,
            LocalDateTime generatedAt,
            List<RosterAssignmentResponse> assignments,
            CoverageReportResponse coverageReport
    ) {
        this(id, startDate, endDate, generatedAt, GenerationMode.MANUAL, RosterStatus.GENERATED,
                null, null, null, null, null, null, null, "SENT", assignments, coverageReport);
    }

    public RosterCycleResponse(
            Long id,
            LocalDate startDate,
            LocalDate endDate,
            LocalDateTime generatedAt,
            GenerationMode generationMode,
            String emailStatus,
            List<RosterAssignmentResponse> assignments
    ) {
        this(id, startDate, endDate, generatedAt, generationMode, RosterStatus.GENERATED,
                null, null, null, null, null, null, null, emailStatus, assignments, null);
    }

    public RosterCycleResponse(
            Long id,
            LocalDate startDate,
            LocalDate endDate,
            LocalDateTime generatedAt,
            GenerationMode generationMode,
            String emailStatus,
            List<RosterAssignmentResponse> assignments,
            CoverageReportResponse coverageReport
    ) {
        this(id, startDate, endDate, generatedAt, generationMode, RosterStatus.GENERATED,
                null, null, null, null, null, null, null, emailStatus, assignments, coverageReport);
    }
}
