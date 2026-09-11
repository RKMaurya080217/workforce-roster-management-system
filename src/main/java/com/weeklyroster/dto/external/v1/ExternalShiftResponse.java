package com.weeklyroster.dto.external.v1;

public record ExternalShiftResponse(
        Long id,
        String shiftType,
        int capacity,
        boolean active
) {}
