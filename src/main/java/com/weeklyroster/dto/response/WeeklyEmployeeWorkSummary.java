package com.weeklyroster.dto.response;

import com.weeklyroster.entity.Gender;
import java.util.List;

public record WeeklyEmployeeWorkSummary(
    Long employeeId,
    String employeeCode,
    String employeeName,
    Gender gender,
    int workedDays,
    int leaveDays,
    int holidayDays,
    int weeklyOffDays,
    int absentDays,
    int totalDays,
    List<EmployeeDailyRecord> dailyRecords
) {}
