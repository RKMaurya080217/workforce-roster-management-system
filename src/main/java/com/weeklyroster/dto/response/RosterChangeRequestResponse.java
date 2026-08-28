package com.weeklyroster.dto.response;

import com.weeklyroster.entity.Gender;
import com.weeklyroster.entity.RosterChangeStatus;
import com.weeklyroster.entity.ShiftType;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record RosterChangeRequestResponse(
        Long id,
        Long employeeId,
        String employeeCode,
        String employeeName,
        Gender gender,
        Long cycleId,
        Long assignmentId,
        LocalDate rosterDate,
        String dayOfWeek,
        ShiftType currentShiftType,
        boolean currentWeeklyOff,
        ShiftType requestedShiftType,
        boolean requestedWeeklyOff,
        String reason,
        RosterChangeStatus status,
        String adminRemarks,
        LocalDateTime createdAt,
        LocalDateTime decidedAt,
        String decidedBy,
        boolean canCancel
) {}
