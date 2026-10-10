package com.weeklyroster.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;

public record AdminShiftPreferenceRequest(
        @JsonAlias({"preferredShift", "preferredShiftTypes", "shiftType", "shiftPreference"})
        String preferredShift,

        @JsonAlias({"adminRemarks", "remarks", "note"})
        String adminRemarks
) {}
