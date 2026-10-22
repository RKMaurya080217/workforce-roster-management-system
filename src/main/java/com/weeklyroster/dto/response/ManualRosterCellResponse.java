package com.weeklyroster.dto.response;

import com.weeklyroster.entity.ShiftType;
import java.time.LocalDate;

public record ManualRosterCellResponse(
        Long assignmentId,
        LocalDate rosterDate,
        ShiftType shiftType,
        boolean weeklyOff,
        boolean onLeave,
        boolean manualAssignment,
        boolean overridden,
        String assignmentSource,
        String assignmentReason,
        boolean hasConflict,
        String conflictReason
) {}
