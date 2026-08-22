package com.weeklyroster.dto.response;

import java.time.LocalDate;

public record VersionAssignmentDiff(
        String employeeCode,
        String employeeName,
        LocalDate date,
        String dayOfWeek,
        String v1Shift,
        String v2Shift,
        boolean changed
) {}
