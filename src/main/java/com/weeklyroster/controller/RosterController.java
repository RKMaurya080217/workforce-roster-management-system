package com.weeklyroster.controller;

import com.weeklyroster.dto.request.RosterOverrideRequest;
import com.weeklyroster.dto.request.ShiftChangeRequest;
import com.weeklyroster.dto.response.EmailDeliveryLogResponse;
import com.weeklyroster.dto.response.RosterAssignmentResponse;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.dto.response.ShiftExplanationResponse;

import com.weeklyroster.dto.response.TodayDutyResponse;
import com.weeklyroster.entity.GenerationMode;
import com.weeklyroster.entity.RosterCycle;
import com.weeklyroster.service.RosterEmailService;
import com.weeklyroster.service.RosterService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rosters")
public class RosterController {
    private final RosterService rosterService;
    private final RosterEmailService rosterEmailService;
    private final com.weeklyroster.service.RosterSchedulerService rosterSchedulerService;

    @org.springframework.beans.factory.annotation.Autowired
    public RosterController(RosterService rosterService,
                            RosterEmailService rosterEmailService,
                            @org.springframework.beans.factory.annotation.Autowired(required = false) com.weeklyroster.service.RosterSchedulerService rosterSchedulerService) {
        this.rosterService = rosterService;
        this.rosterEmailService = rosterEmailService;
        this.rosterSchedulerService = rosterSchedulerService;
    }

    public RosterController(RosterService rosterService, RosterEmailService rosterEmailService) {
        this(rosterService, rosterEmailService, null);
    }

    @GetMapping(value = "/scheduler/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> schedulerStatus() {
        if (rosterSchedulerService != null) {
            return ResponseEntity.ok(rosterSchedulerService.getSchedulerStatus());
        }
        return ResponseEntity.ok(Map.of("status", "UNAVAILABLE"));
    }

    @GetMapping(value = "/scheduler/preview", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> schedulerPreview() {
        if (rosterSchedulerService != null) {
            return ResponseEntity.ok(rosterSchedulerService.previewUpcomingCycle());
        }
        return ResponseEntity.ok(Map.of("status", "UNAVAILABLE"));
    }

    @PostMapping(value = "/generate", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<RosterCycleResponse> generate(
            @RequestParam(name = "startDate", required = false) String startDate) {
        java.time.LocalDate defaultMonday = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
                .with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY));
        java.time.LocalDate parsedDate = com.weeklyroster.util.DateParser.parse(startDate, defaultMonday);
        return ResponseEntity.ok(rosterService.generateWeeklyRoster(parsedDate, GenerationMode.MANUAL));
    }

    public ResponseEntity<RosterCycleResponse> generate(java.time.LocalDate startDate) {
        return ResponseEntity.ok(rosterService.generateWeeklyRoster(startDate, GenerationMode.MANUAL));
    }

