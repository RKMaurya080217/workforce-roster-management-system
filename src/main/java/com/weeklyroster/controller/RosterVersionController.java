package com.weeklyroster.controller;

import com.weeklyroster.dto.request.RollbackRequest;
import com.weeklyroster.dto.response.RosterVersionResponse;
import com.weeklyroster.dto.response.RollbackPreviewResponse;
import com.weeklyroster.dto.response.VersionComparisonResponse;
import com.weeklyroster.service.RosterVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/roster-versions")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@Tag(name = "Admin Roster Versioning & Comparison", description = "Admin endpoints for version control, side-by-side comparison, and safe rollback")
public class RosterVersionController {

    private final RosterVersionService versionService;

    public RosterVersionController(RosterVersionService versionService) {
        this.versionService = versionService;
    }

    @GetMapping("/cycle/{cycleId}")
    @Operation(summary = "Get all historical version snapshots for a roster cycle")
    public ResponseEntity<List<RosterVersionResponse>> getCycleVersions(@PathVariable Long cycleId) {
        return ResponseEntity.ok(versionService.getCycleVersions(cycleId));
    }

    @GetMapping("/cycle/{cycleId}/compare")
    @Operation(summary = "Compare two specific roster versions by cycle and version numbers")
    public ResponseEntity<VersionComparisonResponse> compareVersions(
            @PathVariable Long cycleId,
            @RequestParam(defaultValue = "1") int v1,
            @RequestParam(defaultValue = "2") int v2) {
        return ResponseEntity.ok(versionService.compareVersions(cycleId, v1, v2));
    }

    @GetMapping("/cycle/{cycleId}/version/{versionNumber}")
    @Operation(summary = "Get detailed snapshot data of a specific roster version")
    public ResponseEntity<RosterVersionResponse> getVersionDetails(
            @PathVariable Long cycleId,
            @PathVariable int versionNumber) {
        return ResponseEntity.ok(versionService.getVersionDetails(cycleId, versionNumber));
    }

    @GetMapping("/cycle/{cycleId}/rollback-preview/{targetVersionNumber}")
    @Operation(summary = "Preview safety checks and health impact before executing a rollback")
    public ResponseEntity<RollbackPreviewResponse> previewRollback(
            @PathVariable Long cycleId,
            @PathVariable int targetVersionNumber) {
        return ResponseEntity.ok(versionService.previewRollback(cycleId, targetVersionNumber));
    }

    @PostMapping("/cycle/{cycleId}/rollback/{targetVersionNumber}")
    @Operation(summary = "Safely roll back roster state to a historical version (creates a new ROLLBACK version snapshot)")
    public ResponseEntity<RosterVersionResponse> rollbackVersion(
            @PathVariable Long cycleId,
            @PathVariable int targetVersionNumber,
            @RequestBody(required = false) RollbackRequest request,
            org.springframework.security.core.Authentication auth) {
        String actor = auth != null ? auth.getName() : "admin";
        String reason = request != null ? request.reason() : "Admin rollback";
        return ResponseEntity.ok(versionService.rollbackVersion(cycleId, targetVersionNumber, reason, actor));
    }

    @PostMapping("/cycle/{cycleId}/restore/{versionNumber}")
    @Operation(summary = "Legacy restore endpoint (redirects to rollback)")
    public ResponseEntity<RosterVersionResponse> restoreVersion(
            @PathVariable Long cycleId,
            @PathVariable int versionNumber,
            org.springframework.security.core.Authentication auth) {
        String actor = auth != null ? auth.getName() : "admin";
        return ResponseEntity.ok(versionService.rollbackVersion(cycleId, versionNumber, "Restored snapshot from version V" + versionNumber, actor));
    }
}