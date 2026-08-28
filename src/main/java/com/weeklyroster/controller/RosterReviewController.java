package com.weeklyroster.controller;

import com.weeklyroster.dto.request.RosterChangeDecisionRequest;
import com.weeklyroster.dto.request.SubmitRosterChangeRequest;
import com.weeklyroster.dto.response.EmployeeRosterReviewSummaryResponse;
import com.weeklyroster.dto.response.RosterChangeRequestResponse;
import com.weeklyroster.dto.response.TeamRosterReviewSummaryResponse;
import com.weeklyroster.service.RosterReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/roster-review")
@Tag(name = "Employee Roster Review & Change Center", description = "Endpoints for employee tentative review and change request workflow")
public class RosterReviewController {

    private final RosterReviewService reviewService;

    @Autowired
    public RosterReviewController(RosterReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping("/summary")
    @Operation(summary = "Get employee roster review center summary, weekly schedule, and change requests")
    public ResponseEntity<EmployeeRosterReviewSummaryResponse> getEmployeeReviewSummary(
            @RequestParam(required = false) Long cycleId,
            Authentication auth
    ) {
        return ResponseEntity.ok(reviewService.getEmployeeReviewSummary(cycleId, auth.getName()));
    }

    @PostMapping("/request")
    @Operation(summary = "Submit a shift change request for a tentative assignment before Sunday 4 PM deadline")
    public ResponseEntity<RosterChangeRequestResponse> submitChangeRequest(
            @Valid @RequestBody SubmitRosterChangeRequest request,
            Authentication auth
    ) {
        return ResponseEntity.ok(reviewService.submitChangeRequest(request, auth.getName()));
    }

    @DeleteMapping("/request/{id}")
    @Operation(summary = "Cancel a pending shift change request before admin approval")
    public ResponseEntity<RosterChangeRequestResponse> cancelChangeRequest(
            @PathVariable Long id,
            Authentication auth
    ) {
        return ResponseEntity.ok(reviewService.cancelChangeRequest(id, auth.getName()));
    }

    @PostMapping("/mark-complete")
    @Operation(summary = "Mark review as completed for employee")
    public ResponseEntity<Boolean> markReviewComplete(
            @RequestParam(required = false) Long cycleId,
            Authentication auth
    ) {
        return ResponseEntity.ok(reviewService.markReviewComplete(cycleId, auth.getName()));
    }

    @GetMapping("/admin/summary")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get team-wide roster review summary and pending change requests (Admin only)")
    public ResponseEntity<TeamRosterReviewSummaryResponse> getTeamReviewSummary(
            @RequestParam(required = false) Long cycleId
    ) {
        return ResponseEntity.ok(reviewService.getTeamReviewSummary(cycleId));
    }

    @PostMapping("/admin/request/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Approve a shift change request with Batch 40 Change Impact validation (Admin only)")
    public ResponseEntity<RosterChangeRequestResponse> approveChangeRequest(
            @PathVariable Long id,
            @RequestBody(required = false) RosterChangeDecisionRequest decision,
            Authentication auth
    ) {
        return ResponseEntity.ok(reviewService.decideChangeRequest(id, true, decision, auth.getName()));
    }

    @PostMapping("/admin/request/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Reject a shift change request with admin remarks (Admin only)")
    public ResponseEntity<RosterChangeRequestResponse> rejectChangeRequest(
            @PathVariable Long id,
            @RequestBody(required = false) RosterChangeDecisionRequest decision,
            Authentication auth
    ) {
        return ResponseEntity.ok(reviewService.decideChangeRequest(id, false, decision, auth.getName()));
    }
}
