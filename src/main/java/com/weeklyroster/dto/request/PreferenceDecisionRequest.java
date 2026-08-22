package com.weeklyroster.dto.request;

import com.weeklyroster.entity.PreferenceStatus;
import jakarta.validation.constraints.NotNull;

public record PreferenceDecisionRequest(
        @NotNull(message = "Decision status is required") PreferenceStatus status,
        String adminRemarks
) {}
