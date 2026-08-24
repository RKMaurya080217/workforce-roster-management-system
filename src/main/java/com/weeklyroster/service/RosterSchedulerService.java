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
    @Scheduled(cron = "${roster.auto-generation.cron:0 0 9 * * SUN}", zone = "${roster.auto-generation.timezone:Asia/Kolkata}")
    public void runScheduledSundayGeneration() {
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
     * Idempotent automatic generation runner for the immediate upcoming Monday.
     * NEVER pre-generates multiple future weeks.
     * If a manual or existing cycle already exists, DOES NOTHING.
     */
    public RosterCycleResponse executeAutoGeneration(LocalDate targetMonday) {
        LocalDate baseDate = LocalDate.now(java.time.ZoneId.of(autoGenerationTimezone != null ? autoGenerationTimezone : "Asia/Kolkata"));
        LocalDate currentStart = calculateCurrentWeekStart(baseDate);
        LocalDate currentEnd = calculateCurrentWeekEnd(baseDate);
        LocalDate upcomingStart = calculateUpcomingWeekStart(baseDate);
        LocalDate upcomingEnd = calculateUpcomingWeekEnd(baseDate);

        if (targetMonday == null) {
            targetMonday = upcomingStart;
        }

        log.info("[WRMS Scheduler]");
        log.info("[WRMS Scheduler] Today: {}", baseDate);
        log.info("[WRMS Scheduler] Current Week: {} -> {}", currentStart, currentEnd);
        log.info("[WRMS Scheduler] Upcoming Week: {} -> {}", upcomingStart, upcomingEnd);

        // Centralized Future Roster Guard:
        if (!isAutomaticGenerationAllowed(targetMonday, baseDate)) {
            log.warn("[WRMS Scheduler] Skipping cycle: {} -> {}. Reason: Not the immediate upcoming week (expected {} -> {}).",
                    targetMonday, targetMonday.plusDays(6), upcomingStart, upcomingEnd);
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
                    cycle.setStatus(RosterStatus.PUBLISHED);
                    cycle.setPublishedAt(LocalDateTime.now());
                    cycle.setPublishedBy("SYSTEM");
                    cycleRepository.save(cycle);
                });
                log.info("[WRMS Scheduler] Automatic roster #{} marked PUBLISHED.", response.id());

                if (notificationService != null) {
                    notificationService.notifyAllActiveEmployees(
                            "Weekly Roster Published",
                            "Your weekly roster for " + response.startDate() + " to " + response.endDate() + " is now published.",
                            NotificationType.ROSTER_PUBLISHED, "roster", response.id());
                }

                // Trigger automated email distribution if enabled
                if (autoEmailEnabled) {
                    cycleRepository.findById(response.id()).ifPresent(cycle -> {
                        rosterEmailService.distributeRosterEmails(cycle, response, GenerationMode.AUTOMATIC);
                    });
                }
            }

            RosterCycleResponse updated = null;
            if (response.id() != null) {
                try {
                    updated = rosterService.cycle(response.id());
                } catch (Exception ignored) {}
            }
            return updated != null ? updated : response;
        } catch (Exception e) {
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
