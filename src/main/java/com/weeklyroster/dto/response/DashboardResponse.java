package com.weeklyroster.dto.response;

public record DashboardResponse(
        long totalEmployees,
        long activeEmployees,
        long inactiveEmployees,
        long todaysMorningEmployees,
        long todaysGeneralEmployees,
        long todaysEveningEmployees,
        long todaysNightEmployees,
        long todaysOffEmployees,
        long todaysLeaveEmployees,
        long pendingLeaveRequests
) {
}
