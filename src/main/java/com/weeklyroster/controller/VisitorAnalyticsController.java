package com.weeklyroster.controller;

import com.weeklyroster.dto.VisitorHeartbeatRequest;
import com.weeklyroster.dto.VisitorStatsResponse;
import com.weeklyroster.service.VisitorAnalyticsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/visitor-stats")
public class VisitorAnalyticsController {

    private final VisitorAnalyticsService visitorAnalyticsService;

    public VisitorAnalyticsController(VisitorAnalyticsService visitorAnalyticsService) {
        this.visitorAnalyticsService = visitorAnalyticsService;
    }

    @GetMapping
    public ResponseEntity<VisitorStatsResponse> getStats() {
        VisitorStatsResponse response = visitorAnalyticsService.getStats();
        return ResponseEntity.ok(response);
    }

    @PostMapping("/visit")
    public ResponseEntity<VisitorStatsResponse> recordVisit(@RequestBody(required = false) VisitorHeartbeatRequest request,
                                                            @RequestParam(value = "visitorId", required = false) String paramVisitorId) {
        String vid = (request != null && request.getVisitorId() != null) ? request.getVisitorId() : paramVisitorId;
        VisitorStatsResponse response = visitorAnalyticsService.recordVisit(vid);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<VisitorStatsResponse> recordHeartbeat(@RequestBody(required = false) VisitorHeartbeatRequest request,
                                                                @RequestParam(value = "visitorId", required = false) String paramVisitorId) {
        String vid = (request != null && request.getVisitorId() != null) ? request.getVisitorId() : paramVisitorId;
        VisitorStatsResponse response = visitorAnalyticsService.recordHeartbeat(vid);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/offline")
    public ResponseEntity<VisitorStatsResponse> recordOffline(@RequestBody(required = false) VisitorHeartbeatRequest request,
                                                              @RequestParam(value = "visitorId", required = false) String paramVisitorId) {
        String vid = (request != null && request.getVisitorId() != null) ? request.getVisitorId() : paramVisitorId;
        VisitorStatsResponse response = visitorAnalyticsService.recordOffline(vid);
        return ResponseEntity.ok(response);
    }
}