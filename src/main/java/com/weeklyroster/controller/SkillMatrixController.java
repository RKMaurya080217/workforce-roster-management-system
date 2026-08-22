package com.weeklyroster.controller;

import com.weeklyroster.dto.response.EmployeeSkillResponse;
import com.weeklyroster.dto.response.SkillResponse;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.service.SkillMatrixService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/skills")
@Tag(name = "Employee Skills", description = "Endpoints for employee skills and competencies")
public class SkillMatrixController {

    private final SkillMatrixService skillService;
    private final EmployeeRepository employeeRepository;

    public SkillMatrixController(SkillMatrixService skillService,
                                 EmployeeRepository employeeRepository) {
        this.skillService = skillService;
        this.employeeRepository = employeeRepository;
    }

    @GetMapping
    @Operation(summary = "Get all active skills directory")
    public ResponseEntity<List<SkillResponse>> getActiveSkills() {
        return ResponseEntity.ok(skillService.getActiveSkills());
    }

    @GetMapping("/my")
    @Operation(summary = "Get my verified skills and certifications")
    public ResponseEntity<List<EmployeeSkillResponse>> getMySkills(Authentication auth) {
        Employee emp = resolveEmployee(auth);
        return ResponseEntity.ok(skillService.getMySkills(emp.getId()));
    }

    private Employee resolveEmployee(Authentication auth) {
        String username = auth.getName();
        return employeeRepository.findByUserUsernameIgnoreCase(username)
                .or(() -> employeeRepository.findByEmployeeCodeIgnoreCase(username))
                .orElseThrow(() -> new ResourceNotFoundException("No employee profile associated with: " + username));
    }
}
