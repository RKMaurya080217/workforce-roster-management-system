package com.weeklyroster.controller;

import com.weeklyroster.dto.request.PreferenceSubmitRequest;
import com.weeklyroster.dto.response.PreferenceResponse;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.service.DateShiftPreferenceService;
import com.weeklyroster.service.EmployeePreferenceService;
import com.weeklyroster.service.RecurringShiftPreferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/preferences")
@Tag(name = "Employee Preferences", description = "Endpoints for employee shift availability and preferences")
public class EmployeePreferenceController {

    private final EmployeePreferenceService preferenceService;
    private final EmployeeRepository employeeRepository;
    private final RecurringShiftPreferenceService recurringShiftPreferenceService;
    private final DateShiftPreferenceService dateShiftPreferenceService;

    public EmployeePreferenceController(EmployeePreferenceService preferenceService,
                                       EmployeeRepository employeeRepository) {
        this(preferenceService, employeeRepository, null, null);
    }

    @Autowired
    public EmployeePreferenceController(EmployeePreferenceService preferenceService,
                                       EmployeeRepository employeeRepository,
                                       @Autowired(required = false) RecurringShiftPreferenceService recurringShiftPreferenceService,
                                       @Autowired(required = false) DateShiftPreferenceService dateShiftPreferenceService) {
        this.preferenceService = preferenceService;
        this.employeeRepository = employeeRepository;
        this.recurringShiftPreferenceService = recurringShiftPreferenceService;
        this.dateShiftPreferenceService = dateShiftPreferenceService;
    }

    @GetMapping
    @Operation(summary = "Get my preference history")
    public ResponseEntity<List<PreferenceResponse>> getPreferences(Authentication auth) {
        Employee emp = resolveEmployee(auth);
        return ResponseEntity.ok(preferenceService.getMyPreferences(emp.getId()));
    }

    @GetMapping("/my")
    @Operation(summary = "Get my preference history")
    public ResponseEntity<List<PreferenceResponse>> getMyPreferences(Authentication auth) {
        Employee emp = resolveEmployee(auth);
        return ResponseEntity.ok(preferenceService.getMyPreferences(emp.getId()));
    }

    @GetMapping("/my/active")
    @Operation(summary = "Get my current active approved preference")
    public ResponseEntity<PreferenceResponse> getMyActivePreference(Authentication auth) {
        Employee emp = resolveEmployee(auth);
        return preferenceService.getMyActiveApprovedPreference(emp.getId())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/my/summary")
    @Operation(summary = "Get full shift preferences summary for authenticated employee")
    public ResponseEntity<Map<String, Object>> getMyPreferenceSummary(Authentication auth) {
        Employee emp = resolveEmployee(auth);
        var recurring = (recurringShiftPreferenceService != null)
                ? recurringShiftPreferenceService.getByEmployee(emp.getId())
                : Collections.emptyList();
        var dateSpecific = (dateShiftPreferenceService != null)
                ? dateShiftPreferenceService.getDatePreferencesByEmployee(emp.getId())
                : Collections.emptyList();
        var general = preferenceService.getMyActiveApprovedPreference(emp.getId()).orElse(null);
        Map<String, Object> result = new HashMap<>();
        result.put("employeeId", emp.getId());
        result.put("employeeCode", emp.getEmployeeCode());
        result.put("recurringPreferences", recurring);
        result.put("dateSpecificPreferences", dateSpecific);
        result.put("generalPreference", general != null ? general : preferenceService.getEmptyPreferenceResponse(emp.getId()));
        return ResponseEntity.ok(result);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Operation(summary = "Submit a shift preference request (Admin only)")
    public ResponseEntity<PreferenceResponse> submitPreference(@Valid @RequestBody PreferenceSubmitRequest req, Authentication auth) {
        Employee emp = resolveEmployee(auth);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(preferenceService.submitPreference(emp.getId(), req, auth.getName()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Operation(summary = "Update an existing shift preference request (Admin only)")
    public ResponseEntity<PreferenceResponse> updatePreference(@PathVariable Long id, @Valid @RequestBody PreferenceSubmitRequest req, Authentication auth) {
        Employee emp = resolveEmployee(auth);
        return ResponseEntity.ok(preferenceService.updatePreference(emp.getId(), id, req, auth.getName()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Operation(summary = "Clear/reset a shift preference request (Admin only)")
    public ResponseEntity<Void> deletePreference(@PathVariable Long id, Authentication auth) {
        Employee emp = resolveEmployee(auth);
        preferenceService.deletePreference(emp.getId(), id, auth.getName());
        return ResponseEntity.noContent().build();
    }

    private Employee resolveEmployee(Authentication auth) {
        String username = auth.getName();
        return employeeRepository.findByUserUsernameIgnoreCase(username)
                .or(() -> employeeRepository.findByEmployeeCodeIgnoreCase(username))
                .orElseThrow(() -> new ResourceNotFoundException("No employee profile associated with: " + username));
    }
}
