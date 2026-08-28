package com.weeklyroster.controller;

import com.weeklyroster.dto.response.SystemHealthResponse;
import com.weeklyroster.service.SystemHealthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SystemHealthController {

    private final SystemHealthService systemHealthService;

    @Autowired
    public SystemHealthController(SystemHealthService systemHealthService) {
        this.systemHealthService = systemHealthService;
    }

    @GetMapping("/admin/system-health")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SystemHealthResponse> getAdminSystemHealth() {
        return ResponseEntity.ok(systemHealthService.getSystemHealth());
    }

    @GetMapping("/public/health")
    public ResponseEntity<SystemHealthResponse> getPublicHealth() {
        return ResponseEntity.ok(systemHealthService.getSystemHealth());
    }
}