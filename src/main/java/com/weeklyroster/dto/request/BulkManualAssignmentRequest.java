package com.weeklyroster.dto.request;

import com.weeklyroster.entity.ShiftType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

public record BulkManualAssignmentRequest(
        Long cycleId,
        LocalDate startDate,
        @NotEmpty List<Long> employeeIds,
        @NotEmpty List<LocalDate> dates,
        @NotNull ShiftType shiftType,
        Boolean weeklyOff,
        String reason
) {}
