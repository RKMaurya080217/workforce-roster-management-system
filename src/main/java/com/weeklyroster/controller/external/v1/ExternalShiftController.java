package com.weeklyroster.controller.external.v1;

import com.weeklyroster.dto.external.v1.ExternalApiResponse;
import com.weeklyroster.dto.external.v1.ExternalShiftResponse;
import com.weeklyroster.security.CorrelationIdFilter;
import com.weeklyroster.service.ExternalApiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/external/v1/shifts")
@Tag(name = "External API - Shifts", description = "Read-only access to configured active shift types and capacities")
@SecurityRequirement(name = "apiKeyAuth")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyAuthority('SCOPE_SHIFT_READ', 'ROLE_ADMIN')")
public class ExternalShiftController {

    private final ExternalApiService externalApiService;

    public ExternalShiftController(ExternalApiService externalApiService) {
        this.externalApiService = externalApiService;
    }

    @GetMapping
    @Operation(summary = "List Active Shifts", description = "Returns active shift definitions (e.g. MORNING, GENERAL, EVENING, NIGHT, OFF) and their capacities")
    public ResponseEntity<ExternalApiResponse<List<ExternalShiftResponse>>> getShifts(HttpServletRequest request) {
        String requestId = resolveRequestId(request);
        List<ExternalShiftResponse> shifts = externalApiService.getActiveShifts();
        return ResponseEntity.ok(ExternalApiResponse.success(shifts, requestId));
    }

    private String resolveRequestId(HttpServletRequest request) {
        String reqId = (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE_REQUEST_ID);
        if (reqId == null) reqId = request.getHeader(CorrelationIdFilter.HEADER_REQUEST_ID);
        return reqId != null ? reqId : "n/a";
    }
}
