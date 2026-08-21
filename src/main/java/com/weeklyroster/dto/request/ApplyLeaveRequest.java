package com.weeklyroster.dto.request;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record ApplyLeaveRequest(
        @NotNull Long employeeId,
        @NotNull @FutureOrPresent LocalDate startDate,
        @NotNull @FutureOrPresent LocalDate endDate,
        @NotBlank @Size(max = 500) String reason
) {
}
