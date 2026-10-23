package com.weeklyroster.dto.request;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record AutoSaveDraftRequest(
        Long cycleId,
        @NotNull LocalDate startDate,
        String clientDraftId
) {}
