package com.weeklyroster.dto.external.v1;

import java.time.LocalDate;

public record ExternalRosterAssignmentResponse(
        Long id,
        LocalDate date,
        String dayOfWeek,
        String shiftType,
        String employeeCode,
        String employeeName,
        boolean weeklyOff,
        boolean onLeave
) {}
