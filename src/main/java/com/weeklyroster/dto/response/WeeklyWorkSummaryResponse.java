package com.weeklyroster.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record WeeklyWorkSummaryResponse(
    LocalDate weekStartDate,
    LocalDate weekEndDate,
    int totalPeriodDays,
    int totalEmployees,
    int totalWorkedDays,
    int totalLeaveDays,
    int totalHolidayDays,
    int totalWeeklyOffDays,
    int totalAbsentDays,
    int totalDaysAccounted,
    List<WeeklyEmployeeWorkSummary> employeeSummaries,
    LocalDateTime generatedAt
) {}
