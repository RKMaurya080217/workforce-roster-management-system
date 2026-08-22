package com.weeklyroster.dto.response;

import java.time.LocalDate;

public record DayCoverageItem(
        LocalDate date,
        String dayName,
        int morning,
        int general,
        int evening,
        int night,
        int off,
        int leave,
        int total
) {}
