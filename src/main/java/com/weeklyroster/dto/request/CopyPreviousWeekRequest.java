package com.weeklyroster.dto.request;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record CopyPreviousWeekRequest(
        Long cycleId,
        @NotNull LocalDate targetStartDate,
        LocalDate sourceStartDate,
        Boolean includeWeeklyOff,
        Boolean overwriteExistingManual,
        Boolean previewOnly
) {
    public CopyPreviousWeekRequest(Long cycleId, LocalDate targetStartDate) {
        this(cycleId, targetStartDate, null, true, false, false);
    }
}
