package com.weeklyroster.controller;

import com.weeklyroster.dto.request.AdminChangeUsernameRequest;
import com.weeklyroster.dto.request.AdminEmergencyRecoveryRequest;
import com.weeklyroster.dto.request.AdminRecoveryRequest;
import com.weeklyroster.dto.request.AdminRecoveryVerifyRequest;
import com.weeklyroster.dto.request.ChangePasswordRequest;
import com.weeklyroster.dto.response.AdminRecoveryResponse;
import com.weeklyroster.service.AdminRecoveryService;
import com.weeklyroster.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller for Admin Account Recovery, Emergency Reset, and Administrative Account Settings.
 */
@RestController
@Tag(name = "Admin Account Recovery & Settings", description = "Endpoints for administrator forgot password, emergency key recovery, and credential updates")
public class AdminRecoveryController {

    private final AdminRecoveryService recoveryService;
    private final AuthService authService;

    @Autowired
    public AdminRecoveryController(AdminRecoveryService recoveryService, AuthService authService) {
        this.recoveryService = recoveryService;
        this.authService = authService;
    }

    // =========================================================================
    // 1. PUBLIC ADMIN RECOVERY ENDPOINTS
    // =========================================================================

    @PostMapping("/api/auth/admin/recovery/request")
    @Operation(summary = "Request single-use OTP code for administrator password recovery")
    public ResponseEntity<AdminRecoveryResponse> requestRecoveryOtp(@Valid @RequestBody AdminRecoveryRequest req) {
        return ResponseEntity.ok(recoveryService.requestRecoveryOtp(req));
    }

    @PostMapping("/api/auth/admin/recovery/verify-reset")
    @Operation(summary = "Verify OTP code and reset administrator password")
    public ResponseEntity<AdminRecoveryResponse> verifyAndResetPassword(@Valid @RequestBody AdminRecoveryVerifyRequest req) {
        return ResponseEntity.ok(recoveryService.verifyAndResetPassword(req));
    }

    @PostMapping("/api/auth/admin/recovery/emergency")
    @Operation(summary = "Emergency administrator password reset using ADMIN_RECOVERY_KEY")
    public ResponseEntity<AdminRecoveryResponse> emergencyResetPassword(@Valid @RequestBody AdminEmergencyRecoveryRequest req) {
        return ResponseEntity.ok(recoveryService.emergencyRecover(req));
    }

    // =========================================================================
    // 2. AUTHENTICATED ADMIN ACCOUNT SETTINGS
    // =========================================================================

    @PostMapping("/api/admin/account/change-username")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Operation(summary = "Change administrator Login ID / Username")
    public ResponseEntity<AdminRecoveryResponse> changeAdminUsername(
            @Valid @RequestBody AdminChangeUsernameRequest req,
            Authentication auth) {
        String actor = auth != null ? auth.getName() : "";
        return ResponseEntity.ok(recoveryService.changeAdminUsername(actor, req));
    }

    @PostMapping("/api/admin/account/change-password")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Operation(summary = "Change administrator account password")
    public ResponseEntity<Map<String, Object>> changeAdminPassword(
            @Valid @RequestBody ChangePasswordRequest req,
            Authentication auth) {
        Map<String, Object> result = authService.changePassword(req);
        String actor = auth != null ? auth.getName() : "admin";
        recoveryService.logPasswordChanged(actor);
        return ResponseEntity.ok(result);
    }
}
