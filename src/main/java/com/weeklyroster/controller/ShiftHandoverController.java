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
    public ResponseEntity<HandoverResponse> getHandoverById(@PathVariable Long id, Authentication auth) {
        HandoverResponse res = handoverService.getHandoverById(id);
        if (auth != null && auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
            Employee emp = resolveEmployee(auth);
            boolean isCreator = res.fromEmployeeId() != null && res.fromEmployeeId().equals(emp.getId());
            boolean isReliever = res.toEmployeeId() != null && res.toEmployeeId().equals(emp.getId());
            boolean isOpen = res.toEmployeeId() == null;
            if (!isCreator && !isReliever && !isOpen) {
                throw new com.weeklyroster.exception.BusinessException("Access denied: You are not authorized to view this shift handover.");
            }
        }
        return ResponseEntity.ok(res);
    }

    @PostMapping
    @Operation(summary = "Create a shift handover note")
    public ResponseEntity<HandoverResponse> createHandover(@Valid @RequestBody CreateHandoverRequest req, Authentication auth) {
        Long fromEmployeeId = resolveFromEmployeeId(auth, req.fromEmployeeId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(handoverService.createHandover(fromEmployeeId, req, auth != null ? auth.getName() : "system"));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a shift handover note")
    public ResponseEntity<HandoverResponse> updateHandover(@PathVariable Long id,
                                                           @RequestBody UpdateHandoverRequest req,
                                                           Authentication auth) {
        boolean isAdmin = auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        Long empId = null;
        if (!isAdmin) {
            Employee emp = resolveEmployee(auth);
            empId = emp.getId();
        }
        return ResponseEntity.ok(handoverService.updateHandover(id, empId, isAdmin, req, auth != null ? auth.getName() : "system"));
    }

    @PostMapping("/{id}/acknowledge")
    @Operation(summary = "Acknowledge an incoming shift handover note")
    public ResponseEntity<HandoverResponse> acknowledgeHandoverPost(@PathVariable Long id,
                                                                   @RequestParam(required = false) String remarks,
                                                                   @RequestBody(required = false) java.util.Map<String, Object> body,
                                                                   Authentication auth) {
        Employee emp = resolveEmployee(auth);
        String finalRemarks = remarks;
        if ((finalRemarks == null || finalRemarks.isBlank()) && body != null) {
            Object bRemarks = body.get("remarks");
            if (bRemarks != null) finalRemarks = bRemarks.toString();
        }
        return ResponseEntity.ok(handoverService.acknowledgeHandover(id, emp.getId(), finalRemarks, auth != null ? auth.getName() : "system"));
    }

    @PutMapping("/{id}/acknowledge")
    @Operation(summary = "Acknowledge an incoming shift handover note")
    public ResponseEntity<HandoverResponse> acknowledgeHandoverPut(@PathVariable Long id,
                                                                  @RequestParam(required = false) String remarks,
                                                                  @RequestBody(required = false) java.util.Map<String, Object> body,
                                                                  Authentication auth) {
        Employee emp = resolveEmployee(auth);
        String finalRemarks = remarks;
        if ((finalRemarks == null || finalRemarks.isBlank()) && body != null) {
            Object bRemarks = body.get("remarks");
            if (bRemarks != null) finalRemarks = bRemarks.toString();
        }
        return ResponseEntity.ok(handoverService.acknowledgeHandover(id, emp.getId(), finalRemarks, auth != null ? auth.getName() : "system"));
    }

    private Long resolveFromEmployeeId(Authentication auth, Long requestedFromEmployeeId) {
        boolean isAdmin = auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (isAdmin) {
            if (requestedFromEmployeeId != null) {
                Employee fromEmp = employeeRepository.findById(requestedFromEmployeeId)
                        .orElseThrow(() -> new ResourceNotFoundException("From Employee not found with id: " + requestedFromEmployeeId));
                return fromEmp.getId();
            }
            String username = auth.getName();
            return employeeRepository.findByUserUsernameIgnoreCase(username)
                    .or(() -> employeeRepository.findByEmployeeCodeIgnoreCase(username))
                    .or(() -> employeeRepository.findByActiveTrueOrderByIdAsc().stream().findFirst())
                    .or(() -> employeeRepository.findAll().stream().findFirst())
                    .map(Employee::getId)
                    .orElseThrow(() -> new ResourceNotFoundException("No employee profile available in the system."));
        }

        Employee emp = resolveEmployee(auth);
        return emp.getId();
    }

    private Employee resolveEmployee(Authentication auth) {
        String username = auth != null ? auth.getName() : "";
        return employeeRepository.findByUserUsernameIgnoreCase(username)
                .or(() -> employeeRepository.findByEmployeeCodeIgnoreCase(username))
                .orElseThrow(() -> new ResourceNotFoundException("No employee profile associated with: " + username));
    }
}
