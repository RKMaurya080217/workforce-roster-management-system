package com.weeklyroster.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record WorkloadReportResponse(
        LocalDate fromDate,
        LocalDate toDate,
        int totalEmployees,
        double averageWorkloadScore,
        List<EmployeeWorkloadMetric> employeeWorkloads,
        LocalDateTime generatedAt
) {}
