package com.weeklyroster.controller;

import com.weeklyroster.dto.request.ApplyLeaveRequest;
import com.weeklyroster.dto.request.CancelLeaveRequest;
import com.weeklyroster.dto.request.LeaveDecisionRequest;
import com.weeklyroster.dto.request.ModifyLeaveRequest;
import com.weeklyroster.dto.response.LeaveResponse;
import com.weeklyroster.service.LeaveService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/leaves")
public class LeaveController {
    private final LeaveService leaveService;

    public LeaveController(LeaveService leaveService) {
        this.leaveService = leaveService;
    }

    @PostMapping
    public ResponseEntity<LeaveResponse> apply(@Valid @RequestBody ApplyLeaveRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(leaveService.apply(request));
    }

    @PostMapping("/{id}/modification")
    public ResponseEntity<LeaveResponse> requestModification(@PathVariable("id") Long id,
                                                            @Valid @RequestBody ModifyLeaveRequest request) {
        return ResponseEntity.ok(leaveService.requestModification(id, request));
    }

    @PostMapping("/{id}/cancellation")
    public ResponseEntity<LeaveResponse> requestCancellation(@PathVariable("id") Long id,
                                                            @Valid @RequestBody CancelLeaveRequest request) {
        return ResponseEntity.ok(leaveService.requestCancellation(id, request));
    }

    @GetMapping("/my/{employeeId}")
    public ResponseEntity<List<LeaveResponse>> myLeaves(@PathVariable("employeeId") Long employeeId) {
        return ResponseEntity.ok(leaveService.myLeaves(employeeId));
    }

    @GetMapping("/pending")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<List<LeaveResponse>> pending() {
        return ResponseEntity.ok(leaveService.pending());
    }

    @PutMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<LeaveResponse> approve(@PathVariable("id") Long id,
                                                 @RequestBody(required = false) LeaveDecisionRequest request) {
        return ResponseEntity.ok(leaveService.approve(id, request == null ? new LeaveDecisionRequest(null) : request));
    }

    @PutMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<LeaveResponse> reject(@PathVariable("id") Long id,
                                                @RequestBody(required = false) LeaveDecisionRequest request) {
        return ResponseEntity.ok(leaveService.reject(id, request == null ? new LeaveDecisionRequest(null) : request));
    }

    @PutMapping("/{id}/modification/approve")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<LeaveResponse> approveModification(@PathVariable("id") Long id,
                                                             @RequestBody(required = false) LeaveDecisionRequest request) {
        return ResponseEntity.ok(leaveService.approveModification(id, request == null ? new LeaveDecisionRequest(null) : request));
    }

    @PutMapping("/{id}/modification/reject")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<LeaveResponse> rejectModification(@PathVariable("id") Long id,
                                                             @RequestBody(required = false) LeaveDecisionRequest request) {
        return ResponseEntity.ok(leaveService.rejectModification(id, request == null ? new LeaveDecisionRequest(null) : request));
    }

    @PutMapping("/{id}/cancellation/approve")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<LeaveResponse> approveCancellation(@PathVariable("id") Long id,
                                                             @RequestBody(required = false) LeaveDecisionRequest request) {
        return ResponseEntity.ok(leaveService.approveCancellation(id, request == null ? new LeaveDecisionRequest(null) : request));
    }

    @PutMapping("/{id}/cancellation/reject")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<LeaveResponse> rejectCancellation(@PathVariable("id") Long id,
                                                             @RequestBody(required = false) LeaveDecisionRequest request) {
        return ResponseEntity.ok(leaveService.rejectCancellation(id, request == null ? new LeaveDecisionRequest(null) : request));
    }
}
