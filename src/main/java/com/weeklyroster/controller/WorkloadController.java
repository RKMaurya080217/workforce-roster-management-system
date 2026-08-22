package com.weeklyroster.controller;

import com.weeklyroster.dto.response.WorkloadReportResponse;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.service.WorkloadAnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api")
@Tag(name = "Workload Analytics", description = "Endpoints for employee workload scoring and analysis")
public class WorkloadController {

    private final WorkloadAnalyticsService workloadService;
    private final EmployeeRepository employeeRepository;

    public WorkloadController(WorkloadAnalyticsService workloadService,
                              EmployeeRepository employeeRepository) {
        this.workloadService = workloadService;
        this.employeeRepository = employeeRepository;
    }

    @GetMapping("/admin/workload")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Operation(summary = "Get workload metrics for all active personnel")
    public ResponseEntity<WorkloadReportResponse> getWorkloadReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long employeeId) {
        return ResponseEntity.ok(workloadService.calculateWorkload(startDate, endDate, employeeId));
    }

    @GetMapping("/workload/me")
    @Operation(summary = "Get my personal workload metric summary")
    public ResponseEntity<WorkloadReportResponse> getMyWorkload(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            Authentication auth) {
        String username = auth.getName();
        Employee emp = employeeRepository.findByUserUsernameIgnoreCase(username)
                .or(() -> employeeRepository.findByEmployeeCodeIgnoreCase(username))
                .orElseThrow(() -> new ResourceNotFoundException("No employee profile associated with: " + username));

        return ResponseEntity.ok(workloadService.calculateWorkload(startDate, endDate, emp.getId()));
    }
}
