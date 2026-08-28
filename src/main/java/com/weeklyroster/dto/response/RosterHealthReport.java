package com.weeklyroster.dto.response;

import com.weeklyroster.entity.RosterStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record RosterHealthReport(
        Long cycleId,
        LocalDate startDate,
        LocalDate endDate,
        RosterStatus status,
        boolean readyToPublish,
        String summaryStatus,
        String coverageCheck,
        String restRulesCheck,
        String nightLimitCheck,
        String genderRulesCheck,
        String leaveRulesCheck,
        String overridesCheck,
        String duplicatesCheck,
        String weeklyOffCheck,
        String shiftContinuityCheck,
        int criticalConflictsCount,
        int highConflictsCount,
        int mediumConflictsCount,
        int lowConflictsCount,
        int infoCount,
        List<ConflictItem> conflicts,
        Double healthScore,
        Double preferenceComplianceScore,
        String maleNightCoverage,
        String overallValidationStatus,
        Double shiftContinuityScore,
        Double workloadBalanceScore,
        Double coveragePercentage,
        Double restCompliancePercentage,
        Double nightDistributionPercentage,
        Double offDistributionPercentage,
        String healthScoreStatus,
        Map<String, String> hardConstraints,
        PreferenceHealthDetails preferenceDetails,
        ContinuityHealthDetails continuityDetails,
        WorkloadHealthDetails workloadDetails,
        NightHealthDetails nightDetails,
        OffHealthDetails offDetails
) {
    public record PreferenceHealthDetails(
            int totalAssignments,
            int preferenceCompatibleCount,
            int preferenceConflictsCount,
            List<PreferenceDetailItem> items
    ) {}

    public record PreferenceDetailItem(
            String employeeName,
            String employeeCode,
            LocalDate date,
            String shiftType,
            String status,
            String note
    ) {}

    public record ContinuityHealthDetails(
            String status,
            String description,
            int totalTransitions,
            int continuousBlocksCount,
            List<ContinuityIssueItem> issues
    ) {}

    public record ContinuityIssueItem(
            String employeeName,
            String pattern,
            String reason
    ) {}

    public record WorkloadHealthDetails(
            List<WorkloadEmployeeItem> employees
    ) {}

    public record WorkloadEmployeeItem(
            String employeeName,
            String employeeCode,
            int dutyDays,
            int dutyHours,
            int nightCount,
            int eveningCount,
            int weekendCount,
            String workloadStatus
    ) {}

    public record NightHealthDetails(
            int totalNightDuties,
            List<NightEmployeeItem> maleDistribution,
            int femaleNightCount,
            boolean compliant,
            String message
    ) {}

    public record NightEmployeeItem(
            String employeeName,
            String employeeCode,
            int nightCount,
            boolean compliant
    ) {}

    public record OffHealthDetails(
            int totalEmployees,
            int offCompliantCount,
            List<OffEmployeeItem> employees
    ) {}

    public record OffEmployeeItem(
            String employeeName,
            String employeeCode,
            int offCount,
            String offDates,
            boolean preferredDayMatched,
            boolean compliant
    ) {}

        public RosterHealthReport(
            Long cycleId,
            LocalDate startDate,
            LocalDate endDate,
            RosterStatus status,
            boolean readyToPublish,
            String summaryStatus,
            String coverageCheck,
            String restRulesCheck,
            String nightLimitCheck,
            String genderRulesCheck,
            String leaveRulesCheck,
            String overridesCheck,
            String duplicatesCheck,
            String weeklyOffCheck,
            String shiftContinuityCheck,
            int criticalConflictsCount,
            int highConflictsCount,
            int mediumConflictsCount,
            int lowConflictsCount,
            int infoCount,
            List<ConflictItem> conflicts
    ) {
        this(cycleId, startDate, endDate, status, readyToPublish, summaryStatus,
                coverageCheck, restRulesCheck, nightLimitCheck, genderRulesCheck, leaveRulesCheck,
                overridesCheck, duplicatesCheck, weeklyOffCheck, shiftContinuityCheck,
                criticalConflictsCount, highConflictsCount, mediumConflictsCount, lowConflictsCount, infoCount,
                conflicts, 100.0, 100.0, "All satisfied", "VALID", 100.0, 100.0);
    }

    public RosterHealthReport(
            Long cycleId,
            LocalDate startDate,
            LocalDate endDate,
            RosterStatus status,
            boolean readyToPublish,
            String summaryStatus,
            String coverageCheck,
            String restRulesCheck,
            String nightLimitCheck,
            String genderRulesCheck,
            String leaveRulesCheck,
            String overridesCheck,
            String duplicatesCheck,
            String weeklyOffCheck,
            String shiftContinuityCheck,
            int criticalConflictsCount,
            int highConflictsCount,
            int mediumConflictsCount,
            int lowConflictsCount,
            int infoCount,
            List<ConflictItem> conflicts,
            Double healthScore,
            Double preferenceComplianceScore,
            String maleNightCoverage,
            String overallValidationStatus
    ) {
        this(cycleId, startDate, endDate, status, readyToPublish, summaryStatus,
                coverageCheck, restRulesCheck, nightLimitCheck, genderRulesCheck, leaveRulesCheck,
                overridesCheck, duplicatesCheck, weeklyOffCheck, shiftContinuityCheck,
                criticalConflictsCount, highConflictsCount, mediumConflictsCount, lowConflictsCount, infoCount,
                conflicts, healthScore, preferenceComplianceScore, maleNightCoverage,
                overallValidationStatus, 100.0, 100.0);
    }

    // Backward-compatible constructor
    public RosterHealthReport(
            Long cycleId,
            LocalDate startDate,
            LocalDate endDate,
            RosterStatus status,
            boolean readyToPublish,
            String summaryStatus,
            String coverageCheck,
            String restRulesCheck,
            String nightLimitCheck,
            String genderRulesCheck,
            String leaveRulesCheck,
            String overridesCheck,
            String duplicatesCheck,
            String weeklyOffCheck,
            String shiftContinuityCheck,
            int criticalConflictsCount,
            int highConflictsCount,
            int mediumConflictsCount,
            int lowConflictsCount,
            int infoCount,
            List<ConflictItem> conflicts,
            Double healthScore,
            Double preferenceComplianceScore,
            String maleNightCoverage,
            String overallValidationStatus,
            Double shiftContinuityScore,
            Double workloadBalanceScore
    ) {
        this(
                cycleId, startDate, endDate, status, readyToPublish, summaryStatus,
                coverageCheck, restRulesCheck, nightLimitCheck, genderRulesCheck, leaveRulesCheck,
                overridesCheck, duplicatesCheck, weeklyOffCheck, shiftContinuityCheck,
                criticalConflictsCount, highConflictsCount, mediumConflictsCount, lowConflictsCount, infoCount,
                conflicts, healthScore, preferenceComplianceScore, maleNightCoverage,
                overallValidationStatus, shiftContinuityScore, workloadBalanceScore,
                "PASSED".equals(coverageCheck) ? 100.0 : 0.0,
                "PASSED".equals(restRulesCheck) ? 100.0 : 0.0,
                "PASSED".equals(nightLimitCheck) ? 100.0 : 50.0,
                "PASSED".equals(weeklyOffCheck) ? 100.0 : 80.0,
                healthScore != null && healthScore >= 90.0 ? "Excellent" : (healthScore != null && healthScore >= 80.0 ? "Good" : "Needs Improvement"),
                Map.of("Coverage", coverageCheck, "12-hour Rest", restRulesCheck, "Night Rule", nightLimitCheck, "Female Shift Restrictions", genderRulesCheck, "Approved Leave", leaveRulesCheck),
                null, null, null, null, null
        );
    }
}
