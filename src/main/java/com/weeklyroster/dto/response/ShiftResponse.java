package com.weeklyroster.dto.response;

import java.time.LocalTime;
import com.weeklyroster.entity.ShiftType;

public record ShiftResponse(
        Long id,
        ShiftType shiftType,
        int capacity,
        int feasibleCapacity,
        boolean active,
        LocalTime startTime,
        LocalTime endTime,
        boolean overnight,
        String timingDisplay
) {
    public ShiftResponse(
            Long id,
            ShiftType shiftType,
            int capacity,
            boolean active,
            LocalTime startTime,
            LocalTime endTime,
            boolean overnight,
            String timingDisplay
    ) {
        this(id, shiftType, capacity, capacity, active, startTime, endTime, overnight, timingDisplay);
    }
}
