package com.weeklyroster.service;

import com.weeklyroster.dto.request.LeaveDecisionRequest;
import com.weeklyroster.dto.request.PreferenceDecisionRequest;
import com.weeklyroster.dto.request.ProfileChangeDecisionRequest;
import com.weeklyroster.dto.response.LeaveResponse;
import com.weeklyroster.dto.response.PreferenceResponse;
import com.weeklyroster.dto.response.ProfileChangeRequestResponse;
import com.weeklyroster.dto.response.UnifiedApprovalsResponse;
import com.weeklyroster.dto.response.RosterChangeRequestResponse;
import com.weeklyroster.dto.response.UnifiedApprovalsSummaryResponse;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UnifiedApprovalService {

    private final ProfileChangeRequestService profileChangeRequestService;
    private final LeaveService leaveService;
    private final EmployeePreferenceService preferenceService;
    private final RosterService rosterService;
    private final com.weeklyroster.repository.RosterCycleRepository cycleRepository;
    private final com.weeklyroster.repository.RosterChangeRequestRepository changeRequestRepository;
    private final RosterReviewService rosterReviewService;

    public UnifiedApprovalService(ProfileChangeRequestService profileChangeRequestService,
                                  LeaveService leaveService,
                                  EmployeePreferenceService preferenceService,
                                  RosterService rosterService,
                                  com.weeklyroster.repository.RosterCycleRepository cycleRepository,
                                  com.weeklyroster.repository.RosterChangeRequestRepository changeRequestRepository,
                                  RosterReviewService rosterReviewService) {
        this.profileChangeRequestService = profileChangeRequestService;
        this.leaveService = leaveService;
        this.preferenceService = preferenceService;
        this.rosterService = rosterService;
        this.cycleRepository = cycleRepository;
        this.changeRequestRepository = changeRequestRepository;
        this.rosterReviewService = rosterReviewService;
    }

    public UnifiedApprovalsSummaryResponse getSummary() {
        List<ProfileChangeRequestResponse> profilePending = profileChangeRequestService.getPendingRequests();
        List<LeaveResponse> leavePending = leaveService.pending();
        List<PreferenceResponse> prefPending = preferenceService.getPendingPreferences();

        long profCount = profilePending != null ? profilePending.size() : 0;
        long leaveCount = leavePending != null ? leavePending.size() : 0;
        long prefCount = prefPending != null ? prefPending.size() : 0;

        long changeCount = (changeRequestRepository != null) ? changeRequestRepository.countByStatus(com.weeklyroster.entity.RosterChangeStatus.PENDING) : 0;
        return new UnifiedApprovalsSummaryResponse(
                profCount + leaveCount + prefCount + changeCount,
                profCount,
                leaveCount,
                prefCount,
                changeCount
        );
    }

    public UnifiedApprovalsResponse getAllPending() {
        List<ProfileChangeRequestResponse> profilePending = profileChangeRequestService.getPendingRequests();
        List<LeaveResponse> leavePending = leaveService.pending();
        List<PreferenceResponse> prefPending = preferenceService.getPendingPreferences();

        long profCount = profilePending != null ? profilePending.size() : 0;
        long leaveCount = leavePending != null ? leavePending.size() : 0;
        long prefCount = prefPending != null ? prefPending.size() : 0;

        List<RosterChangeRequestResponse> changePending = (rosterReviewService != null) ? rosterReviewService.getTeamReviewSummary(null).pendingRequests() : List.of();
        long changeCount = changePending != null ? changePending.size() : 0;
        return new UnifiedApprovalsResponse(
                profCount + leaveCount + prefCount + changeCount,
                profilePending != null ? profilePending : List.of(),
                leavePending != null ? leavePending : List.of(),
                prefPending != null ? prefPending : List.of(),
                changePending != null ? changePending : List.of()
        );
    }

    @Transactional
    public ProfileChangeRequestResponse decideProfile(Long id, boolean approve, ProfileChangeDecisionRequest request) {
        if (approve) {
            return profileChangeRequestService.approve(id, request);
        } else {
            return profileChangeRequestService.reject(id, request);
        }
    }

    @Transactional
    public LeaveResponse decideLeave(Long id, boolean approve, LeaveDecisionRequest request) {
        if (approve) {
            LeaveResponse res = leaveService.approve(id, request != null ? request : new LeaveDecisionRequest(null));
            triggerReoptimizationForDates(res.startDate(), res.endDate(), "Approved Leave Request #" + id);
            return res;
        } else {
            return leaveService.reject(id, request != null ? request : new LeaveDecisionRequest(null));
        }
    }

    private void triggerReoptimizationForDates(java.time.LocalDate start, java.time.LocalDate end, String reason) {
        if (start == null || end == null || rosterService == null || cycleRepository == null) return;
        List<com.weeklyroster.entity.RosterCycle> overlapping = cycleRepository.findOverlappingCycles(start, end);
        for (com.weeklyroster.entity.RosterCycle c : overlapping) {
            if (c.getStatus() != com.weeklyroster.entity.RosterStatus.LOCKED && c.getStatus() != com.weeklyroster.entity.RosterStatus.COMPLETED) {
                try {
                    rosterService.reoptimizeCycle(c.getId(), reason);
                } catch (Exception e) {
                    org.slf4j.LoggerFactory.getLogger(UnifiedApprovalService.class).warn("Failed auto re-optimization on approval for cycle #{}: {}", c.getId(), e.getMessage());
                }
            }
        }
    }

    @Transactional
    public PreferenceResponse decidePreference(Long id, PreferenceDecisionRequest request, String adminUsername) {
        PreferenceResponse res = preferenceService.decidePreference(id, request, adminUsername);
        if (request != null && request.status() == com.weeklyroster.entity.PreferenceStatus.APPROVED) {
            java.time.LocalDate from = res.effectiveFrom() != null ? res.effectiveFrom() : java.time.LocalDate.now();
            java.time.LocalDate to = res.effectiveTo() != null ? res.effectiveTo() : from.plusDays(7);
            triggerReoptimizationForDates(from, to, "Approved Shift Preference #" + id);
        }
        return res;
    }
}
