package com.weeklyroster.dto.response;

import com.weeklyroster.entity.RosterStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record SmartCommandCenterResponse(
        Long cycleId,
        LocalDate startDate,
        LocalDate endDate,
        String formattedDateRange,
        RosterStatus status,
        String lifecycleStage,
        String reviewDeadline,
        String smartSummary,
        Double healthScore,
        String healthStatus,
        Double coveragePercentage,
        Double restCompliancePercentage,
        Double preferenceCompliancePercentage,
        Double shiftContinuityPercentage,
        Double workloadBalancePercentage,
        Double nightDistributionPercentage,
        int pendingRequestsCount,
        int criticalConflictsCount,
        int warningConflictsCount,
        int infoConflictsCount,
        String finalizationReadiness,
        String finalizationStatusMessage,
        List<String> finalizationBlockers,
        Map<String, String> hardConstraints,
        List<CommandCenterExceptionItem> exceptions,
        List<PendingChangeSummaryItem> pendingChanges,
        NightAllocationSummaryDto nightAllocationSummary,
        ContinuitySummaryDto continuitySummary,
        WorkloadSummaryDto workloadSummary,
        AdminOverridesSummaryDto adminOverridesSummary,
        OptimizationSummaryDto optimizationSummary,
        List<CommandCenterActivityItem> recentActivities,
        NotificationsSummaryDto notificationsSummary
) {
    public record CommandCenterExceptionItem(
            String id,
            String severity, // "CRITICAL", "WARNING", "INFO"
            String title,
            String description,
            String affectedEmployee,
            LocalDate date,
            String actionLabel,
            String actionTarget
    ) {}

    public record PendingChangeSummaryItem(
            Long id,
            String type, // "SHIFT_CHANGE", "LEAVE", "PREFERENCE"
            String employeeName,
            String employeeCode,
            String description,
            String requestedDate,
            String potentialImpact
    ) {}

    public record NightAllocationSummaryDto(
            int totalNights,
            int eligibleMaleCount,
            int compliantMaleCount,
            int femaleNightCount,
            boolean compliant,
            String statusText
    ) {}

    public record ContinuitySummaryDto(
            Double score,
            String status,
            int continuousBlocksCount,
            int switchingIssuesCount,
            String summaryText
    ) {}

    public record WorkloadSummaryDto(
            String status, // "BALANCED", "ATTENTION_NEEDED"
            int standardDutyDays,
            int maxDutyDays,
            int minDutyDays,
            String highestDutyEmployee,
            int highestDutyHours,
            String summaryText
    ) {}

    public record AdminOverridesSummaryDto(
            int activeOverridesCount,
            List<AdminOverrideItemDto> items
    ) {}

    public record AdminOverrideItemDto(
            Long assignmentId,
            String employeeName,
            String employeeCode,
            LocalDate date,
            String shiftType,
            String reason
    ) {}

    public record OptimizationSummaryDto(
            Double currentScore,
            Double potentialScore,
            String status, // "AVAILABLE", "OPTIMIZED", "NO_IMPROVEMENT", "LOCKED"
            String message,
            boolean optimizationAvailable
    ) {}

    public record CommandCenterActivityItem(
            String timeFormatted,
            String actor,
            String action,
            String details
    ) {}

    public record NotificationsSummaryDto(
            int unreadCount,
            int totalCount,
            String summaryBreakdown
    ) {}
}
