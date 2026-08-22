package com.weeklyroster.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record RosterAnalyticsResponse(
        LocalDate startDate,
        LocalDate endDate,
        Long cycleId,
        RosterAnalyticsSummary summary,
        List<ShiftDistributionItem> shiftDistribution,
        List<DayCoverageItem> dailyBreakdown,
        List<EmployeeWorkloadMetric> workloadDistribution,
        LocalDateTime generatedAt
) {}
