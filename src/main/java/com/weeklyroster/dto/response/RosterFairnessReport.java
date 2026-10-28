package com.weeklyroster.dto.response;

import java.time.LocalDate;
import java.util.List;

public record RosterFairnessReport(
        LocalDate startDate,
        LocalDate endDate,
        int overallScore,
        FairnessScoreBreakdown scoreBreakdown,
        List<EmployeeFairnessDetail> employeeDetails,
        double overallPreferenceSatisfactionRate,
        int totalAvoidableTransitions,
        double nightVariance,
        double eveningVariance,
        double workloadVariance,
        boolean fair,
        List<String> explanations,
        List<String> mandatoryConstraintViolations,
        boolean infeasible,
        String infeasibilityReason
) {
    public record FairnessScoreBreakdown(
            int totalScore,
            int preferenceScore,
            int continuityScore,
            int nightFairnessScore,
            int eveningFairnessScore,
            int workloadBalanceScore,
            int weeklyOffDistributionScore,
            int rollingHistoryScore,
            int diversityScore
    ) {}

    public record EmployeeFairnessDetail(
            Long employeeId,
            String employeeCode,
            String employeeName,
            String gender,
            int totalWorkingShifts,
            int morningShifts,
            int generalShifts,
            int eveningShifts,
            int nightShifts,
            int weeklyOffCount,
            List<LocalDate> weeklyOffDates,
            int eligibleNightOpportunities,
            int eligibleEveningOpportunities,
            double preferenceSatisfactionRate,
            int avoidableTransitionsCount,
            int rollingHistoryNights,
            int rollingHistoryEvenings,
            int rollingHistoryWorkingShifts
    ) {}
}
