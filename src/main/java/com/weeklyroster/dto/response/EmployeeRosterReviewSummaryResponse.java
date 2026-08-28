package com.weeklyroster.dto.response;

import com.weeklyroster.entity.RosterStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record EmployeeRosterReviewSummaryResponse(
        Long cycleId,
        LocalDate cycleStartDate,
        LocalDate cycleEndDate,
        RosterStatus cycleStatus,
        LocalDateTime reviewDeadline,
        boolean isReviewOpen,
        String reviewStatus,
        String reviewStatusBadge,
        int totalAssignments,
        int pendingRequestsCount,
        int approvedRequestsCount,
        int rejectedRequestsCount,
        List<RosterAssignmentResponse> assignments,
        List<RosterChangeRequestResponse> pendingRequests,
        List<RosterChangeRequestResponse> requestHistory,
        List<String> preferredShifts,
        List<String> avoidedShifts,
        List<String> preferredOffDays
) {}
