package com.weeklyroster.controller;

import com.weeklyroster.dto.response.RosterVersionResponse;
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
@Tag(name = "Admin Roster Versioning & Comparison", description = "Admin endpoints for inspecting version history and comparing roster revisions")
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

    @PostMapping("/cycle/{cycleId}/restore/{versionNumber}")
    @Operation(summary = "Restore roster state from a historical version (creates a new RESTORED snapshot)")
    public ResponseEntity<RosterVersionResponse> restoreVersion(
            @PathVariable Long cycleId,
            @PathVariable int versionNumber,
            org.springframework.security.core.Authentication auth) {
        String actor = auth != null ? auth.getName() : "admin";
        return ResponseEntity.ok(versionService.restoreVersion(cycleId, versionNumber, actor));
    }

    @GetMapping("/compare")
    @Operation(summary = "Compare two roster versions by snapshot IDs or version numbers")
    public ResponseEntity<VersionComparisonResponse> compareVersionsFlexible(
            @RequestParam(required = false) Long version1Id,
            @RequestParam(required = false) Long version2Id,
            @RequestParam(required = false) Long cycleId,
            @RequestParam(required = false, defaultValue = "1") Integer v1,
            @RequestParam(required = false, defaultValue = "2") Integer v2) {
        if (version1Id != null && version2Id != null) {
            return ResponseEntity.ok(versionService.compareVersionsByIds(version1Id, version2Id));
        }
        if (cycleId != null) {
            return ResponseEntity.ok(versionService.compareVersions(cycleId, v1, v2));
        }
        throw new com.weeklyroster.exception.BusinessException("Either (version1Id and version2Id) or (cycleId and v1 and v2) must be provided");
    }
}
