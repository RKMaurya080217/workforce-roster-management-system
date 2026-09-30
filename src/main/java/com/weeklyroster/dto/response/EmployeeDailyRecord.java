package com.weeklyroster.dto.response;

import com.weeklyroster.entity.ShiftType;
import java.time.LocalDate;

public record EmployeeDailyRecord(
    LocalDate date,
    String dayOfWeek,
    String status,
    String shiftName,
    ShiftType shiftType,
    String remarks
) {}
