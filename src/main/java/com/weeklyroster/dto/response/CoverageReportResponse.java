package com.weeklyroster.dto.response;

import java.util.List;

public record CoverageReportResponse(
    int configuredDemand,
    int workforceCapacity,
    int feasibleCapacity,
    int totalAssigned,
    int operationalShortage,
    int configuredShortage,
    int totalRequired,
    int totalShortage,
    List<DailyCoverageReport> dailyReports,
    List<String> warnings
) {
    public CoverageReportResponse(
        int configuredDemand,
        int workforceCapacity,
        int feasibleCapacity,
        int totalAssigned,
        int operationalShortage,
        int configuredShortage,
        List<DailyCoverageReport> dailyReports,
        List<String> warnings
    ) {
        this(configuredDemand, workforceCapacity, feasibleCapacity, totalAssigned,
             operationalShortage, configuredShortage, configuredDemand,
             operationalShortage, dailyReports, warnings);
    }

    public CoverageReportResponse(
        int totalRequired,
        int totalAssigned,
        int totalShortage,
        List<DailyCoverageReport> dailyReports,
        List<String> warnings
    ) {
        this(totalRequired, totalAssigned, totalAssigned, totalAssigned,
             totalShortage, Math.max(0, totalRequired - totalAssigned),
             totalRequired, totalShortage, dailyReports, warnings);
    }
}
