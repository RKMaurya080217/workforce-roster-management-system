package com.weeklyroster.dto.request;

import jakarta.validation.constraints.Min;
import java.time.LocalTime;

public record UpdateShiftRequest(
        @Min(value = 0, message = "Shift capacity cannot be negative") Integer capacity,
        LocalTime startTime,
        LocalTime endTime,
        Boolean overnight
) {
}
