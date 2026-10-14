package com.weeklyroster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weeklyroster.dto.request.AdminChangeUsernameRequest;
import com.weeklyroster.dto.request.AdminEmergencyRecoveryRequest;
import com.weeklyroster.dto.request.AdminRecoveryRequest;
import com.weeklyroster.dto.request.ChangePasswordRequest;
import com.weeklyroster.entity.AuditAction;
import com.weeklyroster.entity.AuditLog;
import com.weeklyroster.entity.User;
import com.weeklyroster.repository.AuditLogRepository;
import com.weeklyroster.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Batch87AdminRecoveryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private static final String DEFAULT_EMERGENCY_KEY = "WRMS-EMERGENCY-ADMIN-RECOVERY-KEY-2026";

    @org.junit.jupiter.api.BeforeEach
    void setupAdmin() {
        List<User> admins = userRepository.findByRole(com.weeklyroster.entity.Role.ROLE_ADMIN);
        if (!admins.isEmpty()) {
            User admin = admins.get(0);
            if (!"admin".equals(admin.getUsername())) {
                admin.setUsername("admin");
                userRepository.save(admin);
            }
        }
    }

    @Test
    @Order(1)
    @DisplayName("Admin Recovery: Requesting recovery OTP generates OTP and enforces cooldown on second request")
    void testRequestPasswordRecovery_SuccessAndCooldown() throws Exception {
        // First request: succeeds
        mockMvc.perform(post("/api/auth/admin/recovery/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AdminRecoveryRequest("admin@weeklyroster.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // Second request immediately: rate limited (cooldown) -> 400 Bad Request
        mockMvc.perform(post("/api/auth/admin/recovery/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AdminRecoveryRequest("admin@weeklyroster.com"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("seconds before requesting another code")));
    }

    @Test
    @Order(2)
    @DisplayName("Admin Recovery: Requesting recovery for non-existent admin is enumeration-safe")
    void testRequestPasswordRecovery_UnknownEmail() throws Exception {
        mockMvc.perform(post("/api/auth/admin/recovery/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AdminRecoveryRequest("unknown_random_admin_99999@weeklyroster.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @Order(3)
    @DisplayName("Admin Recovery: Emergency recovery key with invalid key fails")
    void testEmergencyReset_InvalidKey() throws Exception {
        mockMvc.perform(post("/api/auth/admin/recovery/emergency")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AdminEmergencyRecoveryRequest(
                                "INCORRECT-RECOVERY-KEY-XYZ", "admin", "NewPass@123", "NewPass@123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Invalid emergency recovery key")));
    }

    @Test
    @Order(4)
    @DisplayName("Admin Recovery: Emergency recovery key succeeds and updates password")
    void testEmergencyReset_Success() throws Exception {
        mockMvc.perform(post("/api/auth/admin/recovery/emergency")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AdminEmergencyRecoveryRequest(
                                DEFAULT_EMERGENCY_KEY, "admin", "AdminEmergency@999", "AdminEmergency@999"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        User admin = userRepository.findByUsernameIgnoreCase("admin").orElseThrow();
        assertTrue(passwordEncoder.matches("AdminEmergency@999", admin.getPassword()));
    }

    @Test
    @Order(5)
    @DisplayName("Admin Settings: Authenticated admin can change password")
    void testChangePassword_Success() throws Exception {
        // Successful password change
        mockMvc.perform(post("/api/admin/account/change-password")
                        .with(user("admin").authorities(() -> "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangePasswordRequest(
                                "AdminEmergency@999", "AdminReset@888", "AdminReset@888"))))
                .andExpect(status().isOk());

        User admin = userRepository.findByUsernameIgnoreCase("admin").orElseThrow();
        assertTrue(passwordEncoder.matches("AdminReset@888", admin.getPassword()));

        // Invalid current password should return 400 Bad Request
        mockMvc.perform(post("/api/admin/account/change-password")
                        .with(user("admin").authorities(() -> "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangePasswordRequest(
                                "WrongOldPassword", "NextPass@111", "NextPass@111"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(6)
    @DisplayName("Admin Settings: Authenticated admin can change username and revert back")
    void testChangeUsername_SuccessAndRevert() throws Exception {
        // Change username: admin -> admin_lead
        mockMvc.perform(post("/api/admin/account/change-username")
                        .with(user("admin").authorities(() -> "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AdminChangeUsernameRequest(
                                "AdminReset@888", "admin_lead"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertTrue(userRepository.findByUsernameIgnoreCase("admin_lead").isPresent());

        // Revert username back to admin
        mockMvc.perform(post("/api/admin/account/change-username")
                        .with(user("admin_lead").authorities(() -> "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AdminChangeUsernameRequest(
                                "AdminReset@888", "admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertTrue(userRepository.findByUsernameIgnoreCase("admin").isPresent());

        // Revert password back to Admin@123
        mockMvc.perform(post("/api/admin/account/change-password")
                        .with(user("admin").authorities(() -> "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangePasswordRequest(
                                "AdminReset@888", "Admin@123", "Admin@123"))))
                .andExpect(status().isOk());

        User cleanAdmin = userRepository.findByUsernameIgnoreCase("admin").orElseThrow();
        assertTrue(passwordEncoder.matches("Admin@123", cleanAdmin.getPassword()));
    }

    @Test
    @Order(7)
    @DisplayName("System Health: Endpoint returns accurate diagnostics and enforces role authorization")
    void testSystemHealthDiagnostics() throws Exception {
        // Admin gets full system health telemetry
        mockMvc.perform(get("/api/admin/system-health")
                        .with(user("admin").authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.databaseType").value(org.hamcrest.Matchers.containsString("SQLite")))
                .andExpect(jsonPath("$.serverTimezone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.overallStatus").value("HEALTHY"));

        // Employee is forbidden from admin system health
        mockMvc.perform(get("/api/admin/system-health")
                        .with(user("emp001").authorities(() -> "ROLE_EMPLOYEE")))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(8)
    @DisplayName("Audit Log: Administrative security actions are authoritatively recorded")
    void testAuditTrailRecorded() {
        List<AuditLog> logs = auditLogRepository.findAll();
        assertFalse(logs.isEmpty());

        boolean hasRecoveryRequested = logs.stream()
                .anyMatch(l -> l.getAction() == AuditAction.ADMIN_RECOVERY_REQUESTED);
        boolean hasEmergencyRecovered = logs.stream()
                .anyMatch(l -> l.getAction() == AuditAction.ADMIN_EMERGENCY_RECOVERED);
        boolean hasPasswordChanged = logs.stream()
                .anyMatch(l -> l.getAction() == AuditAction.ADMIN_PASSWORD_CHANGED);
        boolean hasUsernameChanged = logs.stream()
                .anyMatch(l -> l.getAction() == AuditAction.ADMIN_USERNAME_CHANGED);

        assertTrue(hasRecoveryRequested, "Audit log should contain ADMIN_RECOVERY_REQUESTED");
        assertTrue(hasEmergencyRecovered, "Audit log should contain ADMIN_EMERGENCY_RECOVERED");
        assertTrue(hasPasswordChanged, "Audit log should contain ADMIN_PASSWORD_CHANGED");
        assertTrue(hasUsernameChanged, "Audit log should contain ADMIN_USERNAME_CHANGED");
    }
}
