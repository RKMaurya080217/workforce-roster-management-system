package com.weeklyroster.dto.request;

import java.time.LocalDate;

public record ExportReportRequest(
        String reportType,
        String format,
        LocalDate startDate,
        LocalDate endDate,
        Long cycleId,
        Long employeeId,
        String shiftType
) {}
