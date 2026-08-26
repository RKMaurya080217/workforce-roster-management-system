package com.weeklyroster.service;

import com.weeklyroster.dto.request.LeaveDecisionRequest;
import com.weeklyroster.dto.request.PreferenceDecisionRequest;
import com.weeklyroster.dto.request.ProfileChangeDecisionRequest;
import com.weeklyroster.dto.response.LeaveResponse;
import com.weeklyroster.dto.response.PreferenceResponse;
import com.weeklyroster.dto.response.ProfileChangeRequestResponse;
import com.weeklyroster.dto.response.UnifiedApprovalsResponse;
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

    public UnifiedApprovalService(ProfileChangeRequestService profileChangeRequestService,
                                  LeaveService leaveService,
                                  EmployeePreferenceService preferenceService) {
        this.profileChangeRequestService = profileChangeRequestService;
        this.leaveService = leaveService;
        this.preferenceService = preferenceService;
    }

    public UnifiedApprovalsSummaryResponse getSummary() {
        List<ProfileChangeRequestResponse> profilePending = profileChangeRequestService.getPendingRequests();
        List<LeaveResponse> leavePending = leaveService.pending();
        List<PreferenceResponse> prefPending = preferenceService.getPendingPreferences();

        long profCount = profilePending != null ? profilePending.size() : 0;
        long leaveCount = leavePending != null ? leavePending.size() : 0;
        long prefCount = prefPending != null ? prefPending.size() : 0;

        return new UnifiedApprovalsSummaryResponse(
                profCount + leaveCount + prefCount,
                profCount,
                leaveCount,
                prefCount
        );
    }

    public UnifiedApprovalsResponse getAllPending() {
        List<ProfileChangeRequestResponse> profilePending = profileChangeRequestService.getPendingRequests();
        List<LeaveResponse> leavePending = leaveService.pending();
        List<PreferenceResponse> prefPending = preferenceService.getPendingPreferences();

        long profCount = profilePending != null ? profilePending.size() : 0;
        long leaveCount = leavePending != null ? leavePending.size() : 0;
        long prefCount = prefPending != null ? prefPending.size() : 0;

        return new UnifiedApprovalsResponse(
                profCount + leaveCount + prefCount,
                profilePending != null ? profilePending : List.of(),
                leavePending != null ? leavePending : List.of(),
                prefPending != null ? prefPending : List.of()
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
            return leaveService.approve(id, request != null ? request : new LeaveDecisionRequest(null));
        } else {
            return leaveService.reject(id, request != null ? request : new LeaveDecisionRequest(null));
        }
    }

    @Transactional
    public PreferenceResponse decidePreference(Long id, PreferenceDecisionRequest request, String adminUsername) {
        return preferenceService.decidePreference(id, request, adminUsername);
    }
}
