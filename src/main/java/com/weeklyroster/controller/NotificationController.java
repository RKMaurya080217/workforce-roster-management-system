package com.weeklyroster.controller;

import com.weeklyroster.dto.response.NotificationResponse;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.User;
import com.weeklyroster.service.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final com.weeklyroster.service.SseEmitterService sseEmitterService;
    private final com.weeklyroster.service.push.NotificationPushService pushService;
    private final com.weeklyroster.repository.UserRepository userRepository;

    @org.springframework.beans.factory.annotation.Autowired
    public NotificationController(NotificationService notificationService,
                                  @org.springframework.beans.factory.annotation.Autowired(required = false) com.weeklyroster.service.SseEmitterService sseEmitterService,
                                  @org.springframework.beans.factory.annotation.Autowired(required = false) com.weeklyroster.service.push.NotificationPushService pushService,
                                  @org.springframework.beans.factory.annotation.Autowired(required = false) com.weeklyroster.repository.UserRepository userRepository) {
        this.notificationService = notificationService;
        this.sseEmitterService = sseEmitterService;
        this.pushService = pushService;
        this.userRepository = userRepository;
    }

    public NotificationController(NotificationService notificationService) {
        this(notificationService, null, null, null);
    }

    private String getAuthenticatedUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new org.springframework.security.access.AccessDeniedException("Authentication required");
        }
        return auth.getName();
    }

    @GetMapping("/my")
    public ResponseEntity<List<NotificationResponse>> getMyNotifications(
            @RequestParam(value = "filter", defaultValue = "ALL", required = false) String filter,
            @RequestParam(value = "limit", defaultValue = "50", required = false) int limit) {
        return ResponseEntity.ok(notificationService.getMyNotificationsFiltered(getAuthenticatedUsername(), filter, limit));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Object>> getUnreadCount() {
        long count = notificationService.getUnreadCount(getAuthenticatedUsername());
        return ResponseEntity.ok(Map.of("unreadCount", count));
    }

    @PutMapping("/{id}/read")
    public ResponseEntity<NotificationResponse> markAsRead(@PathVariable("id") Long id) {
        return ResponseEntity.ok(notificationService.markAsRead(id, getAuthenticatedUsername()));
    }

    @PutMapping("/read-all")
    public ResponseEntity<Map<String, Object>> markAllAsRead() {
        notificationService.markAllAsRead(getAuthenticatedUsername());
        return ResponseEntity.ok(Map.of("success", true, "message", "All notifications marked as read"));
    }

    @GetMapping(value = "/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter streamNotifications() {
        if (sseEmitterService == null) {
            org.springframework.web.servlet.mvc.method.annotation.SseEmitter fallback = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(60000L);
            try {
                fallback.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name("INIT").data(Map.of("status", "DISABLED")));
                fallback.complete();
            } catch (Exception ignored) {}
            return fallback;
        }
        return sseEmitterService.subscribe(getAuthenticatedUsername());
    }

    @PostMapping("/register-token")
    public ResponseEntity<Map<String, Object>> registerToken(@RequestBody Map<String, String> body) {
        if (pushService == null) {
            return ResponseEntity.ok(Map.of("success", false, "message", "Push notification service unavailable"));
        }
        String username = getAuthenticatedUsername();
        if (userRepository == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "User repository unavailable"));
        }
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "User record not found"));
        }
        String token = body != null ? body.get("token") : null;
        String deviceType = body != null
                ? (body.get("deviceType") != null ? body.get("deviceType") : body.get("platform"))
                : "Browser";

        if (token == null || token.trim().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "FCM token is required"));
        }

        Employee emp = user.getEmployee();
        boolean ok = pushService.registerToken(user, emp, token.trim(), deviceType != null ? deviceType : "Browser");
        return ResponseEntity.ok(Map.of(
                "success", ok,
                "message", ok ? "Device token registered successfully" : "Registration failed"
        ));
    }
}
