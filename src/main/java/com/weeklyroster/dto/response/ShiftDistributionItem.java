package com.weeklyroster.dto.response;

public record ShiftDistributionItem(
        String shiftName,
        int count,
        double percentage
) {}
