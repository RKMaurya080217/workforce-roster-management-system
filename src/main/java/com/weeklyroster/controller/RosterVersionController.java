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
    @Operation(summary = "Compare two specific roster versions for diffs")
    public ResponseEntity<VersionComparisonResponse> compareVersions(
            @PathVariable Long cycleId,
            @RequestParam int v1,
            @RequestParam int v2) {
        return ResponseEntity.ok(versionService.compareVersions(cycleId, v1, v2));
    }
}
