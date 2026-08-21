package com.weeklyroster.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.weeklyroster.dto.request.ChangePasswordRequest;
import com.weeklyroster.dto.request.LoginRequest;
import com.weeklyroster.dto.response.AuthResponse;
import com.weeklyroster.dto.response.UserProfileResponse;
import com.weeklyroster.entity.ActivityCategory;
import com.weeklyroster.entity.ActivityStatus;
import com.weeklyroster.service.AuthService;
import com.weeklyroster.service.EmployeeActivityLogService;

import jakarta.validation.Valid;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final EmployeeActivityLogService activityLogService;

    public AuthController(AuthService authService, EmployeeActivityLogService activityLogService) {
        this.authService = authService;
        this.activityLogService = activityLogService;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        return ResponseEntity.ok(authService.changePassword(request));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !auth.getName().equals("anonymousUser")) {
            activityLogService.logUserActivity(
                    auth.getName(),
                    ActivityCategory.ACCOUNT,
                    "LOGOUT",
                    ActivityStatus.SUCCESS,
                    "User signed out"
            );
        }
        return ResponseEntity.ok(Map.of("success", true, "message", "Signed out successfully"));
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> me() {
        return ResponseEntity.ok(authService.currentProfile());
    }
}
