package com.weeklyroster.dto.request;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record ManualShiftSwapRequest(
        Long cycleId,
        LocalDate startDate,
        @NotNull Long employeeAId,
        @NotNull LocalDate dateA,
        @NotNull Long employeeBId,
        @NotNull LocalDate dateB,
        Boolean allowManualOverride,
        Boolean previewOnly
) {}
