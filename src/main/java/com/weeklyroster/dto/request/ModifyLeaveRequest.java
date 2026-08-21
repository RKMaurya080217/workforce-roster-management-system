package com.weeklyroster.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record ModifyLeaveRequest(
        @NotNull(message = "New start date is required")
        LocalDate newStartDate,

        @NotNull(message = "New end date is required")
        LocalDate newEndDate,

        @NotBlank(message = "Reason for modification is required")
        String reason
) {
}
