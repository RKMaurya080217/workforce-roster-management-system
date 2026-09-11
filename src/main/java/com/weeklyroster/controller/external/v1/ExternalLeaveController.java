package com.weeklyroster.controller.external.v1;

import com.weeklyroster.dto.external.v1.ExternalApiResponse;
import com.weeklyroster.dto.external.v1.ExternalLeaveResponse;
import com.weeklyroster.security.CorrelationIdFilter;
import com.weeklyroster.service.ExternalApiService;
import com.weeklyroster.util.DateParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/external/v1/leaves")
@Tag(name = "External API - Leaves", description = "Read-only access to approved employee leave requests")
@SecurityRequirement(name = "apiKeyAuth")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyAuthority('SCOPE_LEAVE_READ', 'ROLE_ADMIN')")
public class ExternalLeaveController {

    private final ExternalApiService externalApiService;

    public ExternalLeaveController(ExternalApiService externalApiService) {
        this.externalApiService = externalApiService;
    }

    @GetMapping
    @Operation(summary = "List Approved Leaves", description = "Returns approved employee leaves with optional date range and employee code filtering")
    public ResponseEntity<ExternalApiResponse<List<ExternalLeaveResponse>>> getLeaves(
            @Parameter(description = "Filter leaves starting on or after (YYYY-MM-DD)")
            @RequestParam(name = "startDate", required = false) String startDate,
            @Parameter(description = "Filter leaves ending on or before (YYYY-MM-DD)")
            @RequestParam(name = "endDate", required = false) String endDate,
            @Parameter(description = "Filter leaves for specific employee code (e.g. EMP001)")
            @RequestParam(name = "employeeCode", required = false) String employeeCode,
            HttpServletRequest request) {
        String requestId = resolveRequestId(request);

        LocalDate start = startDate != null && !startDate.isBlank() ? DateParser.parse(startDate, null) : null;
        LocalDate end = endDate != null && !endDate.isBlank() ? DateParser.parse(endDate, null) : null;

        List<ExternalLeaveResponse> leaves = externalApiService.getApprovedLeaves(start, end, employeeCode);
        return ResponseEntity.ok(ExternalApiResponse.success(leaves, requestId));
    }

    private String resolveRequestId(HttpServletRequest request) {
        String reqId = (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE_REQUEST_ID);
        if (reqId == null) reqId = request.getHeader(CorrelationIdFilter.HEADER_REQUEST_ID);
        return reqId != null ? reqId : "n/a";
    }
}
