package com.weeklyroster.controller;

import com.weeklyroster.service.RosterEmailService;
import com.weeklyroster.service.email.EmailService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Admin REST Controller for Transactional Email Provider Diagnostics and Live Test Operations.
 */
@RestController
@RequestMapping("/api/admin/email")
@Tag(name = "Admin Email Management", description = "Endpoints for checking email provider health, Brevo HTTPS status, and live test dispatch")
public class AdminEmailController {

    private final EmailService emailService;
    private final RosterEmailService rosterEmailService;

    @Autowired
    public AdminEmailController(EmailService emailService, RosterEmailService rosterEmailService) {
        this.emailService = emailService;
        this.rosterEmailService = rosterEmailService;
    }

    @GetMapping(value = "/provider-status", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Operation(summary = "Get Email Provider Status", description = "Returns active provider (BREVO / SMTP), configuration status, and fallback availability without exposing secrets")
    public ResponseEntity<Map<String, Object>> getProviderStatus() {
        return ResponseEntity.ok(emailService.getProviderStatus());
    }

    @PostMapping(value = "/test", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Operation(summary = "Send Test Transactional Email", description = "Dispatches a live transactional test email via the active email provider")
    public ResponseEntity<Map<String, Object>> sendTestEmail(@RequestParam(name = "to", required = false) String to) {
        return ResponseEntity.ok(rosterEmailService.sendTestEmail(to));
    }
}
