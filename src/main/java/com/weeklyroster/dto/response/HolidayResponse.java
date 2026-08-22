package com.weeklyroster.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record HolidayResponse(
        Long id,
        String name,
        LocalDate holidayDate,
        String description,
        boolean active,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
