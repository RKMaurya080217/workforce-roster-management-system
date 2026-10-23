package com.weeklyroster.dto.response;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record WeekComparisonResponse(
        LocalDate weekAStartDate,
        LocalDate weekAEndDate,
        LocalDate weekBStartDate,
        LocalDate weekBEndDate,
        int totalDifferences,
        int shiftChangesCount,
        int offChangesCount,
        int manualFlagsChangesCount,
        List<EmployeeComparisonRow> rows,
        List<String> summaryNotes
) {
    public record EmployeeComparisonRow(
            Long employeeId,
            String employeeCode,
            String employeeName,
            Map<Integer, DayComparisonCell> dayComparisons
    ) {}

    public record DayComparisonCell(
            String dayName,
            String shiftA,
            boolean isOffA,
            boolean isManualA,
            String shiftB,
            boolean isOffB,
            boolean isManualB,
            boolean hasDifference,
            String diffDescription
    ) {}
}
