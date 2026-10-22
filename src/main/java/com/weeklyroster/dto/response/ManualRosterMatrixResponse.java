package com.weeklyroster.dto.response;

import com.weeklyroster.entity.RosterStatus;
import com.weeklyroster.entity.ShiftType;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record ManualRosterMatrixResponse(
        Long cycleId,
        LocalDate startDate,
        LocalDate endDate,
        RosterStatus status,
        int workingDays,
        List<LocalDate> dates,
        List<ManualRosterEmployeeRow> rows,
        Map<LocalDate, Map<ShiftType, Long>> dailyShiftCounts,
        Map<LocalDate, Long> dailyTotalWorking,
        Map<LocalDate, Long> dailyTotalOff,
        List<String> validationWarnings,
        int criticalConflicts,
        boolean isPublished
) {}
