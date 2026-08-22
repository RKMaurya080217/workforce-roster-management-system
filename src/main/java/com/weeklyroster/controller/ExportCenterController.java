package com.weeklyroster.controller;

import com.weeklyroster.dto.request.ExportReportRequest;
import com.weeklyroster.service.ExportCenterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/admin/exports")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@Tag(name = "Admin Export Center", description = "Admin endpoints for exporting enterprise reports in Excel, PDF, and CSV")
public class ExportCenterController {

    private final ExportCenterService exportCenterService;

    public ExportCenterController(ExportCenterService exportCenterService) {
        this.exportCenterService = exportCenterService;
    }

    @GetMapping("/download")
    @Operation(summary = "Download enterprise report in requested format (.xlsx, .pdf, .csv)")
    public ResponseEntity<byte[]> downloadReport(
            @RequestParam(defaultValue = "WEEKLY_ROSTER") String reportType,
            @RequestParam(defaultValue = "xlsx") String format,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long cycleId,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) String shiftType) {

        ExportReportRequest req = new ExportReportRequest(reportType, format, startDate, endDate, cycleId, employeeId, shiftType);
        byte[] data = exportCenterService.generateExport(req);

        String ext = format.toLowerCase();
        String contentType;
        if ("csv".equals(ext)) {
            contentType = "text/csv; charset=UTF-8";
        } else if ("pdf".equals(ext)) {
            contentType = "application/pdf";
        } else {
            contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            ext = "xlsx";
        }

        String filename = "WRMS_" + reportType.toLowerCase() + "_" + (startDate != null ? startDate : "current") + "." + ext;

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(data);
    }
}
