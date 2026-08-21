package com.weeklyroster.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RosterSwapRequest(
        @NotNull Long assignmentId1,
        @NotNull Long assignmentId2,
        @Size(max = 500) String reason
) {
}
