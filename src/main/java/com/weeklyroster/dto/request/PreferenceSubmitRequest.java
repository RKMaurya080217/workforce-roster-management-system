package com.weeklyroster.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.weeklyroster.util.StringOrListDeserializer;
import java.time.LocalDate;

public record PreferenceSubmitRequest(
        @JsonAlias({"preferredShifts", "preferred_shift_types", "preferred_shifts"})
        @JsonDeserialize(using = StringOrListDeserializer.class)
        String preferredShiftTypes,

        @JsonAlias({"preferredOFFDays", "preferred_off_days", "offDays", "off_days"})
        @JsonDeserialize(using = StringOrListDeserializer.class)
        String preferredOffDays,

        @JsonAlias({"preferred_working_days", "workingDays", "working_days", "preferredWorkDays"})
        @JsonDeserialize(using = StringOrListDeserializer.class)
        String preferredWorkingDays,

        @JsonAlias({"avoidShifts", "avoid_shift_types", "avoid_shifts"})
        @JsonDeserialize(using = StringOrListDeserializer.class)
        String avoidShiftTypes,

        @JsonAlias({"temporaryConstraints", "temporary_constraints", "temporary_restrictions", "notes"})
        String temporaryRestrictions,

        @JsonAlias({"comment", "adminRemarks"})
        String remarks,

        LocalDate effectiveFrom,
        LocalDate effectiveTo
) {}

