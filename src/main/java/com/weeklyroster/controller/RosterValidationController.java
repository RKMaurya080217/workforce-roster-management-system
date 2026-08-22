package com.weeklyroster.controller;

import com.weeklyroster.dto.response.RosterValidationResponse;
import com.weeklyroster.service.RosterValidatorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/validation")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@Tag(name = "Smart Roster Conflict Validator", description = "Admin endpoints for validating roster compliance and constraint violations")
public class RosterValidationController {

    private final RosterValidatorService validatorService;

    public RosterValidationController(RosterValidatorService validatorService) {
        this.validatorService = validatorService;
    }

    @GetMapping("/active")
    @Operation(summary = "Validate active roster cycle for constraint violations")
    public ResponseEntity<RosterValidationResponse> validateActive() {
        return ResponseEntity.ok(validatorService.validateActiveRoster());
    }

    @GetMapping("/cycle/{cycleId}")
    @Operation(summary = "Validate a specific roster cycle for constraint violations")
    public ResponseEntity<RosterValidationResponse> getValidateCycle(@PathVariable Long cycleId) {
        return ResponseEntity.ok(validatorService.validateRoster(cycleId));
    }

    @PostMapping("/cycle/{cycleId}")
    @Operation(summary = "Validate a specific roster cycle for constraint violations")
    public ResponseEntity<RosterValidationResponse> validateCycle(@PathVariable Long cycleId) {
        return ResponseEntity.ok(validatorService.validateRoster(cycleId));
    }

    @GetMapping
    @Operation(summary = "Validate active or specified roster cycle")
    public ResponseEntity<RosterValidationResponse> validateRosterParam(@RequestParam(required = false) Long cycleId) {
        if (cycleId != null) {
            return ResponseEntity.ok(validatorService.validateRoster(cycleId));
        }
        return ResponseEntity.ok(validatorService.validateActiveRoster());
    }
}
