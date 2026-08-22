package com.weeklyroster.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record HolidayRequest(
        @NotBlank(message = "Holiday name is required") String name,
        @NotNull(message = "Holiday date is required") LocalDate holidayDate,
        String description,
        Boolean active
) {}
