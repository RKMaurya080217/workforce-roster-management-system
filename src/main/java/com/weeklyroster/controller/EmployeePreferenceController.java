package com.weeklyroster.controller;

import com.weeklyroster.dto.request.PreferenceSubmitRequest;
import com.weeklyroster.dto.response.PreferenceResponse;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.service.EmployeePreferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/preferences")
@Tag(name = "Employee Preferences", description = "Endpoints for employee shift availability and preferences")
public class EmployeePreferenceController {

    private final EmployeePreferenceService preferenceService;
    private final EmployeeRepository employeeRepository;

    public EmployeePreferenceController(EmployeePreferenceService preferenceService,
                                       EmployeeRepository employeeRepository) {
        this.preferenceService = preferenceService;
        this.employeeRepository = employeeRepository;
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

    @PostMapping
    @Operation(summary = "Submit a shift preference request")
    public ResponseEntity<PreferenceResponse> submitPreference(@Valid @RequestBody PreferenceSubmitRequest req, Authentication auth) {
        Employee emp = resolveEmployee(auth);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(preferenceService.submitPreference(emp.getId(), req, auth.getName()));
    }

    private Employee resolveEmployee(Authentication auth) {
        String username = auth.getName();
        return employeeRepository.findByUserUsernameIgnoreCase(username)
                .or(() -> employeeRepository.findByEmployeeCodeIgnoreCase(username))
                .orElseThrow(() -> new ResourceNotFoundException("No employee profile associated with: " + username));
    }
}
