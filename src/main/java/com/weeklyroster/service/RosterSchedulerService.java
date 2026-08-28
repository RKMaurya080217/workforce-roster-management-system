package com.weeklyroster.service;

import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.dto.response.RosterHealthReport;
import com.weeklyroster.entity.GenerationMode;
import com.weeklyroster.entity.NotificationType;
import com.weeklyroster.entity.RosterCycle;
import com.weeklyroster.entity.RosterStatus;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.RosterCycleRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class RosterSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(RosterSchedulerService.class);

    private final RosterService rosterService;
    private final RosterEmailService rosterEmailService;
    private final RosterCycleRepository cycleRepository;
    private final RosterHealthService rosterHealthService;
    private final NotificationService notificationService;

    @Value("${roster.auto-generation.enabled:true}")
    private boolean autoGenerationEnabled = true;

    @Value("${roster.auto-email.enabled:true}")
    private boolean autoEmailEnabled = true;

    @Value("${roster.auto-generation.timezone:Asia/Kolkata}")
    private String autoGenerationTimezone = "Asia/Kolkata";

    private volatile boolean isShuttingDown = false;
    private volatile LocalDateTime lastRunAt;
    private volatile String lastResult = "INITIALIZED";
    private volatile String lastAutomaticCycle = "NONE";

    @jakarta.annotation.PreDestroy
    public void onShutdown() {
        this.isShuttingDown = true;
        this.lastResult = "STOPPED";
        log.info("[WRMS Scheduler] System shutting down. Stopping all scheduled roster generation jobs.");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RosterSchedulerService(RosterService rosterService,
                                  RosterEmailService rosterEmailService,
                                  RosterCycleRepository cycleRepository,
                                  RosterHealthService rosterHealthService,
                                  NotificationService notificationService) {
        this(rosterService, rosterEmailService, cycleRepository, rosterHealthService, notificationService, true, true, "Asia/Kolkata");
    }

    public RosterSchedulerService(RosterService rosterService,
                                  RosterEmailService rosterEmailService,
                                  RosterCycleRepository cycleRepository) {
        this(rosterService, rosterEmailService, cycleRepository, null, null, true, true, "Asia/Kolkata");
    }

    public RosterSchedulerService(RosterService rosterService,
                                  RosterEmailService rosterEmailService,
                                  RosterCycleRepository cycleRepository,
                                  RosterHealthService rosterHealthService,
                                  NotificationService notificationService,
                                  boolean autoGenerationEnabled,
                                  boolean autoEmailEnabled) {
        this(rosterService, rosterEmailService, cycleRepository, rosterHealthService, notificationService, autoGenerationEnabled, autoEmailEnabled, "Asia/Kolkata");
    }

    public RosterSchedulerService(RosterService rosterService,
                                  RosterEmailService rosterEmailService,
                                  RosterCycleRepository cycleRepository,
                                  RosterHealthService rosterHealthService,
                                  NotificationService notificationService,
                                  boolean autoGenerationEnabled,
                                  boolean autoEmailEnabled,
                                  String autoGenerationTimezone) {
        this.rosterService = rosterService;
        this.rosterEmailService = rosterEmailService;
        this.cycleRepository = cycleRepository;
        this.rosterHealthService = rosterHealthService;
        this.notificationService = notificationService;
        this.autoGenerationEnabled = autoGenerationEnabled;
        this.autoEmailEnabled = autoEmailEnabled;
        this.autoGenerationTimezone = autoGenerationTimezone != null ? autoGenerationTimezone : "Asia/Kolkata";
    }

    public RosterSchedulerService(RosterService rosterService,
                                  RosterEmailService rosterEmailService,
                                  RosterCycleRepository cycleRepository,
                                  boolean autoGenerationEnabled,
                                  boolean autoEmailEnabled) {
        this(rosterService, rosterEmailService, cycleRepository, null, null, autoGenerationEnabled, autoEmailEnabled, "Asia/Kolkata");
    }

    /**
     * Scheduled automatic Sunday weekly roster generator.
     * Generates ONLY the immediately upcoming Monday to Sunday cycle.
     * Cron expression defaults to every Sunday at 09:00 AM Asia/Kolkata timezone.
     */
    /**
     * Scheduled automatic Sunday 4:00 PM (16:00 IST) weekly roster finalization.
     * Closes the review window, incorporates all approved requests, re-optimizes, locks the roster, and sends FINAL emails.
     */
    @Scheduled(cron = "${roster.auto-finalization.cron:0 0 16 * * SUN}", zone = "${roster.auto-generation.timezone:Asia/Kolkata}")
    public void runScheduledSundayFinalization() {
        if (isShuttingDown) {
            log.warn("[WRMS Scheduler] Finalization aborted: System shutdown in progress.");
            return;
        }

        if (!autoGenerationEnabled) {
            log.info("[WRMS Scheduler] Automatic finalization skipped: Auto-generation is disabled.");
            return;
        }

        LocalDate today = LocalDate.now(java.time.ZoneId.of(autoGenerationTimezone != null ? autoGenerationTimezone : "Asia/Kolkata"));
        LocalDate upcomingMonday = calculateUpcomingWeekStart(today);
        executeAutoFinalization(upcomingMonday);
    }

    public RosterCycleResponse executeAutoFinalization(LocalDate targetMonday) {
        this.lastRunAt = LocalDateTime.now();
        java.time.ZoneId istZone = java.time.ZoneId.of(autoGenerationTimezone != null ? autoGenerationTimezone : "Asia/Kolkata");
        LocalDate today = LocalDate.now(istZone);
        LocalDate upcomingMonday = calculateUpcomingWeekStart(today);

        final LocalDate finalTargetMonday = (targetMonday != null) ? targetMonday : upcomingMonday;
        final LocalDate upcomingEnd = finalTargetMonday.plusDays(6);
        log.info("[WRMS Scheduler] Starting Sunday 4:00 PM Finalization for {} to {}...", finalTargetMonday, upcomingEnd);

        List<RosterCycle> existingCycles = cycleRepository.findByStartDateAndEndDate(finalTargetMonday, upcomingEnd)
                .map(List::of)
                .orElseGet(() -> cycleRepository.findOverlappingCycles(finalTargetMonday, upcomingEnd));

        if (existingCycles.isEmpty()) {
            log.warn("[WRMS Scheduler] Operational Warning: No tentative roster cycle found for finalization ({} to {}).", finalTargetMonday, upcomingEnd);
            if (notificationService != null) {
                notificationService.notifyAdmins(
                        "Finalization Warning: Missing Roster",
                        "Sunday 4:00 PM Finalization ran but no roster cycle was found for " + finalTargetMonday + " to " + upcomingEnd + ". Please generate manually.",
                        NotificationType.ADMIN_ALERT, "roster", null);
            }
            return null;
        }

        RosterCycle cycle = existingCycles.get(0);
        if (cycle.getStatus() == RosterStatus.FINAL || cycle.getStatus() == RosterStatus.LOCKED) {
            log.info("[WRMS Scheduler] Cycle #{} is already FINAL/LOCKED. Finalization skipped (idempotent).", cycle.getId());
            return rosterService.cycle(cycle.getId());
        }

        try {
            // 1. Re-optimize the roster incorporating all approved requests
            RosterCycleResponse reoptimized = rosterService.reoptimizeCycle(cycle.getId(), "Sunday 4:00 PM Automatic Finalization");

            // 2. Lock the cycle and mark as FINAL
            cycle.setStatus(RosterStatus.FINAL);
            cycle.setLockedAt(LocalDateTime.now());
            cycle.setLockedBy("SYSTEM");
            cycle.setPublishedAt(LocalDateTime.now());
            cycle.setPublishedBy("SYSTEM");
            cycleRepository.save(cycle);

            log.info("[WRMS Scheduler] Cycle #{} successfully finalized and marked FINAL / LOCKED at 4:00 PM IST.", cycle.getId());

            // 3. Notify employees
            if (notificationService != null) {
                notificationService.notifyAllActiveEmployees(
                        "Weekly Roster Finalized & Locked",
                        "Your weekly roster for " + cycle.getStartDate() + " to " + cycle.getEndDate() + " has been finalized and locked. Normal change requests are now closed.",
                        NotificationType.ROSTER_PUBLISHED, "roster", cycle.getId());
            }

            // 4. Send final emails
            if (autoEmailEnabled) {
                rosterEmailService.distributeFinalRosterEmails(cycle, reoptimized, GenerationMode.AUTOMATIC);
            }

            return rosterService.cycle(cycle.getId());
        } catch (Exception e) {
            log.error("[WRMS Scheduler] Failed to finalize cycle #{}: {}", cycle.getId(), e.getMessage(), e);
            throw new BusinessException("Failed to finalize weekly roster: " + e.getMessage());
        }
    }

    /**
     * Scheduled automatic Sunday weekly roster generator.
     * Generates ONLY the immediately upcoming Monday to Sunday cycle.
     * Cron expression defaults to every Sunday at 09:00 AM Asia/Kolkata timezone.
     */
    @Scheduled(cron = "${roster.auto-generation.cron:0 0 9 * * SUN}", zone = "${roster.auto-generation.timezone:Asia/Kolkata}")
    public void runScheduledSundayGeneration() {

        if (isShuttingDown) {
            log.warn("[WRMS Scheduler] Automatic generation aborted: System shutdown in progress.");
            return;
        }

        if (!autoGenerationEnabled) {
            log.info("[WRMS Scheduler] Automatic Sunday roster generation is disabled by configuration (roster.auto-generation.enabled=false).");
            return;
        }

        LocalDate today = LocalDate.now(java.time.ZoneId.of(autoGenerationTimezone != null ? autoGenerationTimezone : "Asia/Kolkata"));
        LocalDate upcomingMonday = calculateUpcomingWeekStart(today);
        executeAutoGeneration(upcomingMonday);
    }

    /**
     * Backward-compatible alias for scheduled weekly roster generation.
     */
    public void runScheduledMondayGeneration() {
        runScheduledSundayGeneration();
    }

    /**
     * Calculates the Monday of the current week for a given base date.
     * Monday to Sunday are in the same current week.
     */
    public LocalDate calculateCurrentWeekStart(LocalDate baseDate) {
        if (baseDate == null) {
            baseDate = LocalDate.now(java.time.ZoneId.of(autoGenerationTimezone != null ? autoGenerationTimezone : "Asia/Kolkata"));
        }
        return baseDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /**
     * Calculates the Sunday of the current week for a given base date.
     */
    public LocalDate calculateCurrentWeekEnd(LocalDate baseDate) {
        return calculateCurrentWeekStart(baseDate).plusDays(6);
    }

    /**
     * Calculates the Monday of the immediate upcoming week for a given base date.
     * Formula: currentWeekStart + 7 days
     */
    public LocalDate calculateUpcomingWeekStart(LocalDate baseDate) {
        return calculateCurrentWeekStart(baseDate).plusDays(7);
    }

    /**
     * Calculates the Sunday of the immediate upcoming week for a given base date.
     * Formula: upcomingWeekStart + 6 days
     */
    public LocalDate calculateUpcomingWeekEnd(LocalDate baseDate) {
        return calculateUpcomingWeekStart(baseDate).plusDays(6);
    }

    /**
     * Target Monday for automatic generation: Always the immediately upcoming Monday.
     */
    public LocalDate calculateTargetMonday(LocalDate baseDate) {
        return calculateUpcomingWeekStart(baseDate);
    }

    /**
     * Centralized Future Roster Guard / Safety Validation.
     * Validates that automatic generation only targets the immediately upcoming Monday.
     * Rejects:
     * - Current week
     * - Next-next week or beyond
     * - Past weeks
     * - Multiple future cycles or arbitrary future dates
     */
    public boolean isAutomaticGenerationAllowed(LocalDate targetMonday, LocalDate baseDate) {
        if (targetMonday == null) return false;
        LocalDate immediateUpcoming = calculateUpcomingWeekStart(baseDate);
        return targetMonday.equals(immediateUpcoming);
    }

    /**
     * Global automation guard for both generation and email distribution.
     * Verifies if a given date range is strictly the immediate upcoming Monday to Sunday cycle.
     */
        /**
     * Checks if the employee review window is currently open for the given cycle start date.
     * The review window is open from Sunday morning until Sunday 4:00 PM (16:00) IST prior to the Monday cycle start.
     */
    public boolean isReviewWindowOpen(LocalDate cycleStartDate, LocalDateTime checkTime) {
        if (cycleStartDate == null) return false;
        java.time.ZoneId istZone = java.time.ZoneId.of(autoGenerationTimezone != null ? autoGenerationTimezone : "Asia/Kolkata");
        if (checkTime == null) {
            checkTime = LocalDateTime.now(istZone);
        }

        LocalDate sundayBefore = cycleStartDate.minusDays(1);
        LocalDateTime reviewDeadline = LocalDateTime.of(sundayBefore, java.time.LocalTime.of(16, 0, 0));

        // Check if cycle is already FINAL or LOCKED
        List<RosterCycle> cycles = cycleRepository.findByStartDateAndEndDate(cycleStartDate, cycleStartDate.plusDays(6)).map(List::of).orElseGet(List::of);
        if (!cycles.isEmpty()) {
            RosterStatus status = cycles.get(0).getStatus();
            if (status == RosterStatus.FINAL || status == RosterStatus.LOCKED) {
                return false;
            }
        }

        return !checkTime.isAfter(reviewDeadline);
    }

    public LocalDateTime calculateReviewDeadline(LocalDate cycleStartDate) {
        if (cycleStartDate == null) return null;
        LocalDate sundayBefore = cycleStartDate.minusDays(1);
        return LocalDateTime.of(sundayBefore, java.time.LocalTime.of(16, 0, 0));
    }

    public boolean isImmediateUpcomingWeek(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) return false;
        LocalDate today = LocalDate.now(java.time.ZoneId.of(autoGenerationTimezone != null ? autoGenerationTimezone : "Asia/Kolkata"));
        LocalDate upcomingStart = calculateUpcomingWeekStart(today);
        LocalDate upcomingEnd = calculateUpcomingWeekEnd(today);
        return startDate.equals(upcomingStart) && endDate.equals(upcomingEnd);
    }

    /**
     * Diagnostic and health status for Admin visibility.
     */
    public java.util.Map<String, Object> getSchedulerStatus() {
        java.time.ZoneId istZone = java.time.ZoneId.of(autoGenerationTimezone != null ? autoGenerationTimezone : "Asia/Kolkata");
        java.time.ZonedDateTime nowIst = java.time.ZonedDateTime.now(istZone);
        java.time.ZonedDateTime nowUtc = nowIst.withZoneSameInstant(java.time.ZoneId.of("UTC"));
        LocalDate today = nowIst.toLocalDate();

        LocalDate currentStart = calculateCurrentWeekStart(today);
        LocalDate currentEnd = calculateCurrentWeekEnd(today);
        LocalDate upcomingStart = calculateUpcomingWeekStart(today);
        LocalDate upcomingEnd = calculateUpcomingWeekEnd(today);

        String host = "localhost";
        try { host = java.net.InetAddress.getLocalHost().getHostName(); } catch (Exception ignored) {}

        java.util.Map<String, Object> statusMap = new java.util.LinkedHashMap<>();
        statusMap.put("status", isShuttingDown ? "STOPPED" : (autoGenerationEnabled ? "ACTIVE" : "DISABLED"));
        statusMap.put("autoGenerationEnabled", autoGenerationEnabled);
        statusMap.put("autoEmailEnabled", autoEmailEnabled);
        statusMap.put("timezone", autoGenerationTimezone != null ? autoGenerationTimezone : "Asia/Kolkata");
        statusMap.put("currentTimeIst", nowIst.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        statusMap.put("currentTimeUtc", nowUtc.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        statusMap.put("currentWeek", currentStart + " -> " + currentEnd);
        statusMap.put("upcomingWeek", upcomingStart + " -> " + upcomingEnd);
        statusMap.put("cron", "0 0 9 * * SUN");
        statusMap.put("instance", java.lang.management.ManagementFactory.getRuntimeMXBean().getName());
        statusMap.put("hostname", host);
        statusMap.put("lastRunAt", lastRunAt != null ? lastRunAt.toString() : "NONE");
        statusMap.put("lastAutomaticCycle", lastAutomaticCycle);
        statusMap.put("lastResult", lastResult);
        return statusMap;
    }

    /**
     * Safe dry-run preview: calculates the upcoming week cycle that WOULD be generated.
     */
    public java.util.Map<String, Object> previewUpcomingCycle() {
        java.time.ZoneId istZone = java.time.ZoneId.of(autoGenerationTimezone != null ? autoGenerationTimezone : "Asia/Kolkata");
        LocalDate today = LocalDate.now(istZone);
        LocalDate upcomingStart = calculateUpcomingWeekStart(today);
        LocalDate upcomingEnd = calculateUpcomingWeekEnd(today);

        boolean exists = cycleRepository.findByStartDateAndEndDate(upcomingStart, upcomingEnd).isPresent();
        return java.util.Map.of(
                "today", today.toString(),
                "targetUpcomingStart", upcomingStart.toString(),
                "targetUpcomingEnd", upcomingEnd.toString(),
                "cycleAlreadyExists", exists,
                "action", exists ? "DO_NOTHING" : "GENERATE_AND_PUBLISH"
        );
    }

    /**
     * Idempotent automatic generation runner for the immediate upcoming Monday.
     * NEVER pre-generates multiple future weeks.
     * If a manual or existing cycle already exists, DOES NOTHING.
     */
    public RosterCycleResponse executeAutoGeneration(LocalDate targetMonday) {
        this.lastRunAt = LocalDateTime.now();
        if (isShuttingDown) {
            log.warn("[WRMS Scheduler] Automatic generation aborted: System shutdown in progress.");
            this.lastResult = "ABORTED_SHUTDOWN";
            return null;
        }

        java.time.ZoneId istZone = java.time.ZoneId.of(autoGenerationTimezone != null ? autoGenerationTimezone : "Asia/Kolkata");
        java.time.ZonedDateTime nowIst = java.time.ZonedDateTime.now(istZone);
        java.time.ZonedDateTime nowUtc = nowIst.withZoneSameInstant(java.time.ZoneId.of("UTC"));
        String instanceInfo = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();

        LocalDate baseDate = nowIst.toLocalDate();
        LocalDate currentStart = calculateCurrentWeekStart(baseDate);
        LocalDate currentEnd = calculateCurrentWeekEnd(baseDate);
        LocalDate upcomingStart = calculateUpcomingWeekStart(baseDate);
        LocalDate upcomingEnd = calculateUpcomingWeekEnd(baseDate);

        if (targetMonday == null) {
            targetMonday = upcomingStart;
        }

        log.info("[WRMS Scheduler]");
        log.info("[WRMS Scheduler] Time: {} IST (UTC: {})",
                nowIst.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                nowUtc.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        log.info("[WRMS Scheduler] Instance: {}", instanceInfo);
        log.info("[WRMS Scheduler] Today: {}", baseDate);
        log.info("[WRMS Scheduler] Current Week: {} -> {}", currentStart, currentEnd);
        log.info("[WRMS Scheduler] Upcoming Week: {} -> {}", upcomingStart, upcomingEnd);

        this.lastAutomaticCycle = upcomingStart + " -> " + upcomingEnd;

        // Centralized Future Roster Guard:
        if (!isAutomaticGenerationAllowed(targetMonday, baseDate)) {
            log.warn("[WRMS Scheduler] Skipping cycle: {} -> {}. Reason: Not the immediate upcoming week (expected {} -> {}).",
                    targetMonday, targetMonday.plusDays(6), upcomingStart, upcomingEnd);
            this.lastResult = "SKIPPED_NOT_UPCOMING";
            throw new BusinessException("Automatic generation is strictly restricted to the immediately upcoming week (" + upcomingStart + " to " + upcomingEnd + ")");
        }

        log.info("[WRMS Scheduler] Processing ONLY upcoming cycle: {} -> {}", upcomingStart, upcomingEnd);

        // IDEMPOTENCY & EXACT DATABASE QUERY:
        // Query exact target (startDate = upcomingWeekStart AND endDate = upcomingWeekEnd)
        List<RosterCycle> existingCycles = cycleRepository.findByStartDateAndEndDate(upcomingStart, upcomingEnd)
                .map(List::of)
                .orElseGet(() -> cycleRepository.findOverlappingCycles(upcomingStart, upcomingEnd));

        if (!existingCycles.isEmpty()) {
            RosterCycle existing = existingCycles.get(0);
            log.info("[WRMS Scheduler] Roster cycle already exists (ID: #{}, Mode: {}, Status: {}) for {} to {}. Automatic generation skipped (DO NOTHING).",
                    existing.getId(), existing.getGenerationMode(), existing.getStatus(), existing.getStartDate(), existing.getEndDate());
            
            this.lastResult = "SKIPPED_ALREADY_EXISTS";

            // If already published and auto-email is enabled, check idempotency and email
            if (autoEmailEnabled && existing.getStatus() == RosterStatus.PUBLISHED) {
                RosterCycleResponse cycleResp = rosterService.cycle(existing.getId());
                rosterEmailService.distributeRosterEmails(existing, cycleResp, GenerationMode.AUTOMATIC);
            }
            return rosterService.cycle(existing.getId());
        }

        try {
            log.info("[WRMS Scheduler] Starting automatic generation for {} to {}...", upcomingStart, upcomingEnd);
            RosterCycleResponse response = rosterService.generateWeeklyRoster(upcomingStart, GenerationMode.AUTOMATIC);
            log.info("[WRMS Scheduler] Automatic roster generation SUCCESSFUL for cycle #{} ({} to {})",
                    response.id(), response.startDate(), response.endDate());

            // Health Validation and Lifecycle Decision
            boolean healthPassed = true;
            if (rosterHealthService != null && response.id() != null) {
                RosterHealthReport health = rosterHealthService.getCycleHealth(response.id());
                healthPassed = health.readyToPublish();
                if (!healthPassed) {
                    log.warn("[WRMS Scheduler] Automatic roster #{} has {} critical conflict(s). Status kept as GENERATED.",
                            response.id(), health.criticalConflictsCount());
                    if (notificationService != null) {
                        notificationService.notifyAdmins(
                                "Automatic Generation: Conflicts Detected",
                                "Automatic roster for " + response.startDate() + " to " + response.endDate() + " generated with "
                                        + health.criticalConflictsCount() + " critical conflict(s). Review in Roster Health.",
                                NotificationType.ADMIN_ALERT, "health", response.id());
                    }
                }
            }

            if (healthPassed && response.id() != null) {
                cycleRepository.findById(response.id()).ifPresent(cycle -> {
                    cycle.setStatus(RosterStatus.TENTATIVE);
                    cycle.setPublishedAt(LocalDateTime.now());
                    cycle.setPublishedBy("SYSTEM");
                    cycleRepository.save(cycle);
                });
                log.info("[WRMS Scheduler] Automatic roster #{} marked TENTATIVE. Review window open until 4:00 PM IST.", response.id());

                if (notificationService != null) {
                    notificationService.notifyAllActiveEmployees(
                            "Tentative Weekly Roster Available",
                            "Your tentative weekly roster for " + response.startDate() + " to " + response.endDate() + " is now available for review. Submit change requests before Sunday 4:00 PM IST.",
                            NotificationType.ROSTER_PUBLISHED, "roster", response.id());
                }

                // Trigger automated tentative email distribution if enabled
                if (autoEmailEnabled) {
                    cycleRepository.findById(response.id()).ifPresent(cycle -> {
                        rosterEmailService.distributeRosterEmails(cycle, response, GenerationMode.AUTOMATIC);
                    });
                }
            }

            this.lastResult = "SUCCESS";

            RosterCycleResponse updated = null;
            if (response.id() != null) {
                try {
                    updated = rosterService.cycle(response.id());
                } catch (Exception ignored) {}
            }
            return updated != null ? updated : response;
        } catch (Exception e) {
            this.lastResult = "FAILED: " + e.getMessage();
            log.error("[WRMS Scheduler] Automatic roster generation FAILED for {}. Reason: {}", upcomingStart, e.getMessage(), e);
            if (notificationService != null) {
                notificationService.notifyAdmins(
                        "Automatic Generation Failed",
                        "Automatic roster generation for " + upcomingStart + " failed: " + e.getMessage(),
                        NotificationType.ADMIN_ALERT, "roster", null);
            }
            throw e;
        }
    }
}
