package com.weeklyroster.controller;

import com.weeklyroster.dto.request.RosterChangeImpactRequest;
import com.weeklyroster.dto.response.RosterAssignmentResponse;
import com.weeklyroster.dto.response.RosterChangeImpactResponse;
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.service.RosterChangeImpactService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rosters/impact-preview")
@PreAuthorize("hasRole('ADMIN')")
public class RosterChangeImpactController {

    private final RosterChangeImpactService impactService;

    @Autowired
    public RosterChangeImpactController(RosterChangeImpactService impactService) {
        this.impactService = impactService;
    }

    @GetMapping(value = "/assignment/{assignmentId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RosterChangeImpactResponse> previewAssignmentImpact(
            @PathVariable("assignmentId") Long assignmentId,
            @RequestParam(name = "newShiftType", required = false) ShiftType newShiftType,
            @RequestParam(name = "weeklyOff", defaultValue = "false") boolean weeklyOff) {
        return ResponseEntity.ok(impactService.previewImpact(assignmentId, newShiftType, weeklyOff));
    }

    @PostMapping(value = "/assignment/{assignmentId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RosterChangeImpactResponse> previewAssignmentImpactPost(
            @PathVariable("assignmentId") Long assignmentId,
            @Valid @RequestBody RosterChangeImpactRequest request) {
        return ResponseEntity.ok(impactService.previewImpact(assignmentId, request.newShiftType(), request.weeklyOff()));
    }

    @PostMapping(value = "/assignment/{assignmentId}/apply", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RosterAssignmentResponse> applyAssignmentChange(
            @PathVariable("assignmentId") Long assignmentId,
            @Valid @RequestBody RosterChangeImpactRequest request) {
        return ResponseEntity.ok(impactService.applyChangeWithValidation(
                assignmentId, request.newShiftType(), request.weeklyOff(), request.reason()
        ));
    }
}
