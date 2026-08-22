package com.weeklyroster.controller;

import com.weeklyroster.dto.request.PreferenceDecisionRequest;
import com.weeklyroster.dto.response.PreferenceResponse;
import com.weeklyroster.service.EmployeePreferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/preferences")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@Tag(name = "Admin Preference Management", description = "Admin endpoints for reviewing employee shift preferences")
public class AdminPreferenceController {

    private final EmployeePreferenceService preferenceService;

    public AdminPreferenceController(EmployeePreferenceService preferenceService) {
        this.preferenceService = preferenceService;
    }

    @GetMapping
    @Operation(summary = "Get all employee preference requests")
    public ResponseEntity<List<PreferenceResponse>> getAllPreferences() {
        return ResponseEntity.ok(preferenceService.getAllPreferences());
    }

    @GetMapping("/pending")
    @Operation(summary = "Get pending employee preference requests")
    public ResponseEntity<List<PreferenceResponse>> getPendingPreferences() {
        return ResponseEntity.ok(preferenceService.getPendingPreferences());
    }

    @PostMapping("/{id}/decision")
    @Operation(summary = "Approve or reject an employee preference request")
    public ResponseEntity<PreferenceResponse> decidePreference(@PathVariable Long id,
                                                              @Valid @RequestBody PreferenceDecisionRequest req,
                                                              Authentication auth) {
        return ResponseEntity.ok(preferenceService.decidePreference(id, req, auth.getName()));
    }
}
