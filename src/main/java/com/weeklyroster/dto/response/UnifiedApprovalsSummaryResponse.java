package com.weeklyroster.dto.response;

public record UnifiedApprovalsSummaryResponse(
        long totalPending,
        long profileRequestsCount,
        long leaveRequestsCount,
        long preferenceRequestsCount
) {}
