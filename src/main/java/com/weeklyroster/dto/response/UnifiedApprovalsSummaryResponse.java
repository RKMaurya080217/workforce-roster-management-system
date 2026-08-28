package com.weeklyroster.dto.response;

public record UnifiedApprovalsSummaryResponse(
        long totalPending,
        long profileRequestsCount,
        long leaveRequestsCount,
        long preferenceRequestsCount,
        long rosterChangeRequestsCount
) {
    public UnifiedApprovalsSummaryResponse(long totalPending, long profileRequestsCount, long leaveRequestsCount, long preferenceRequestsCount) {
        this(totalPending, profileRequestsCount, leaveRequestsCount, preferenceRequestsCount, 0);
    }
}
