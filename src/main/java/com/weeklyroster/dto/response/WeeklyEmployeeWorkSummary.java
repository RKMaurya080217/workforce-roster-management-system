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
    int morningCount,
    int generalCount,
    int eveningCount,
    int nightCount,
    List<EmployeeDailyRecord> dailyRecords
) {
    public WeeklyEmployeeWorkSummary(
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
    ) {
        this(employeeId, employeeCode, employeeName, gender, workedDays, leaveDays, holidayDays, weeklyOffDays, absentDays, totalDays, 0, 0, 0, 0, dailyRecords);
    }
}
