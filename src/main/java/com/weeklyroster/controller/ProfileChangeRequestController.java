package com.weeklyroster.controller;

import com.weeklyroster.dto.request.CreateProfileChangeRequest;
import com.weeklyroster.dto.response.ProfileChangeRequestResponse;
import com.weeklyroster.service.ProfileChangeRequestService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/profile-change-requests")
public class ProfileChangeRequestController {

    private final ProfileChangeRequestService profileChangeRequestService;

    public ProfileChangeRequestController(ProfileChangeRequestService profileChangeRequestService) {
        this.profileChangeRequestService = profileChangeRequestService;
    }

    @PostMapping
    public ResponseEntity<ProfileChangeRequestResponse> submit(@Valid @RequestBody CreateProfileChangeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(profileChangeRequestService.submitRequest(request));
    }

    @GetMapping("/my")
    public ResponseEntity<List<ProfileChangeRequestResponse>> myRequests() {
        return ResponseEntity.ok(profileChangeRequestService.getMyRequests());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProfileChangeRequestResponse> getById(@PathVariable("id") Long id) {
        return ResponseEntity.ok(profileChangeRequestService.getById(id));
    }
}
