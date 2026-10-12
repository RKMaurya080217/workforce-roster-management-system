package com.weeklyroster.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.List;

public record AdminShiftPreferenceRequest(
        @JsonAlias({"preferredShift", "preferredShiftTypes", "shiftType", "shiftPreference"})
        String preferredShift,

        @JsonAlias({"preferredShifts", "shifts", "preferredShiftList", "selectedShifts"})
        List<String> preferredShifts,

        @JsonAlias({"adminRemarks", "remarks", "note"})
        String adminRemarks
) {
    public AdminShiftPreferenceRequest(String preferredShift, String adminRemarks) {
        this(preferredShift, null, adminRemarks);
    }

    public AdminShiftPreferenceRequest(List<String> preferredShifts, String adminRemarks) {
        this(null, preferredShifts, adminRemarks);
    }
}
