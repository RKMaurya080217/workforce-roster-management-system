package com.weeklyroster.controller;

import com.weeklyroster.dto.response.SmartCommandCenterResponse;
import com.weeklyroster.service.SmartCommandCenterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/command-center")
@PreAuthorize("hasRole('ADMIN')")
public class SmartCommandCenterController {

    private final SmartCommandCenterService commandCenterService;

    @Autowired
    public SmartCommandCenterController(SmartCommandCenterService commandCenterService) {
        this.commandCenterService = commandCenterService;
    }

    @GetMapping(value = "/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SmartCommandCenterResponse> getActiveCycleSummary() {
        return ResponseEntity.ok(commandCenterService.getActiveCycleSummary());
    }

    @GetMapping(value = "/cycle/{cycleId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SmartCommandCenterResponse> getCycleSummary(@PathVariable("cycleId") Long cycleId) {
        return ResponseEntity.ok(commandCenterService.getCycleSummary(cycleId));
    }

    @org.springframework.web.bind.annotation.PostMapping(value = "/generate-upcoming", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SmartCommandCenterResponse> generateUpcomingRoster() {
        return ResponseEntity.ok(commandCenterService.generateUpcomingRoster());
    }

    @org.springframework.web.bind.annotation.PostMapping(value = "/cycle/{cycleId}/publish", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SmartCommandCenterResponse> publishCycle(@PathVariable("cycleId") Long cycleId) {
        return ResponseEntity.ok(commandCenterService.publishCycle(cycleId));
    }

    @org.springframework.web.bind.annotation.PostMapping(value = "/cycle/{cycleId}/lock", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SmartCommandCenterResponse> lockCycle(@PathVariable("cycleId") Long cycleId) {
        return ResponseEntity.ok(commandCenterService.lockCycle(cycleId));
    }
}
