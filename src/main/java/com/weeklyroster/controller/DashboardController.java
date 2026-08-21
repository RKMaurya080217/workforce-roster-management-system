package com.weeklyroster.controller;

import com.weeklyroster.dto.response.DashboardDayViewResponse;
import com.weeklyroster.dto.response.DashboardDetailResponse;
import com.weeklyroster.dto.response.DashboardEmployeeViewResponse;
import com.weeklyroster.dto.response.DashboardResponse;
import com.weeklyroster.service.DashboardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    public ResponseEntity<DashboardResponse> dashboard() {
        return ResponseEntity.ok(dashboardService.dashboard());
    }

    @GetMapping("/details")
    public ResponseEntity<DashboardDetailResponse> dashboardDetails() {
        return ResponseEntity.ok(dashboardService.dashboardDetails());
    }

    @GetMapping("/day-view")
    public ResponseEntity<DashboardDayViewResponse> dayView(
            @RequestParam(name = "cycleId", required = false) Long cycleId) {
        return ResponseEntity.ok(dashboardService.dayView(cycleId));
    }

    @GetMapping("/employee-view")
    public ResponseEntity<DashboardEmployeeViewResponse> employeeView(
            @RequestParam(name = "cycleId", required = false) Long cycleId) {
        return ResponseEntity.ok(dashboardService.employeeView(cycleId));
    }
}
