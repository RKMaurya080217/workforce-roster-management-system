package com.weeklyroster.controller;

import com.weeklyroster.dto.response.EmployeeActivityPageResponse;
import com.weeklyroster.entity.ActivityCategory;
import com.weeklyroster.entity.ActivityStatus;
import com.weeklyroster.service.EmployeeActivityLogService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/activities")
public class EmployeeActivityController {

    private final EmployeeActivityLogService activityLogService;

    public EmployeeActivityController(EmployeeActivityLogService activityLogService) {
        this.activityLogService = activityLogService;
    }

    private String getAuthenticatedUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new org.springframework.security.access.AccessDeniedException("Authentication required");
        }
        return auth.getName();
    }

    @GetMapping("/my")
    public ResponseEntity<EmployeeActivityPageResponse> getMyActivities(
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        String username = getAuthenticatedUsername();
        return ResponseEntity.ok(activityLogService.getMyActivities(username, category, page, size));
    }

    @PostMapping("/view-roster")
    public ResponseEntity<Map<String, Object>> logRosterViewed() {
        String username = getAuthenticatedUsername();
        activityLogService.logUserActivity(
                username,
                ActivityCategory.ROSTER,
                "ROSTER_VIEWED",
                ActivityStatus.SUCCESS,
                "Weekly roster schedule viewed in self-service portal"
        );
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/log-view")
    public ResponseEntity<Map<String, Object>> logViewEvent(@RequestBody Map<String, String> payload) {
        String username = getAuthenticatedUsername();
        String eventType = payload.getOrDefault("eventType", "VIEW");
        String categoryStr = payload.getOrDefault("category", "ACCOUNT");
        String description = payload.getOrDefault("description", "Viewed portal section");

        ActivityCategory category = ActivityCategory.ACCOUNT;
        try {
            category = ActivityCategory.valueOf(categoryStr.toUpperCase());
        } catch (Exception ignored) {}

        activityLogService.logUserActivity(username, category, eventType, ActivityStatus.SUCCESS, description);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
