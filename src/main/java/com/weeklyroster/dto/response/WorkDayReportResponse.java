package com.weeklyroster.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record WorkDayReportResponse(
    LocalDate startDate,
    LocalDate endDate,
    int totalPeriodDays,
    int totalEmployees,
    int totalWorkedDays,
    int totalHolidayDays,
    int totalLeaveDays,
    int totalWeeklyOffDays,
    List<EmployeeWorkDaySummary> employeeSummaries,
    LocalDateTime generatedAt
) {}
