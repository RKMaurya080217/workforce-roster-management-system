package com.weeklyroster.dto.response;

import java.util.List;

public record DashboardDetailResponse(
        DashboardResponse summary,
        List<RosterAssignmentResponse> todaysAssignments,
        List<LeaveResponse> pendingLeaves,
        List<EmployeeResponse> activeEmployees,
        List<EmployeeResponse> inactiveEmployees,
        RosterCycleResponse currentCycle
) {
}
