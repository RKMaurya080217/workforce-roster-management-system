package com.weeklyroster.dto.response;

public record RosterAnalyticsSummary(
        int totalEmployees,
        int activeEmployees,
        int workingToday,
        int onLeaveToday,
        int offToday,
        int morningToday,
        int generalToday,
        int eveningToday,
        int nightToday,
        double coveragePercentage,
        int totalLeavesInPeriod,
        int pendingLeaves,
        int totalShiftChanges,
        int totalHandoversInPeriod,
        int pendingHandovers,
        int pendingPreferences,
        int activeHolidaysInPeriod
) {}
