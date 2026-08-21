package com.weeklyroster.controller;

import com.weeklyroster.dto.request.ProfileChangeDecisionRequest;
import com.weeklyroster.dto.response.ProfileChangeRequestResponse;
import com.weeklyroster.service.ProfileChangeRequestService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/profile-change-requests")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
public class AdminProfileChangeRequestController {

    private final ProfileChangeRequestService profileChangeRequestService;

    public AdminProfileChangeRequestController(ProfileChangeRequestService profileChangeRequestService) {
        this.profileChangeRequestService = profileChangeRequestService;
    }

    @GetMapping("/pending")
    public ResponseEntity<List<ProfileChangeRequestResponse>> getPendingRequests() {
        return ResponseEntity.ok(profileChangeRequestService.getPendingRequests());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProfileChangeRequestResponse> getById(@PathVariable("id") Long id) {
        return ResponseEntity.ok(profileChangeRequestService.getById(id));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<ProfileChangeRequestResponse> approve(@PathVariable("id") Long id,
                                                                @Valid @RequestBody(required = false) ProfileChangeDecisionRequest request) {
        return ResponseEntity.ok(profileChangeRequestService.approve(id, request));
    }

    @PutMapping("/{id}/approve")
    public ResponseEntity<ProfileChangeRequestResponse> approvePut(@PathVariable("id") Long id,
                                                                   @Valid @RequestBody(required = false) ProfileChangeDecisionRequest request) {
        return ResponseEntity.ok(profileChangeRequestService.approve(id, request));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<ProfileChangeRequestResponse> reject(@PathVariable("id") Long id,
                                                               @Valid @RequestBody(required = false) ProfileChangeDecisionRequest request) {
        return ResponseEntity.ok(profileChangeRequestService.reject(id, request));
    }

    @PutMapping("/{id}/reject")
    public ResponseEntity<ProfileChangeRequestResponse> rejectPut(@PathVariable("id") Long id,
                                                                  @Valid @RequestBody(required = false) ProfileChangeDecisionRequest request) {
        return ResponseEntity.ok(profileChangeRequestService.reject(id, request));
    }
}
