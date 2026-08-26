package com.weeklyroster.dto.response;

import java.util.List;

public record UnifiedApprovalsResponse(
        long totalPending,
        List<ProfileChangeRequestResponse> profileRequests,
        List<LeaveResponse> leaveRequests,
        List<PreferenceResponse> preferenceRequests
) {}
