package com.weeklyroster.dto.request;

import java.time.LocalDate;

public record PreferenceSubmitRequest(
        String preferredShiftTypes,
        String preferredOffDays,
        String preferredWorkingDays,
        String avoidShiftTypes,
        String temporaryRestrictions,
        String remarks,
        LocalDate effectiveFrom,
        LocalDate effectiveTo
) {}
