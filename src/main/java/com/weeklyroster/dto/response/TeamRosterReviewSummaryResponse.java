package com.weeklyroster.dto.response;

import java.util.List;

public record TeamRosterReviewSummaryResponse(
        Long cycleId,
        long totalEmployees,
        long reviewedEmployeesCount,
        long pendingReviewEmployeesCount,
        long pendingRequestsCount,
        long approvedRequestsCount,
        long rejectedRequestsCount,
        String attentionStatus,
        List<RosterChangeRequestResponse> pendingRequests
) {}
