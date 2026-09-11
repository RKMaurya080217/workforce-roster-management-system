package com.weeklyroster.controller.external.v1;

import com.weeklyroster.dto.external.v1.ExternalApiResponse;
import com.weeklyroster.security.ApiKeyAuthenticationToken;
import com.weeklyroster.security.CorrelationIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/external/v1/ping")
@Tag(name = "External API - Health & Diagnostics", description = "Endpoints for third-party connectivity verification and token validation")
@SecurityRequirement(name = "apiKeyAuth")
@SecurityRequirement(name = "bearerAuth")
public class ExternalPingController {

    @GetMapping
    @Operation(summary = "Ping & Verify API Key", description = "Verifies external API connectivity, credentials, and returned authorized scopes")
    public ResponseEntity<ExternalApiResponse<Map<String, Object>>> ping(HttpServletRequest request) {
        String requestId = (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE_REQUEST_ID);
        if (requestId == null) requestId = request.getHeader(CorrelationIdFilter.HEADER_REQUEST_ID);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String clientName = auth != null ? auth.getName() : "Anonymous";
        Object scopes = "ALL";
        if (auth instanceof ApiKeyAuthenticationToken token) {
            scopes = token.getScopes();
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("message", "WRMS External API v1 is reachable and credentials are valid");
        data.put("client", clientName);
        data.put("authorizedScopes", scopes);
        data.put("serverTime", LocalDateTime.now());

        return ResponseEntity.ok(ExternalApiResponse.success(data, requestId));
    }
}
