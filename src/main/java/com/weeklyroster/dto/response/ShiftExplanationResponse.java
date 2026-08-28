package com.weeklyroster.dto.response;

import com.weeklyroster.entity.Gender;
import com.weeklyroster.entity.RosterStatus;
import com.weeklyroster.entity.ShiftType;
import java.time.LocalDate;
import java.util.List;

public record ShiftExplanationResponse(
        Long assignmentId,
        Long cycleId,
        Long employeeId,
        String employeeCode,
        String employeeName,
        Gender gender,
        LocalDate rosterDate,
        String dayOfWeek,
        ShiftType shiftType,
        String shiftName,
        String shiftTiming,
        RosterStatus rosterStatus,
        boolean overridden,
        String adminOverrideReason,
        boolean optimized,
        String optimizationReason,
        List<ExplanationReasonItem> reasons,
        ShiftExplanationAdminDetails adminDetails
) {
    public record ExplanationReasonItem(
            String category,
            String title,
            String description,
            String status,
            String icon
    ) {}

    public record ShiftExplanationAdminDetails(
            String preferenceContribution,
            String continuityContribution,
            String workloadContribution,
            String nightDistributionContribution,
            String coverageContribution,
            Double restIntervalHours,
            String internalScoreNote
    ) {}
}
