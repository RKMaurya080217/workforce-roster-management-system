package com.weeklyroster.controller;

import com.weeklyroster.dto.sync.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.service.EmployeeMasterSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/admin/employees/sync")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@Tag(name = "Admin Employee Master Sync", description = "Endpoints for synchronizing employee master data across CSV, SQLite, and MySQL")
public class AdminEmployeeSyncController {

    private final EmployeeMasterSyncService syncService;

    public AdminEmployeeSyncController(EmployeeMasterSyncService syncService) {
        this.syncService = syncService;
    }

    @GetMapping("/status")
    @Operation(summary = "Get current sync status, hashes, and record counts")
    public ResponseEntity<SyncStatusResponse> getSyncStatus() {
        return ResponseEntity.ok(syncService.getSyncStatus());
    }

    @PostMapping(value = "/preview", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE, MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_PLAIN_VALUE, "*/*"})
    @Operation(summary = "Preview synchronization diffs without writing changes to the database")
    public ResponseEntity<SyncPreviewResponse> previewSync(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestBody(required = false) String rawBody) throws IOException {

        String csvContent = null;
        if (file != null && !file.isEmpty()) {
            csvContent = new String(file.getBytes(), StandardCharsets.UTF_8);
        } else if (rawBody != null && !rawBody.isBlank()) {
            csvContent = rawBody;
        } else {
            File canonicalFile = syncService.resolveCanonicalFile();
            if (canonicalFile.exists()) {
                return ResponseEntity.ok(syncService.previewSyncFromFile(canonicalFile));
            } else {
                throw new BusinessException("No CSV provided and canonical employee.csv not found on server.");
            }
        }

        return ResponseEntity.ok(syncService.previewSyncFromContent(csvContent));
    }

    @PostMapping(value = "/import", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE, MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_PLAIN_VALUE, "*/*"})
    @Operation(summary = "Import and synchronize employee master data from CSV into database")
    public ResponseEntity<SyncExecutionResult> importCsv(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "applyServerFile", defaultValue = "false") boolean applyServerFile,
            @RequestBody(required = false) String rawBody) throws IOException {

        String csvContent = null;
        if (file != null && !file.isEmpty()) {
            csvContent = new String(file.getBytes(), StandardCharsets.UTF_8);
        } else if (rawBody != null && !rawBody.isBlank()) {
            csvContent = rawBody;
        } else if (applyServerFile) {
            File canonicalFile = syncService.resolveCanonicalFile();
            if (canonicalFile.exists()) {
                return ResponseEntity.ok(syncService.applySyncFromFile(canonicalFile));
            } else {
                throw new BusinessException("Server canonical employee.csv file does not exist.");
            }
        } else {
            throw new BusinessException("Please provide a CSV file, request body, or specify applyServerFile=true");
        }

        return ResponseEntity.ok(syncService.applySyncFromContent(csvContent));
    }

    @GetMapping("/export")
    @Operation(summary = "Download authoritative canonical employee CSV exported from database")
    public ResponseEntity<byte[]> exportCsv() {
        String csv = syncService.exportCanonicalCsvString(null);
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"employee.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(bytes);
    }

    @GetMapping("/compare")
    @Operation(summary = "Compare master data across database and CSV file")
    public ResponseEntity<MasterDataComparisonReport> compareMasterData() {
        return ResponseEntity.ok(syncService.compareMasterData());
    }

    @GetMapping("/admin-export")
    @Operation(summary = "Download safe admin master CSV (excluding passwords and hashes)")
    public ResponseEntity<byte[]> exportAdminMaster() {
        String csv = syncService.exportAdminMasterCsvString();
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"admin_master.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(bytes);
    }
}
