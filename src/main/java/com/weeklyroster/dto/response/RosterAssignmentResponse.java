package com.weeklyroster.dto.response;

import com.weeklyroster.entity.Gender;
import com.weeklyroster.entity.ShiftType;
import java.time.LocalDate;

public record RosterAssignmentResponse(
        Long id,
        Long cycleId,
        LocalDate rosterDate,
        Long employeeId,
        String employeeCode,
        String employeeName,
        Gender gender,
        ShiftType shiftType,
        boolean weeklyOff,
        boolean onLeave,
        boolean overridden
) {
}
