package com.weeklyroster.controller;

import com.weeklyroster.dto.request.CreateHandoverRequest;
import com.weeklyroster.dto.request.UpdateHandoverRequest;
import com.weeklyroster.dto.response.HandoverResponse;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.service.ShiftHandoverService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/admin/handovers")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@Tag(name = "Admin Shift Handover Management", description = "Admin endpoints for inspecting and managing all shift handovers")
public class AdminShiftHandoverController {

    private final ShiftHandoverService handoverService;
    private final EmployeeRepository employeeRepository;

    public AdminShiftHandoverController(ShiftHandoverService handoverService, EmployeeRepository employeeRepository) {
        this.handoverService = handoverService;
        this.employeeRepository = employeeRepository;
    }

    @GetMapping
    @Operation(summary = "Get all shift handovers with optional date filter")
    public ResponseEntity<List<HandoverResponse>> getAllHandovers(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(handoverService.getAllHandovers(startDate, endDate));
    }

    @PostMapping
    @Operation(summary = "Admin create a shift handover note")
    public ResponseEntity<HandoverResponse> createHandover(@Valid @RequestBody CreateHandoverRequest req, Authentication auth) {
        Long fromEmpId = req.fromEmployeeId();
        if (fromEmpId == null) {
            String username = auth != null ? auth.getName() : "";
            fromEmpId = employeeRepository.findByUserUsernameIgnoreCase(username)
                    .or(() -> employeeRepository.findByEmployeeCodeIgnoreCase(username))
                    .or(() -> employeeRepository.findByActiveTrueOrderByIdAsc().stream().findFirst())
                    .or(() -> employeeRepository.findAll().stream().findFirst())
                    .map(Employee::getId)
                    .orElseThrow(() -> new ResourceNotFoundException("No employee profile available in the system."));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(handoverService.createHandover(fromEmpId, req, auth != null ? auth.getName() : "admin"));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Admin update/manage shift handover")
    public ResponseEntity<HandoverResponse> updateHandover(@PathVariable Long id,
                                                           @RequestBody UpdateHandoverRequest req,
                                                           Authentication auth) {
        return ResponseEntity.ok(handoverService.updateHandover(id, null, true, req, auth != null ? auth.getName() : "admin"));
    }
}
