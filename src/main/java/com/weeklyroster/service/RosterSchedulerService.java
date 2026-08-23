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
            log.info("Automatic Sunday roster generation is disabled by configuration (roster.auto-generation.enabled=false).");
            return;
        }

        LocalDate today = LocalDate.now(java.time.ZoneId.of(autoGenerationTimezone != null ? autoGenerationTimezone : "Asia/Kolkata"));
        LocalDate targetMonday = calculateTargetMonday(today);
        executeAutoGeneration(targetMonday);
    }

    /**
     * Backward-compatible alias for scheduled weekly roster generation.
     */
    public void runScheduledMondayGeneration() {
        runScheduledSundayGeneration();
    }

    /**
     * Calculates the immediate next Monday for a given base date.
     * On Sunday (e.g. 23 Aug), returns tomorrow Monday (24 Aug).
     * On Monday (e.g. 24 Aug), returns the current Monday (24 Aug).
     * On Tuesday-Saturday (e.g. 25 Aug), returns next Monday (31 Aug).
     */
    public LocalDate calculateTargetMonday(LocalDate baseDate) {
        if (baseDate == null) {
            baseDate = LocalDate.now(java.time.ZoneId.of(autoGenerationTimezone != null ? autoGenerationTimezone : "Asia/Kolkata"));
        }
        if (baseDate.getDayOfWeek() == DayOfWeek.MONDAY) {
            return baseDate;
        }
        return baseDate.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
    }

    /**
     * Centralized Future Roster Guard / Safety Validation (Part 10).
     * Validates that automatic generation only targets the immediately upcoming Monday.
     * Rejects:
     * - Next-next week or beyond
     * - Past weeks
     * - Multiple future cycles or arbitrary future dates
     */
    public boolean isAutomaticGenerationAllowed(LocalDate targetMonday, LocalDate baseDate) {
        if (targetMonday == null) return false;
        LocalDate immediateUpcoming = calculateTargetMonday(baseDate);
        return targetMonday.equals(immediateUpcoming);
    }

    /**
     * Idempotent automatic generation runner for the immediate upcoming Monday.
     * NEVER pre-generates multiple future weeks.
     * If a manual or existing cycle already exists, DOES NOTHING.
     */
    public RosterCycleResponse executeAutoGeneration(LocalDate targetMonday) {
        LocalDate baseDate = LocalDate.now(java.time.ZoneId.of(autoGenerationTimezone != null ? autoGenerationTimezone : "Asia/Kolkata"));
        if (targetMonday == null) {
            targetMonday = calculateTargetMonday(baseDate);
        } else if (targetMonday.getDayOfWeek() != DayOfWeek.MONDAY) {
            targetMonday = targetMonday.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        }

        // Centralized Future Roster Guard:
        if (!isAutomaticGenerationAllowed(targetMonday, baseDate)) {
            log.warn("Automatic generation rejected by Future Roster Guard for target date {}. Only the immediate upcoming week ({}) is permitted.",
                    targetMonday, calculateTargetMonday(baseDate));
            throw new BusinessException("Automatic generation is strictly restricted to the immediately upcoming week (" + calculateTargetMonday(baseDate) + ")");
        }

        LocalDate endDate = targetMonday.plusDays(6);
        log.info("Checking automatic roster generation for cycle {} to {}...", targetMonday, endDate);

        // IDEMPOTENCY & MANUAL PRIORITY CHECK:
        // If a cycle already exists for this exact week (whether MANUAL, AUTOMATIC, LOCKED, PUBLISHED, or GENERATED), DO NOTHING!
        List<RosterCycle> existingCycles = cycleRepository.findOverlappingCycles(targetMonday, endDate);
        if (existingCycles.isEmpty()) {
            cycleRepository.findByStartDateAndEndDate(targetMonday, endDate).ifPresent(existingCycles::add);
        }
        if (!existingCycles.isEmpty()) {
            RosterCycle existing = existingCycles.get(0);
            log.info("Roster cycle already exists (ID: #{}, Mode: {}, Status: {}) for {} to {}. Automatic generation skipped (DO NOTHING).",
                    existing.getId(), existing.getGenerationMode(), existing.getStatus(), existing.getStartDate(), existing.getEndDate());
            return rosterService.cycle(existing.getId());
        }

        try {
            log.info("Starting automatic Sunday generation for {} to {}...", targetMonday, endDate);
            RosterCycleResponse response = rosterService.generateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC);
            log.info("Automatic Sunday roster generation SUCCESSFUL for cycle #{} ({} to {})",
                    response.id(), response.startDate(), response.endDate());

            // Health Validation and Lifecycle Decision
            boolean healthPassed = true;
            if (rosterHealthService != null && response.id() != null) {
                RosterHealthReport health = rosterHealthService.getCycleHealth(response.id());
                healthPassed = health.readyToPublish();
                if (!healthPassed) {
                    log.warn("Automatic Sunday roster #{} has {} critical conflict(s). Status kept as GENERATED.",
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
                log.info("Automatic Sunday roster #{} marked PUBLISHED.", response.id());

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
            log.error("Automatic roster generation FAILED for {}. Reason: {}", targetMonday, e.getMessage(), e);
            if (notificationService != null) {
                notificationService.notifyAdmins(
                        "Automatic Generation Failed",
                        "Automatic roster generation for " + targetMonday + " failed: " + e.getMessage(),
                        NotificationType.ADMIN_ALERT, "roster", null);
            }
            throw e;
        }
    }
}
