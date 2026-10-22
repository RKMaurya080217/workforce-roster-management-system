package com.weeklyroster.dto.request;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record CopyPreviousWeekRequest(
        Long cycleId,
        @NotNull LocalDate targetStartDate
) {}
