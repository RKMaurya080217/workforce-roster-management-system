package com.weeklyroster.dto.response;

import com.weeklyroster.entity.Gender;
import java.time.LocalDate;
import java.util.Map;

public record ManualRosterEmployeeRow(
        Long employeeId,
        String employeeCode,
        String employeeName,
        Gender gender,
        Map<LocalDate, ManualRosterCellResponse> cells,
        int totalWorkingDays,
        int totalNights,
        int totalOffs
) {}
