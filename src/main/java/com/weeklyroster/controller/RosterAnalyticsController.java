package com.weeklyroster.controller;

import com.weeklyroster.dto.response.RosterAnalyticsResponse;
import com.weeklyroster.service.RosterAnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/admin/analytics")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@Tag(name = "Admin Roster Analytics Dashboard", description = "Admin multi-dimensional analytics and workforce KPIs")
public class RosterAnalyticsController {

    private final RosterAnalyticsService analyticsService;

    public RosterAnalyticsController(RosterAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping
    @Operation(summary = "Get analytics dashboard data for date range or cycle")
    public ResponseEntity<RosterAnalyticsResponse> getAnalytics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long cycleId) {
        return ResponseEntity.ok(analyticsService.getAnalytics(startDate, endDate, cycleId));
    }

    @GetMapping("/summary")
    @Operation(summary = "Get high level analytics summary metrics")
    public ResponseEntity<?> getSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long cycleId) {
        return ResponseEntity.ok(analyticsService.getAnalytics(startDate, endDate, cycleId).summary());
    }

    @GetMapping("/workload")
    @Operation(summary = "Get employee workload distribution metrics")
    public ResponseEntity<?> getWorkload(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long cycleId) {
        return ResponseEntity.ok(analyticsService.getAnalytics(startDate, endDate, cycleId).workloadDistribution());
    }

    @GetMapping("/shifts")
    @Operation(summary = "Get shift distribution breakdown")
    public ResponseEntity<?> getShifts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long cycleId) {
        return ResponseEntity.ok(analyticsService.getAnalytics(startDate, endDate, cycleId).shiftDistribution());
    }

    @GetMapping("/coverage")
    @Operation(summary = "Get daily coverage breakdown")
    public ResponseEntity<?> getCoverage(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long cycleId) {
        return ResponseEntity.ok(analyticsService.getAnalytics(startDate, endDate, cycleId).dailyBreakdown());
    }

    @GetMapping("/night-shifts")
    @Operation(summary = "Get night shift analytics")
    public ResponseEntity<?> getNightShifts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long cycleId) {
        var analytics = analyticsService.getAnalytics(startDate, endDate, cycleId);
        var nightShiftData = analytics.workloadDistribution().stream()
                .map(w -> java.util.Map.of(
                        "employeeCode", w.employeeCode(),
                        "employeeName", w.employeeName(),
                        "nightShiftsCount", w.nightShifts(),
                        "maxConsecutiveNights", w.maxConsecutiveNights(),
                        "compliant", w.nightShifts() <= 2
                ))
                .toList();
        return ResponseEntity.ok(java.util.Map.of(
                "totalNightShifts", analytics.summary().nightToday(),
                "employeeNightShifts", nightShiftData
        ));
    }
}
