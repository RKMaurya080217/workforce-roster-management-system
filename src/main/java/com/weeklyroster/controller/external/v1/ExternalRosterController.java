package com.weeklyroster.controller.external.v1;

import com.weeklyroster.dto.external.v1.ExternalApiResponse;
import com.weeklyroster.dto.external.v1.ExternalRosterAssignmentResponse;
import com.weeklyroster.dto.external.v1.ExternalRosterCycleResponse;
import com.weeklyroster.security.CorrelationIdFilter;
import com.weeklyroster.service.ExternalApiService;
import com.weeklyroster.util.DateParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/external/v1/rosters")
@Tag(name = "External API - Rosters", description = "Read-only access to published rosters and shift duty assignments")
@SecurityRequirement(name = "apiKeyAuth")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyAuthority('SCOPE_ROSTER_READ', 'ROLE_ADMIN')")
public class ExternalRosterController {

    private final ExternalApiService externalApiService;

    public ExternalRosterController(ExternalApiService externalApiService) {
        this.externalApiService = externalApiService;
    }

    @GetMapping("/current")
    @Operation(summary = "Get Current Weekly Roster", description = "Returns the latest active or published roster cycle with all assignments")
    public ResponseEntity<ExternalApiResponse<ExternalRosterCycleResponse>> getCurrentRoster(HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        ExternalRosterCycleResponse response = externalApiService.getCurrentRosterCycle();
        return ResponseEntity.ok(ExternalApiResponse.success(response, requestId));
    }

    @GetMapping
    @Operation(summary = "Get Roster by Start Date", description = "Returns the roster cycle for the week starting on the given Monday (e.g. 2026-09-14)")
    public ResponseEntity<ExternalApiResponse<ExternalRosterCycleResponse>> getRosterByStartDate(
            @Parameter(description = "Start date of the roster week (YYYY-MM-DD)")
            @RequestParam(name = "startDate", required = false) String startDate,
            HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        if (startDate == null || startDate.isBlank()) {
            return getCurrentRoster(request);
        }
        LocalDate date = DateParser.parse(startDate, LocalDate.now());
        ExternalRosterCycleResponse response = externalApiService.getRosterCycleByStartDate(date);
        return ResponseEntity.ok(ExternalApiResponse.success(response, requestId));
    }

    @GetMapping("/by-date")
    @Operation(summary = "Get Roster Assignments by Specific Date", description = "Returns all shift assignments across the team for a specific calendar date")
    public ResponseEntity<ExternalApiResponse<List<ExternalRosterAssignmentResponse>>> getAssignmentsByDate(
            @Parameter(description = "Calendar date to inspect (YYYY-MM-DD)")
            @RequestParam(name = "date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        List<ExternalRosterAssignmentResponse> assignments = externalApiService.getAssignmentsByDate(date);
        return ResponseEntity.ok(ExternalApiResponse.success(assignments, requestId));
    }

    @GetMapping("/employee/{employeeCode}")
    @Operation(summary = "Get Roster Assignments for an Employee", description = "Returns duty assignments for an employee by their employee code (e.g. EMP001)")
    public ResponseEntity<ExternalApiResponse<List<ExternalRosterAssignmentResponse>>> getAssignmentsForEmployee(
            @Parameter(description = "Unique employee code (e.g. EMP001)")
            @PathVariable("employeeCode") String employeeCode,
            @Parameter(description = "Optional cycle ID to filter assignments")
            @RequestParam(name = "cycleId", required = false) Long cycleId,
            HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        List<ExternalRosterAssignmentResponse> assignments = externalApiService.getAssignmentsForEmployee(employeeCode, cycleId);
        return ResponseEntity.ok(ExternalApiResponse.success(assignments, requestId));
    }

    private String resolveRequestId(HttpServletRequest request) {
        String reqId = (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE_REQUEST_ID);
        if (reqId == null) reqId = request.getHeader(CorrelationIdFilter.HEADER_REQUEST_ID);
        return reqId != null ? reqId : "n/a";
    }
}
