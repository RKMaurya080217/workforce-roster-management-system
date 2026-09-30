package com.weeklyroster.controller;

import com.weeklyroster.service.EmployeeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/employees")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@Tag(name = "Admin Employee Management", description = "Admin endpoints for employee operations")
public class AdminEmployeeController {

    private final EmployeeService employeeService;

    public AdminEmployeeController(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    @GetMapping("/next-id")
    @Operation(summary = "Generate next sequential Employee ID")
    public ResponseEntity<Map<String, String>> getNextEmployeeId() {
        String nextId = employeeService.generateNextEmployeeCode();
        return ResponseEntity.ok(Map.of("employeeId", nextId));
    }
}
