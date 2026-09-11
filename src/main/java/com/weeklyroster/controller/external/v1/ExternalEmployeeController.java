package com.weeklyroster.controller.external.v1;

import com.weeklyroster.dto.external.v1.ExternalApiResponse;
import com.weeklyroster.dto.external.v1.ExternalEmployeeResponse;
import com.weeklyroster.security.CorrelationIdFilter;
import com.weeklyroster.service.ExternalApiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/external/v1/employees")
@Tag(name = "External API - Employees", description = "Read-only access to employee directory with sanitized fields")
@SecurityRequirement(name = "apiKeyAuth")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyAuthority('SCOPE_EMPLOYEE_READ', 'ROLE_ADMIN')")
public class ExternalEmployeeController {

    private final ExternalApiService externalApiService;

    public ExternalEmployeeController(ExternalApiService externalApiService) {
        this.externalApiService = externalApiService;
    }

    @GetMapping
    @Operation(summary = "List Employees", description = "Returns active or all employees with sanitized fields (excluding sensitive credentials)")
    public ResponseEntity<ExternalApiResponse<List<ExternalEmployeeResponse>>> getEmployees(
            @Parameter(description = "Filter by active status (default: true)")
            @RequestParam(name = "activeOnly", defaultValue = "true") boolean activeOnly,
            HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        List<ExternalEmployeeResponse> employees = externalApiService.getEmployees(activeOnly);
        return ResponseEntity.ok(ExternalApiResponse.success(employees, requestId));
    }

    @GetMapping("/{employeeCode}")
    @Operation(summary = "Get Employee by Code", description = "Returns basic details for an employee by code (e.g. EMP001)")
    public ResponseEntity<ExternalApiResponse<ExternalEmployeeResponse>> getEmployeeByCode(
            @Parameter(description = "Unique employee code (e.g. EMP001)")
            @PathVariable("employeeCode") String employeeCode,
            HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        ExternalEmployeeResponse employee = externalApiService.getEmployeeByCode(employeeCode);
        return ResponseEntity.ok(ExternalApiResponse.success(employee, requestId));
    }

    private String resolveRequestId(HttpServletRequest request) {
        String reqId = (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE_REQUEST_ID);
        if (reqId == null) reqId = request.getHeader(CorrelationIdFilter.HEADER_REQUEST_ID);
        return reqId != null ? reqId : "n/a";
    }
}
