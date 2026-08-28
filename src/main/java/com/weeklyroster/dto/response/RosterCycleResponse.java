package com.weeklyroster.dto.response;

import com.weeklyroster.entity.GenerationMode;
import com.weeklyroster.entity.RosterStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
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
        CoverageReportResponse coverageReport,
        String classification,
        String source,
        boolean deletable,
        String generationStatus,
        Double preferenceComplianceScore,
        String maleNightCoverage,
        Integer criticalConflicts,
        Integer warnings,
        Double healthScore,
        List<ConflictItem> conflicts,
        List<EmployeeWorkloadMetric> workloadMetrics,
        Double shiftContinuityScore,
        Double workloadBalanceScore
) {
    public RosterCycleResponse(
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
            CoverageReportResponse coverageReport,
            String classification,
            String source,
            boolean deletable,
            String generationStatus,
            Double preferenceComplianceScore,
            String maleNightCoverage,
            Integer criticalConflicts,
            Integer warnings,
            Double healthScore,
            List<ConflictItem> conflicts,
            List<EmployeeWorkloadMetric> workloadMetrics
    ) {
        this(id, startDate, endDate, generatedAt, generationMode, status,
                publishedAt, publishedBy, lockedAt, lockedBy, unlockedAt, unlockedBy, unlockReason,
                emailStatus, assignments, coverageReport,
                classification, source, deletable,
                generationStatus, preferenceComplianceScore, maleNightCoverage,
                criticalConflicts, warnings, healthScore, conflicts, workloadMetrics,
                100.0, 100.0);
    }

    public RosterCycleResponse(
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
            CoverageReportResponse coverageReport,
            String classification,
            String source,
            boolean deletable
    ) {
        this(id, startDate, endDate, generatedAt, generationMode, status,
                publishedAt, publishedBy, lockedAt, lockedBy, unlockedAt, unlockedBy, unlockReason,
                emailStatus, assignments, coverageReport,
                classification, source, deletable,
                "VALID", 100.0, "N/A", 0, 0, 100.0, Collections.emptyList(), Collections.emptyList(),
                100.0, 100.0);
    }

    public RosterCycleResponse(
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
        this(id, startDate, endDate, generatedAt, generationMode, status,
                publishedAt, publishedBy, lockedAt, lockedBy, unlockedAt, unlockedBy, unlockReason,
                emailStatus, assignments, coverageReport,
                com.weeklyroster.util.RosterLifecycleUtil.classifyCycle(startDate, endDate),
                com.weeklyroster.util.RosterLifecycleUtil.resolveSource(generationMode),
                status == RosterStatus.DRAFT || status == RosterStatus.GENERATED,
                "VALID", 100.0, "N/A", 0, 0, 100.0, Collections.emptyList(), Collections.emptyList(),
                100.0, 100.0);
    }

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
