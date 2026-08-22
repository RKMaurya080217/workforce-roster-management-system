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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/handovers")
@Tag(name = "Shift Handovers", description = "Endpoints for employee shift handover management")
public class ShiftHandoverController {

    private final ShiftHandoverService handoverService;
    private final EmployeeRepository employeeRepository;

    public ShiftHandoverController(ShiftHandoverService handoverService,
                                   EmployeeRepository employeeRepository) {
        this.handoverService = handoverService;
        this.employeeRepository = employeeRepository;
    }

    @GetMapping("/my")
    @Operation(summary = "Get handovers created by me")
    public ResponseEntity<List<HandoverResponse>> getMyHandovers(Authentication auth) {
        Employee emp = resolveEmployee(auth);
        return ResponseEntity.ok(handoverService.getMyHandovers(emp.getId()));
    }

    @GetMapping("/incoming")
    @Operation(summary = "Get incoming handovers assigned to me")
    public ResponseEntity<List<HandoverResponse>> getIncomingHandovers(Authentication auth) {
        Employee emp = resolveEmployee(auth);
        return ResponseEntity.ok(handoverService.getIncomingHandovers(emp.getId()));
    }

    @GetMapping("/recent")
    @Operation(summary = "Get recent shift handovers")
    public ResponseEntity<List<HandoverResponse>> getRecentHandovers() {
        return ResponseEntity.ok(handoverService.getRecentHandovers());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get handover note by ID")
    public ResponseEntity<HandoverResponse> getHandoverById(@PathVariable Long id) {
        return ResponseEntity.ok(handoverService.getHandoverById(id));
    }

    @PostMapping
    @Operation(summary = "Create a shift handover note")
    public ResponseEntity<HandoverResponse> createHandover(@Valid @RequestBody CreateHandoverRequest req, Authentication auth) {
        Employee emp = resolveEmployee(auth);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(handoverService.createHandover(emp.getId(), req, auth.getName()));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a shift handover note")
    public ResponseEntity<HandoverResponse> updateHandover(@PathVariable Long id,
                                                           @RequestBody UpdateHandoverRequest req,
                                                           Authentication auth) {
        Employee emp = resolveEmployee(auth);
        return ResponseEntity.ok(handoverService.updateHandover(id, emp.getId(), false, req, auth.getName()));
    }

    private Employee resolveEmployee(Authentication auth) {
        String username = auth.getName();
        return employeeRepository.findByUserUsernameIgnoreCase(username)
                .or(() -> employeeRepository.findByEmployeeCodeIgnoreCase(username))
                .orElseThrow(() -> new ResourceNotFoundException("No employee profile associated with: " + username));
    }
}
