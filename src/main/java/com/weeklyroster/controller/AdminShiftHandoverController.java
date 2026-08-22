package com.weeklyroster.controller;

import com.weeklyroster.dto.request.UpdateHandoverRequest;
import com.weeklyroster.dto.response.HandoverResponse;
import com.weeklyroster.service.ShiftHandoverService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/admin/handovers")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@Tag(name = "Admin Shift Handover Management", description = "Admin endpoints for inspecting and managing all shift handovers")
public class AdminShiftHandoverController {

    private final ShiftHandoverService handoverService;

    public AdminShiftHandoverController(ShiftHandoverService handoverService) {
        this.handoverService = handoverService;
    }

    @GetMapping
    @Operation(summary = "Get all shift handovers with optional date filter")
    public ResponseEntity<List<HandoverResponse>> getAllHandovers(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(handoverService.getAllHandovers(startDate, endDate));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Admin update/manage shift handover")
    public ResponseEntity<HandoverResponse> updateHandover(@PathVariable Long id,
                                                           @RequestBody UpdateHandoverRequest req,
                                                           Authentication auth) {
        return ResponseEntity.ok(handoverService.updateHandover(id, null, true, req, auth.getName()));
    }
}
