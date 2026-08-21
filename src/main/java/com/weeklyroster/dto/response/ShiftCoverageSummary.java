package com.weeklyroster.dto.response;

import com.weeklyroster.entity.ShiftType;

public record ShiftCoverageSummary(
    ShiftType shiftType,
    int configuredCapacity,
    int feasibleCapacity,
    int assignedCount,
    int operationalShortage,
    int shortage,
    int requiredCapacity,
    String status,
    String reason
) {
    public ShiftCoverageSummary(
        ShiftType shiftType,
        int configuredCapacity,
        int feasibleCapacity,
        int assignedCount,
        int operationalShortage,
        String status,
        String reason
    ) {
        this(shiftType, configuredCapacity, feasibleCapacity, assignedCount,
             operationalShortage, operationalShortage, configuredCapacity, status, reason);
    }

    public ShiftCoverageSummary(
        ShiftType shiftType,
        int requiredCapacity,
        int assignedCount,
        int shortage,
        String status,
        String reason
    ) {
        this(shiftType, requiredCapacity, requiredCapacity, assignedCount,
             shortage, shortage, requiredCapacity, status, reason);
    }
}
