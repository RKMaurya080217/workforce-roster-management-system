package com.weeklyroster.dto.response;

import java.time.LocalDate;
import java.util.List;

public record DailyCoverageReport(
    LocalDate date,
    int activeEmployees,
    int plannedWorking,
    int plannedOff,
    int dailyConfiguredDemand,
    int dailyFeasibleCapacity,
    int dailyAssigned,
    int dailyOperationalShortage,
    int dailyShortage,
    List<ShiftCoverageSummary> shiftSummaries
) {
    public DailyCoverageReport(
        LocalDate date,
        int activeEmployees,
        int plannedWorking,
        int plannedOff,
        int dailyConfiguredDemand,
        int dailyFeasibleCapacity,
        int dailyAssigned,
        int dailyOperationalShortage,
        List<ShiftCoverageSummary> shiftSummaries
    ) {
        this(date, activeEmployees, plannedWorking, plannedOff, dailyConfiguredDemand,
             dailyFeasibleCapacity, dailyAssigned, dailyOperationalShortage,
             dailyOperationalShortage, shiftSummaries);
    }

    public DailyCoverageReport(
        LocalDate date,
        List<ShiftCoverageSummary> shiftSummaries,
        int dailyShortage
    ) {
        this(date, 7, 6, 1, 8, 6, 6, dailyShortage, dailyShortage, shiftSummaries);
    }
}
