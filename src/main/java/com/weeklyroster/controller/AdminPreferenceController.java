package com.weeklyroster.controller;

import com.weeklyroster.dto.request.AdminShiftPreferenceRequest;
import com.weeklyroster.dto.request.BulkDateShiftPreferenceRequest;
import com.weeklyroster.dto.request.DateShiftPreferenceRequest;
import com.weeklyroster.dto.request.PreferenceDecisionRequest;
import com.weeklyroster.dto.response.DatePreferencePreviewResponse;
import com.weeklyroster.dto.response.DateShiftPreferenceResponse;
import com.weeklyroster.dto.response.PreferenceResponse;
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.service.DateShiftPreferenceService;
import com.weeklyroster.service.EmployeePreferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/admin/preferences")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@Tag(name = "Admin Preference Management", description = "Admin endpoints for reviewing and managing employee shift preferences")
public class AdminPreferenceController {

    private final EmployeePreferenceService preferenceService;
    private final DateShiftPreferenceService dateShiftPreferenceService;

    public AdminPreferenceController(EmployeePreferenceService preferenceService,
                                   DateShiftPreferenceService dateShiftPreferenceService) {
        this.preferenceService = preferenceService;
        this.dateShiftPreferenceService = dateShiftPreferenceService;
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

    @PutMapping("/{id}/decision")
    @Operation(summary = "Approve or reject an employee preference request")
    public ResponseEntity<PreferenceResponse> decidePreferencePut(@PathVariable Long id,
                                                                 @Valid @RequestBody PreferenceDecisionRequest req,
                                                                 Authentication auth) {
        return ResponseEntity.ok(preferenceService.decidePreference(id, req, auth.getName()));
    }

    @GetMapping("/employee/{employeeId}")
    @Operation(summary = "Get employee shift preference")
    public ResponseEntity<PreferenceResponse> getEmployeePreference(@PathVariable Long employeeId) {
        return preferenceService.getMyActiveApprovedPreference(employeeId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.ok(preferenceService.getEmptyPreferenceResponse(employeeId)));
    }

    @PutMapping("/employee/{employeeId}")
    @Operation(summary = "Set or update employee shift preference by Admin")
    public ResponseEntity<PreferenceResponse> setEmployeePreference(
            @PathVariable Long employeeId,
            @RequestBody AdminShiftPreferenceRequest req,
            Authentication auth) {
        return ResponseEntity.ok(preferenceService.setEmployeeShiftPreferenceByAdmin(employeeId, req, auth.getName()));
    }

    @DeleteMapping("/employee/{employeeId}")
    @Operation(summary = "Clear employee shift preference by Admin")
    public ResponseEntity<Void> clearEmployeePreference(
            @PathVariable Long employeeId,
            Authentication auth) {
        preferenceService.clearEmployeeShiftPreferenceByAdmin(employeeId, auth.getName());
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // BATCH 80: DATE-SPECIFIC & BULK SHIFT PREFERENCES (ADMIN CONTROLLED)
    // =========================================================================

    @GetMapping("/date-specific")
    @Operation(summary = "Get date-specific shift preferences")
    public ResponseEntity<List<DateShiftPreferenceResponse>> getDateSpecificPreferences(
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        if (employeeId != null) {
            return ResponseEntity.ok(dateShiftPreferenceService.getDatePreferencesByEmployee(employeeId));
        } else if (startDate != null && endDate != null) {
            return ResponseEntity.ok(dateShiftPreferenceService.getDatePreferencesForPeriod(startDate, endDate));
        }
        return ResponseEntity.ok(dateShiftPreferenceService.getAllDatePreferences());
    }

    @PostMapping("/date-specific")
    @Operation(summary = "Create or update a date-specific shift preference for an employee")
    public ResponseEntity<DateShiftPreferenceResponse> setDateSpecificPreference(
            @Valid @RequestBody DateShiftPreferenceRequest req,
            Authentication auth) {
        return ResponseEntity.ok(dateShiftPreferenceService.createOrUpdate(req, auth.getName()));
    }

    @PostMapping("/date-specific/bulk")
    @Operation(summary = "Apply a date-specific shift preference to multiple employees in bulk")
    public ResponseEntity<List<DateShiftPreferenceResponse>> setBulkDateSpecificPreference(
            @Valid @RequestBody BulkDateShiftPreferenceRequest req,
            Authentication auth) {
        return ResponseEntity.ok(dateShiftPreferenceService.createOrUpdateBulk(req, auth.getName()));
    }

    @DeleteMapping("/date-specific/{id}")
    @Operation(summary = "Delete a date-specific shift preference")
    public ResponseEntity<Void> deleteDateSpecificPreference(
            @PathVariable Long id,
            Authentication auth) {
        dateShiftPreferenceService.deleteDatePreference(id, auth.getName());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/date-specific/preview")
    @Operation(summary = "Preview and validate a date-specific shift preference against business rules")
    public ResponseEntity<DatePreferencePreviewResponse> previewPreference(
            @RequestParam Long employeeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam ShiftType shiftType) {
        return ResponseEntity.ok(dateShiftPreferenceService.previewPreference(employeeId, date, shiftType));
    }
}