    @GetMapping("/check-existing")
    public ResponseEntity<Map<String, Object>> checkExisting(
            @RequestParam(name = "startDate", required = false) String startDate) {
        java.time.LocalDate defaultMonday = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
                .with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY));
        java.time.LocalDate parsedDate = com.weeklyroster.util.DateParser.parse(startDate, defaultMonday);
        return ResponseEntity.ok(rosterService.checkExistingCycle(parsedDate));
    }

    @GetMapping(value = "/cycle/{id}/export/excel", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> exportExcel(@PathVariable("id") Long id) {
        byte[] bytes = rosterService.exportExcel(id);
        RosterCycleResponse cycle = rosterService.cycle(id);
        String filename = "WRMS_Roster_" + cycle.startDate() + "_to_" + cycle.endDate() + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CONTENT_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .body(bytes);
    }

    @GetMapping(value = "/cycle/{id}/export/image", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> exportImage(@PathVariable("id") Long id) {
        byte[] bytes = rosterService.exportImage(id);
        RosterCycleResponse cycle = rosterService.cycle(id);
        String filename = "WRMS_Roster_" + cycle.startDate() + "_to_" + cycle.endDate() + ".png";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_PNG_VALUE)
                .body(bytes);
    }

    @PostMapping(value = "/cycle/{id}/email", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<List<EmailDeliveryLogResponse>> sendEmail(@PathVariable("id") Long id) {
        RosterCycleResponse cycleResponse = rosterService.cycle(id);
        RosterCycle cycle = new RosterCycle();
        cycle.setId(cycleResponse.id());
        cycle.setStartDate(cycleResponse.startDate());
        cycle.setEndDate(cycleResponse.endDate());
        cycle.setGeneratedAt(cycleResponse.generatedAt());
        cycle.setGenerationMode(cycleResponse.generationMode());

        List<EmailDeliveryLogResponse> logs = rosterEmailService.distributeRosterEmails(cycle, cycleResponse, GenerationMode.MANUAL);
        return ResponseEntity.ok(logs);
    }

    @PostMapping(value = "/cycle/{id}/email/retry", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<List<EmailDeliveryLogResponse>> retryEmail(@PathVariable("id") Long id) {
        return ResponseEntity.ok(rosterEmailService.retryFailedEmails(id));
    }

    @GetMapping(value = "/cycle/{id}/email/logs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<EmailDeliveryLogResponse>> emailLogs(@PathVariable("id") Long id) {
        return ResponseEntity.ok(rosterEmailService.getEmailLogs(id));
    }

    @PostMapping(value = "/email/test", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<Map<String, Object>> testEmail(@RequestParam(name = "to", required = false) String to) {
        return ResponseEntity.ok(rosterEmailService.sendTestEmail(to));
    }

    @GetMapping
    public ResponseEntity<List<RosterCycleResponse>> all() {
        return ResponseEntity.ok(rosterService.allCycles());
    }

    @GetMapping("/cycle/{id}")
    public ResponseEntity<RosterCycleResponse> cycle(@PathVariable("id") Long id) {
        return ResponseEntity.ok(rosterService.cycle(id));
    }

    @GetMapping("/my-duty/today")
    public ResponseEntity<TodayDutyResponse> myTodayDuty() {
        return ResponseEntity.ok(rosterService.getMyTodayDuty());
    }

    @GetMapping("/effective-duty")
    public ResponseEntity<TodayDutyResponse> effectiveDuty(
            @RequestParam("employeeId") Long employeeId,
            @RequestParam(name = "date", required = false) String date) {
        java.time.LocalDate parsedDate = com.weeklyroster.util.DateParser.parse(date, java.time.LocalDate.now());
        return ResponseEntity.ok(rosterService.getTodayEffectiveDuty(employeeId, parsedDate, java.time.LocalDateTime.now()));
    }

    @GetMapping("/employee/{employeeId}")
    public ResponseEntity<List<RosterAssignmentResponse>> employeeRoster(@PathVariable("employeeId") Long employeeId) {
        return ResponseEntity.ok(rosterService.employeeRoster(employeeId));
    }

    @PutMapping("/{id}/shift")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<RosterAssignmentResponse> changeShift(@PathVariable("id") Long id,
                                                                @Valid @RequestBody ShiftChangeRequest request) {
        return ResponseEntity.ok(rosterService.changeShift(id, request));
    }

    @PutMapping("/{id}/off")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<RosterAssignmentResponse> markOff(@PathVariable("id") Long id,
                                                            @Valid @RequestBody ShiftChangeRequest request) {
        return ResponseEntity.ok(rosterService.markWeeklyOff(id, request));
    }

    @PostMapping("/overrides")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<RosterAssignmentResponse> override(@Valid @RequestBody RosterOverrideRequest request) {
        return ResponseEntity.ok(rosterService.override(request));
    }

    @PostMapping("/swap")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<List<RosterAssignmentResponse>> swap(@Valid @RequestBody com.weeklyroster.dto.request.RosterSwapRequest request) {
        return ResponseEntity.ok(rosterService.swapShifts(request.assignmentId1(), request.assignmentId2(), request.reason()));
    }

    @PostMapping("/cycle/{id}/publish")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<RosterCycleResponse> publishCycle(@PathVariable("id") Long id) {
        return ResponseEntity.ok(rosterService.publishRoster(id));
    }

    @PostMapping("/cycle/{id}/lock")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<RosterCycleResponse> lockCycle(@PathVariable("id") Long id) {
        return ResponseEntity.ok(rosterService.lockRoster(id));
    }

    @PostMapping("/cycle/{id}/unlock")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<RosterCycleResponse> unlockCycle(@PathVariable("id") Long id,
                                                           @Valid @RequestBody com.weeklyroster.dto.request.UnlockRosterRequest request) {
        return ResponseEntity.ok(rosterService.unlockRoster(id, request));
    }

    @GetMapping(value = "/assignments/{assignmentId}/explanation", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ShiftExplanationResponse> getShiftExplanation(@PathVariable("assignmentId") Long assignmentId) {
        return ResponseEntity.ok(rosterService.getShiftExplanation(assignmentId));
    }

    @GetMapping(value = "/{cycleId}/assignments/{assignmentId}/explanation", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ShiftExplanationResponse> getCycleShiftExplanation(
            @PathVariable("cycleId") Long cycleId,
            @PathVariable("assignmentId") Long assignmentId) {
        return ResponseEntity.ok(rosterService.getShiftExplanation(cycleId, assignmentId));
    }

    @GetMapping("/cycle/{id}/health")
    public ResponseEntity<com.weeklyroster.dto.response.RosterHealthReport> getCycleHealth(@PathVariable("id") Long id) {
        return ResponseEntity.ok(rosterService.getRosterHealth(id));
    }

    @org.springframework.web.bind.annotation.DeleteMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable("id") Long id) {
        rosterService.deleteCycle(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "Roster cycle deleted successfully"));
    }

    @org.springframework.web.bind.annotation.DeleteMapping(value = "/cycle/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<Map<String, Object>> deleteCycle(@PathVariable("id") Long id) {
        rosterService.deleteCycle(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "Roster cycle deleted successfully"));
    }

    @PostMapping(value = "/email/test-smtp", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<Map<String, Object>> testSmtpEmail(@RequestParam(name = "toEmail", required = false) String toEmail) {
        return ResponseEntity.ok(rosterEmailService.sendTestEmail(toEmail));
    }
}
