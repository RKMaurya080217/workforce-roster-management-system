package com.weeklyroster.dto.response;

import java.time.LocalDate;
import java.util.List;

public record DashboardDayViewResponse(
        Long cycleId,
        LocalDate startDate,
        LocalDate endDate,
        List<DayScheduleDto> days
) {
    public record DayScheduleDto(
            LocalDate date,
            String dayOfWeek,
            int totalWorking,
            int totalOff,
            int totalLeave,
            ShiftGroupDto morning,
            ShiftGroupDto general,
            ShiftGroupDto evening,
            ShiftGroupDto night,
            List<StaffItemDto> offEmployees,
            List<StaffItemDto> leaveEmployees
    ) {}

    public record ShiftGroupDto(
            String shiftType,
            String timing,
            int required,
            int assigned,
            List<StaffItemDto> employees
    ) {}

    public record StaffItemDto(
            Long employeeId,
            String employeeCode,
            String employeeName,
            String gender,
            Long assignmentId,
            boolean overridden
    ) {}
}
