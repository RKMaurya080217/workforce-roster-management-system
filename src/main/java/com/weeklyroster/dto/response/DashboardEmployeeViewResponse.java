package com.weeklyroster.dto.response;

import java.time.LocalDate;
import java.util.List;

public record DashboardEmployeeViewResponse(
        Long cycleId,
        LocalDate startDate,
        LocalDate endDate,
        List<EmployeeScheduleDto> employees
) {
    public record EmployeeScheduleDto(
            Long employeeId,
            String employeeCode,
            String employeeName,
            String gender,
            int workingDaysCount,
            int offDaysCount,
            int leaveDaysCount,
            int nightShiftsCount,
            List<EmployeeDaySlotDto> schedule
    ) {}

    public record EmployeeDaySlotDto(
            LocalDate date,
            String dayOfWeek,
            String shiftType,
            String shiftTiming,
            boolean weeklyOff,
            boolean onLeave,
            boolean overridden,
            Long assignmentId
    ) {}
}
