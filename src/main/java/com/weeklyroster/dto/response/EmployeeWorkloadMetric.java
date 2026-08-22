package com.weeklyroster.dto.response;

public record EmployeeWorkloadMetric(
        Long employeeId,
        String employeeCode,
        String employeeName,
        String gender,
        int totalAssignments,
        int workingDays,
        int offDays,
        int morningShifts,
        int generalShifts,
        int eveningShifts,
        int nightShifts,
        int maxConsecutiveWorkDays,
        int maxConsecutiveNights,
        int weekendDuties,
        int holidayDuties,
        int shiftChanges,
        double workloadScore,
        String workloadRating
) {}
