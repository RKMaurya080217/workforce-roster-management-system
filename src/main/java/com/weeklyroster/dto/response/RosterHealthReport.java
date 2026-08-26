package com.weeklyroster.dto.response;

import com.weeklyroster.entity.RosterStatus;
import java.time.LocalDate;
import java.util.List;

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
        String overallValidationStatus
) {
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
                coverageCheck, restRulesCheck, nightLimitCheck, genderRulesCheck,
                leaveRulesCheck, overridesCheck, duplicatesCheck, weeklyOffCheck,
                shiftContinuityCheck, criticalConflictsCount, highConflictsCount,
                mediumConflictsCount, lowConflictsCount, infoCount, conflicts,
                readyToPublish ? 100.0 : 50.0, 100.0, "N/A", readyToPublish ? "VALID" : "INVALID");
    }
}
