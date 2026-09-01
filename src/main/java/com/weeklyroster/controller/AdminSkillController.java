package com.weeklyroster.controller;

import com.weeklyroster.dto.request.AssignEmployeeSkillRequest;
import com.weeklyroster.dto.request.SkillRequest;
import com.weeklyroster.dto.request.UpdateEmployeeSkillRequest;
import com.weeklyroster.dto.response.EmployeeSkillResponse;
import com.weeklyroster.dto.response.SkillResponse;
import com.weeklyroster.service.SkillMatrixService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/skills")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@Tag(name = "Admin Skill Matrix Management", description = "Admin endpoints for skill catalog and employee skill assignments")
public class AdminSkillController {

    private final SkillMatrixService skillService;

    public AdminSkillController(SkillMatrixService skillService) {
        this.skillService = skillService;
    }

    @GetMapping
    @Operation(summary = "Get all catalog skills")
    public ResponseEntity<List<SkillResponse>> getAllSkills() {
        return ResponseEntity.ok(skillService.getAllSkills());
    }

    @PostMapping
    @Operation(summary = "Create a new catalog skill")
    public ResponseEntity<SkillResponse> createSkill(@Valid @RequestBody SkillRequest req, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(skillService.createSkill(req, auth.getName()));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a catalog skill")
    public ResponseEntity<SkillResponse> updateSkill(@PathVariable Long id, @Valid @RequestBody SkillRequest req, Authentication auth) {
        return ResponseEntity.ok(skillService.updateSkill(id, req, auth.getName()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a catalog skill")
    public ResponseEntity<Void> deleteSkill(@PathVariable Long id, Authentication auth) {
        skillService.deleteSkill(id, auth.getName());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/employee-matrix")
    @Operation(summary = "Get matrix of all employee skill assignments")
    public ResponseEntity<List<EmployeeSkillResponse>> getEmployeeSkills() {
        return ResponseEntity.ok(skillService.getAllEmployeeSkills());
    }

    @PostMapping("/assign")
    @Operation(summary = "Assign a skill to an employee")
    public ResponseEntity<EmployeeSkillResponse> assignSkill(@Valid @RequestBody AssignEmployeeSkillRequest req, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(skillService.assignSkillToEmployee(req, auth.getName()));
    }

    @PutMapping("/assignments/{id}")
    @Operation(summary = "Update employee skill assignment")
    public ResponseEntity<EmployeeSkillResponse> updateAssignment(@PathVariable Long id,
                                                                 @RequestBody UpdateEmployeeSkillRequest req,
                                                                 Authentication auth) {
        return ResponseEntity.ok(skillService.updateEmployeeSkill(id, req, auth.getName()));
    }

    @DeleteMapping({"/assignments/{id}", "/employee-skill/{id}"})
    @Operation(summary = "Remove skill assignment from employee")
    public ResponseEntity<Void> removeAssignment(@PathVariable("id") Long id, Authentication auth) {
        skillService.removeSkillFromEmployee(id, auth.getName());
        return ResponseEntity.noContent().build();
    }
}
