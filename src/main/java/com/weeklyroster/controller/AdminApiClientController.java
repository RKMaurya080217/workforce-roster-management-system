package com.weeklyroster.controller;

import com.weeklyroster.dto.external.v1.ExternalClientCreateRequest;
import com.weeklyroster.dto.external.v1.ExternalClientResponse;
import com.weeklyroster.service.ApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/external-clients")
@Tag(name = "Admin - External API Clients", description = "Management of third-party API credentials, scopes, and rate limits")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
public class AdminApiClientController {

    private final ApiKeyService apiKeyService;

    public AdminApiClientController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @GetMapping
    @Operation(summary = "List Registered API Clients", description = "Lists all registered external clients with masked keys and usage metadata")
    public ResponseEntity<List<ExternalClientResponse>> listClients() {
        return ResponseEntity.ok(apiKeyService.listClients());
    }

    @PostMapping
    @Operation(summary = "Create API Client & Key", description = "Creates a new API client and returns the unmasked API key exactly once")
    public ResponseEntity<ExternalClientResponse> createClient(@Valid @RequestBody ExternalClientCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(apiKeyService.createClient(request));
    }

    @PutMapping("/{id}/toggle")
    @Operation(summary = "Toggle Client Active Status", description = "Enables or disables an existing client's API access")
    public ResponseEntity<ExternalClientResponse> toggleStatus(@PathVariable("id") Long id) {
        return ResponseEntity.ok(apiKeyService.toggleStatus(id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Revoke / Delete Client", description = "Permanently deletes an external API client credential")
    public ResponseEntity<Void> deleteClient(@PathVariable("id") Long id) {
        apiKeyService.deleteClient(id);
        return ResponseEntity.noContent().build();
    }
}
