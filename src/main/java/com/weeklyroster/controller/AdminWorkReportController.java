package com.weeklyroster.controller;

import com.weeklyroster.dto.response.WorkDayReportResponse;
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.service.WorkDayReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping({"/api/admin/reports/work-days", "/api/admin/work-report"})
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@Tag(name = "Admin Work-Day Report", description = "Admin-only work-day, holiday, leave, and weekly-off audit reporting")
public class AdminWorkReportController {

    private final WorkDayReportService workDayReportService;

    public AdminWorkReportController(WorkDayReportService workDayReportService) {
        this.workDayReportService = workDayReportService;
    }

    @GetMapping
    @Operation(summary = "Generate Employee Work-Day Report for date range")
    public ResponseEntity<WorkDayReportResponse> getWorkDayReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) ShiftType shiftType) {
        return ResponseEntity.ok(workDayReportService.generateReport(startDate, endDate, employeeId, shiftType));
    }
}
