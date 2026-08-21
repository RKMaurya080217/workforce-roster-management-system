package com.weeklyroster.dto.response;

import com.weeklyroster.entity.ShiftType;
import java.time.LocalDate;
import java.time.LocalTime;

public record DutySummaryDto(
    LocalDate rosterDate,
    String dayOfWeek,
    String status, // "WORKING", "OFF", "LEAVE", "NO_ASSIGNMENT"
    ShiftType shiftType,
    String shiftName,
    LocalTime startTime,
    LocalTime endTime,
    boolean overnight,
    String startDateTime,
    String endDateTime,
    String source // "LEAVE", "OVERRIDE", "ROSTER_ASSIGNMENT", "NONE"
) {}
