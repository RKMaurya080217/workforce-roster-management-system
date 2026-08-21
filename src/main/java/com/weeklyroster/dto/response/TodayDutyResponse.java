package com.weeklyroster.dto.response;

import com.weeklyroster.entity.ShiftType;
import java.time.LocalDate;
import java.time.LocalTime;

public record TodayDutyResponse(
    LocalDate queryDate,
    Long employeeId,
    String employeeCode,
    String employeeName,
    String status, // "WORKING", "OFF", "LEAVE", "NO_ASSIGNMENT"
    ShiftType shiftType,
    String shiftName,
    LocalTime startTime,
    LocalTime endTime,
    boolean overnight,
    String startDateTime,
    String endDateTime,
    String leaveType,
    String leaveReason,
    String source, // "LEAVE", "OVERRIDE", "ROSTER_ASSIGNMENT", "NONE"
    String safetyStatus, // "12h Min Rest Protected"
    String dynamicStatusText, // "Starts in ...", "Currently on duty", "Shift completed", etc.
    DutySummaryDto previousDuty,
    DutySummaryDto nextDuty,
    int activeWorkforceCount
) {}
