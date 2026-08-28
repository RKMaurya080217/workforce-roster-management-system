package com.weeklyroster.dto.response;

import java.util.List;

public record UnifiedApprovalsResponse(
        long totalPending,
        List<ProfileChangeRequestResponse> profileRequests,
        List<LeaveResponse> leaveRequests,
        List<PreferenceResponse> preferenceRequests,
        List<RosterChangeRequestResponse> rosterChangeRequests
) {
    public UnifiedApprovalsResponse(
            long totalPending,
            List<ProfileChangeRequestResponse> profileRequests,
            List<LeaveResponse> leaveRequests,
            List<PreferenceResponse> preferenceRequests
    ) {
        this(totalPending, profileRequests, leaveRequests, preferenceRequests, List.of());
    }
}
