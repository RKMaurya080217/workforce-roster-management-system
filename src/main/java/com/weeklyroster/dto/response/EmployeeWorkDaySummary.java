package com.weeklyroster.dto.response;

import com.weeklyroster.entity.Gender;
import java.util.List;

public record EmployeeWorkDaySummary(
    Long employeeId,
    String employeeCode,
    String employeeName,
    Gender gender,
    int workedDays,
    int holidayDays,
    int leaveDays,
    int weeklyOffDays,
    int totalPeriodDays,
    List<EmployeeDailyRecord> dailyRecords
) {}
