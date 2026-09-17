package com.weeklyroster.controller;

import com.weeklyroster.config.FirebaseConfig;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.User;
import com.weeklyroster.repository.UserRepository;
import com.weeklyroster.service.push.NotificationPushService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications/fcm")
public class PushNotificationController {

    private final FirebaseConfig firebaseConfig;
    private final NotificationPushService notificationPushService;
    private final UserRepository userRepository;

    @Autowired
    public PushNotificationController(FirebaseConfig firebaseConfig,
                                      NotificationPushService notificationPushService,
                                      UserRepository userRepository) {
        this.firebaseConfig = firebaseConfig;
        this.notificationPushService = notificationPushService;
        this.userRepository = userRepository;
    }

    private User getAuthenticatedUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new org.springframework.security.access.AccessDeniedException("Authentication required");
        }
        return userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("User record not found"));
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getPublicConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        boolean enabled = firebaseConfig.isEnabled();
        boolean isWebReady = firebaseConfig.isWebConfigured();
        config.put("enabled", enabled && isWebReady);
        config.put("configured", isWebReady);
        if (enabled && isWebReady) {
            config.put("apiKey", firebaseConfig.getWebApiKey());
            config.put("projectId", firebaseConfig.getProjectId());
            config.put("messagingSenderId", firebaseConfig.getMessagingSenderId());
            config.put("appId", firebaseConfig.getAppId());
            config.put("vapidKey", firebaseConfig.getVapidKey());
        } else {
            config.put("reason", "FCM web public credentials not configured");
        }
        return ResponseEntity.ok(config);
    }

    @PostMapping("/register-token")
    public ResponseEntity<Map<String, Object>> registerToken(@RequestBody Map<String, String> body) {
        User user = getAuthenticatedUser();
        String token = body != null ? body.get("token") : null;
        String deviceType = body != null
                ? (body.get("deviceType") != null ? body.get("deviceType") : body.get("platform"))
                : "Browser";

        if (token == null || token.trim().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "FCM token is required"));
        }

        Employee emp = user.getEmployee();
        boolean ok = notificationPushService.registerToken(user, emp, token.trim(), deviceType != null ? deviceType : "Browser");

        return ResponseEntity.ok(Map.of(
                "success", ok,
                "message", ok ? "Device token registered successfully" : "Registration failed"
        ));
    }

    @PostMapping("/unregister-token")
    public ResponseEntity<Map<String, Object>> unregisterToken(@RequestBody Map<String, String> body) {
        User user = getAuthenticatedUser();
        String token = body != null ? body.get("token") : null;

        if (token == null || token.trim().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "FCM token is required"));
        }

        boolean ok = notificationPushService.deactivateToken(token.trim(), user);
        return ResponseEntity.ok(Map.of(
                "success", ok,
                "message", ok ? "Device token deactivated" : "Token not found or already inactive"
        ));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        User user = getAuthenticatedUser();
        long tokenCount = notificationPushService.getActiveTokenCountForUser(user);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("registered", tokenCount > 0);
        res.put("activeDeviceCount", tokenCount);
        res.put("pushConfigured", notificationPushService.isConfigured());
        res.put("webConfigured", firebaseConfig.isWebConfigured());
        return ResponseEntity.ok(res);
    }

    @GetMapping("/diagnostics")
    public ResponseEntity<Map<String, Object>> getDiagnostics() {
        User user = getAuthenticatedUser();
        return ResponseEntity.ok(notificationPushService.getPushDiagnostics(user));
    }

    @PostMapping("/test")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<Map<String, Object>> sendAdminTest(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestParam(value = "employeeId", required = false) Long employeeIdParam) {
        User adminUser = getAuthenticatedUser();
        Long targetEmployeeId = employeeIdParam;
        if (targetEmployeeId == null && body != null && body.get("employeeId") != null) {
            try {
                targetEmployeeId = Long.valueOf(body.get("employeeId").toString());
            } catch (Exception ignored) {}
        }

        boolean success = notificationPushService.sendAdminTestNotification(adminUser, targetEmployeeId);
        long deviceCount = notificationPushService.getActiveTokenCountForUser(adminUser);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", success);
        res.put("activeDeviceCount", deviceCount);
        res.put("message", success
                ? "WRMS test notification \u2014 your mobile push notification is working."
                : "Failed to deliver push notification or no active registered devices found. Check server logs.");

        return ResponseEntity.ok(res);
    }
}
