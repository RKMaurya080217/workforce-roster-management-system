package com.weeklyroster.dto.external.v1;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ExternalLeaveResponse(
        Long id,
        String employeeCode,
        String employeeName,
        LocalDate startDate,
        LocalDate endDate,
        String reason,
        String status,
        LocalDateTime reviewedAt
) {}
