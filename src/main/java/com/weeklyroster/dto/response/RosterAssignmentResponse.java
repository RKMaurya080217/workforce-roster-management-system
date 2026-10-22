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
        boolean overridden,
        String assignmentReason,
        boolean holiday,
        String holidayName,
        boolean manualAssignment,
        String assignmentSource
) {
    public RosterAssignmentResponse(
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
            boolean overridden,
            String assignmentReason,
            boolean holiday,
            String holidayName
    ) {
        this(id, cycleId, rosterDate, employeeId, employeeCode, employeeName, gender, shiftType, weeklyOff, onLeave, overridden, assignmentReason, holiday, holidayName, overridden, overridden ? "MANUAL" : "AUTOMATIC");
    }

    public RosterAssignmentResponse(
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
            boolean overridden,
            String assignmentReason
    ) {
        this(id, cycleId, rosterDate, employeeId, employeeCode, employeeName, gender, shiftType, weeklyOff, onLeave, overridden, assignmentReason, false, null, overridden, overridden ? "MANUAL" : "AUTOMATIC");
    }

    public RosterAssignmentResponse(
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
        this(id, cycleId, rosterDate, employeeId, employeeCode, employeeName, gender, shiftType, weeklyOff, onLeave, overridden, null, false, null, overridden, overridden ? "MANUAL" : "AUTOMATIC");
    }
}
