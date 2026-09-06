package com.weeklyroster.controller;

import com.weeklyroster.dto.request.LeaveDecisionRequest;
import com.weeklyroster.dto.request.PreferenceDecisionRequest;
import com.weeklyroster.dto.request.ProfileChangeDecisionRequest;
import com.weeklyroster.dto.response.*;
import com.weeklyroster.service.UnifiedApprovalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/approvals")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@Tag(name = "Unified Admin Approvals", description = "Centralized endpoints for reviewing Profile, Leave, and Shift Preference requests")
public class UnifiedApprovalController {

    private final UnifiedApprovalService unifiedApprovalService;

    public UnifiedApprovalController(UnifiedApprovalService unifiedApprovalService) {
        this.unifiedApprovalService = unifiedApprovalService;
    }

    @GetMapping("/summary")
    @Operation(summary = "Get dynamic pending counts across Profile, Leave, and Preference approvals")
    public ResponseEntity<UnifiedApprovalsSummaryResponse> getSummary() {
        return ResponseEntity.ok(unifiedApprovalService.getSummary());
    }

    @GetMapping("/all")
    @Operation(summary = "Get all pending requests grouped by category (Profile, Leave, Preference)")
    public ResponseEntity<UnifiedApprovalsResponse> getAllPending() {
        return ResponseEntity.ok(unifiedApprovalService.getAllPending());
    }

    @RequestMapping(value = "/profile/{id}/approve", method = {RequestMethod.POST, RequestMethod.PUT})
    @Operation(summary = "Approve profile change request")
    public ResponseEntity<ProfileChangeRequestResponse> approveProfile(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) ProfileChangeDecisionRequest req) {
        return ResponseEntity.ok(unifiedApprovalService.decideProfile(id, true, req));
    }

    @RequestMapping(value = "/profile/{id}/reject", method = {RequestMethod.POST, RequestMethod.PUT})
    @Operation(summary = "Reject profile change request")
    public ResponseEntity<ProfileChangeRequestResponse> rejectProfile(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) ProfileChangeDecisionRequest req) {
        return ResponseEntity.ok(unifiedApprovalService.decideProfile(id, false, req));
    }

    @RequestMapping(value = "/leave/{id}/approve", method = {RequestMethod.POST, RequestMethod.PUT})
    @Operation(summary = "Approve leave request")
    public ResponseEntity<LeaveResponse> approveLeave(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) LeaveDecisionRequest req) {
        return ResponseEntity.ok(unifiedApprovalService.decideLeave(id, true, req));
    }

    @RequestMapping(value = "/leave/{id}/reject", method = {RequestMethod.POST, RequestMethod.PUT})
    @Operation(summary = "Reject leave request")
    public ResponseEntity<LeaveResponse> rejectLeave(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) LeaveDecisionRequest req) {
        return ResponseEntity.ok(unifiedApprovalService.decideLeave(id, false, req));
    }

    @RequestMapping(value = "/preference/{id}/decision", method = {RequestMethod.POST, RequestMethod.PUT})
    @Operation(summary = "Decide employee shift preference request")
    public ResponseEntity<PreferenceResponse> decidePreference(
            @PathVariable Long id,
            @Valid @RequestBody PreferenceDecisionRequest req,
            Authentication auth) {
        return ResponseEntity.ok(unifiedApprovalService.decidePreference(id, req, auth.getName()));
    }
}
