package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.weeklyroster.dto.response.EmailDeliveryLogResponse;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.dto.response.RosterHealthReport;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.EmailDeliveryLogRepository;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.RosterCycleRepository;
import com.weeklyroster.repository.ShiftRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class Batch26UpcomingWeekRosterAndEmailAutomationTest {

    private RosterService rosterService;
    private RosterEmailService rosterEmailService;
    private RosterCycleRepository cycleRepository;
    private EmailDeliveryLogRepository emailLogRepository;
    private EmployeeRepository employeeRepository;
    private ShiftRepository shiftRepository;
    private RosterHealthService rosterHealthService;
    private NotificationService notificationService;
    private RosterSchedulerService schedulerService;

    @BeforeEach
    void setUp() {
        rosterService = mock(RosterService.class);
        cycleRepository = mock(RosterCycleRepository.class);
        emailLogRepository = mock(EmailDeliveryLogRepository.class);
        employeeRepository = mock(EmployeeRepository.class);
        shiftRepository = mock(ShiftRepository.class);
        rosterHealthService = mock(RosterHealthService.class);
        notificationService = mock(NotificationService.class);

        rosterEmailService = new RosterEmailService(emailLogRepository, employeeRepository, cycleRepository,
                null, shiftRepository, null);

        schedulerService = new RosterSchedulerService(rosterService, rosterEmailService, cycleRepository,
                rosterHealthService, notificationService, true, true, "Asia/Kolkata");
    }

    @Test
    @DisplayName("Batch 26 â€” 19: Date Logic Verification for 24 Aug, 31 Aug, 07 Sep, 14 Sep 2026")
    void testDateLogicVerification() {
        // Date 1: 24 Aug 2026 -> Current: 24-30 Aug, Upcoming: 31 Aug-06 Sep
        LocalDate d1 = LocalDate.of(2026, 8, 24);
        assertEquals(LocalDate.of(2026, 8, 24), schedulerService.calculateCurrentWeekStart(d1));
        assertEquals(LocalDate.of(2026, 8, 30), schedulerService.calculateCurrentWeekEnd(d1));
        assertEquals(LocalDate.of(2026, 8, 31), schedulerService.calculateUpcomingWeekStart(d1));
        assertEquals(LocalDate.of(2026, 9, 6), schedulerService.calculateUpcomingWeekEnd(d1));

        // Date 2: 31 Aug 2026 -> Current: 31 Aug-06 Sep, Upcoming: 07-13 Sep
        LocalDate d2 = LocalDate.of(2026, 8, 31);
        assertEquals(LocalDate.of(2026, 8, 31), schedulerService.calculateCurrentWeekStart(d2));
        assertEquals(LocalDate.of(2026, 9, 6), schedulerService.calculateCurrentWeekEnd(d2));
        assertEquals(LocalDate.of(2026, 9, 7), schedulerService.calculateUpcomingWeekStart(d2));
        assertEquals(LocalDate.of(2026, 9, 13), schedulerService.calculateUpcomingWeekEnd(d2));

        // Date 3: 07 Sep 2026 -> Current: 07-13 Sep, Upcoming: 14-20 Sep
        LocalDate d3 = LocalDate.of(2026, 9, 7);
        assertEquals(LocalDate.of(2026, 9, 7), schedulerService.calculateCurrentWeekStart(d3));
        assertEquals(LocalDate.of(2026, 9, 13), schedulerService.calculateCurrentWeekEnd(d3));
        assertEquals(LocalDate.of(2026, 9, 14), schedulerService.calculateUpcomingWeekStart(d3));
        assertEquals(LocalDate.of(2026, 9, 20), schedulerService.calculateUpcomingWeekEnd(d3));

        // Date 4: 14 Sep 2026 -> Current: 14-20 Sep, Upcoming: 21-27 Sep
        LocalDate d4 = LocalDate.of(2026, 9, 14);
        assertEquals(LocalDate.of(2026, 9, 14), schedulerService.calculateCurrentWeekStart(d4));
        assertEquals(LocalDate.of(2026, 9, 20), schedulerService.calculateCurrentWeekEnd(d4));
        assertEquals(LocalDate.of(2026, 9, 21), schedulerService.calculateUpcomingWeekStart(d4));
        assertEquals(LocalDate.of(2026, 9, 27), schedulerService.calculateUpcomingWeekEnd(d4));
    }

    @Test
    @DisplayName("Batch 26 â€” 6 & 13: Strict Validation - Future and Past Weeks Rejected by Guard")
    void testStrictValidationGuard() {
        LocalDate baseDate = LocalDate.of(2026, 8, 24);

        // Immediate upcoming week (31 Aug) is allowed
        assertTrue(schedulerService.isAutomaticGenerationAllowed(LocalDate.of(2026, 8, 31), baseDate));

        // Current week (24 Aug) is REJECTED
        assertFalse(schedulerService.isAutomaticGenerationAllowed(LocalDate.of(2026, 8, 24), baseDate));

        // Next-next week (07 Sep) is REJECTED
        assertFalse(schedulerService.isAutomaticGenerationAllowed(LocalDate.of(2026, 9, 7), baseDate));

        // Past week (17 Aug) is REJECTED
        assertFalse(schedulerService.isAutomaticGenerationAllowed(LocalDate.of(2026, 8, 17), baseDate));

        // Far-future weeks (14 Dec 2026, 11 Jan 2027) are REJECTED
        assertFalse(schedulerService.isAutomaticGenerationAllowed(LocalDate.of(2026, 12, 14), baseDate));
        assertFalse(schedulerService.isAutomaticGenerationAllowed(LocalDate.of(2027, 1, 11), baseDate));
    }

    @Test
    @DisplayName("Batch 26 â€” 7 & 8: Email Automation - Distributes ONLY for Immediate Upcoming Week")
    void testEmailAutomation_ImmediateUpcomingWeekOnly() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        LocalDate currentMonday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        LocalDate upcomingMonday = currentMonday.plusDays(7);
        LocalDate upcomingSunday = upcomingMonday.plusDays(6);

        // 1. Cycle for immediate upcoming week
        RosterCycle upcomingCycle = new RosterCycle();
        upcomingCycle.setId(201L);
        upcomingCycle.setStartDate(upcomingMonday);
        upcomingCycle.setEndDate(upcomingSunday);
        upcomingCycle.setGenerationMode(GenerationMode.AUTOMATIC);

        RosterCycleResponse resp = new RosterCycleResponse(201L, upcomingMonday, upcomingSunday, LocalDateTime.now(), List.of());

        Employee emp = new Employee();
        emp.setId(1L);
        emp.setEmployeeCode("EMP001");
        emp.setFirstName("Rajat");
        emp.setLastName("Maurya");
        emp.setEmail("rkmaurya080217@gmail.com");

        when(employeeRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(emp));
        when(shiftRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of());
        when(emailLogRepository.findByCycleAndStatus(upcomingCycle, EmailDeliveryStatus.SENT)).thenReturn(List.of());
        when(emailLogRepository.save(any(EmailDeliveryLog.class))).thenAnswer(inv -> inv.getArgument(0));

        // Automated dispatch succeeds for immediate upcoming week
        List<EmailDeliveryLogResponse> logs = rosterEmailService.distributeRosterEmails(upcomingCycle, resp, GenerationMode.AUTOMATIC);
        assertNotNull(logs);
        assertEquals(1, logs.size());
        assertEquals("rkmaurya080217@gmail.com", logs.get(0).recipientEmail());

        // 2. Future cycle (e.g. Dec 2026) in AUTOMATIC mode -> SKIPPED (empty logs returned)
        RosterCycle futureCycle = new RosterCycle();
        futureCycle.setId(999L);
        futureCycle.setStartDate(LocalDate.of(2026, 12, 14));
        futureCycle.setEndDate(LocalDate.of(2026, 12, 20));
        futureCycle.setGenerationMode(GenerationMode.AUTOMATIC);

        RosterCycleResponse futureResp = new RosterCycleResponse(999L, LocalDate.of(2026, 12, 14), LocalDate.of(2026, 12, 20), LocalDateTime.now(), List.of());
        List<EmailDeliveryLogResponse> futureLogs = rosterEmailService.distributeRosterEmails(futureCycle, futureResp, GenerationMode.AUTOMATIC);
        assertTrue(futureLogs.isEmpty(), "Automated email distribution must reject non-upcoming future cycles");

        // 3. Past cycle (e.g. Current or past week) in AUTOMATIC mode -> SKIPPED
        RosterCycle pastCycle = new RosterCycle();
        pastCycle.setId(100L);
        pastCycle.setStartDate(currentMonday);
        pastCycle.setEndDate(currentMonday.plusDays(6));
        pastCycle.setGenerationMode(GenerationMode.AUTOMATIC);

        RosterCycleResponse pastResp = new RosterCycleResponse(100L, currentMonday, currentMonday.plusDays(6), LocalDateTime.now(), List.of());
        List<EmailDeliveryLogResponse> pastLogs = rosterEmailService.distributeRosterEmails(pastCycle, pastResp, GenerationMode.AUTOMATIC);
        assertTrue(pastLogs.isEmpty(), "Automated email distribution must reject current/past cycles");
    }

    @Test
    @DisplayName("Batch 26 â€” 9 & 10: Manual Admin Generation Remains Fully Functional For Any Selected Cycle")
    void testManualAdminGeneration_IndependentFromScheduler() {
        // Admin manually creates Dec 2026 cycle
        LocalDate manualStart = LocalDate.of(2026, 12, 14);
        LocalDate manualEnd = LocalDate.of(2026, 12, 20);

        RosterCycle manualCycle = new RosterCycle();
        manualCycle.setId(505L);
        manualCycle.setStartDate(manualStart);
        manualCycle.setEndDate(manualEnd);
        manualCycle.setGenerationMode(GenerationMode.MANUAL);

        RosterCycleResponse manualResp = new RosterCycleResponse(505L, manualStart, manualEnd, LocalDateTime.now(), List.of());

        Employee emp = new Employee();
        emp.setId(1L);
        emp.setEmployeeCode("EMP001");
        emp.setFirstName("Rajat");
        emp.setLastName("Maurya");
        emp.setEmail("rkmaurya080217@gmail.com");

        when(employeeRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(emp));
        when(shiftRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of());
        when(emailLogRepository.save(any(EmailDeliveryLog.class))).thenAnswer(inv -> inv.getArgument(0));

        // When admin clicks "Email Roster" in MANUAL mode, it delivers successfully
        List<EmailDeliveryLogResponse> manualLogs = rosterEmailService.distributeRosterEmails(manualCycle, manualResp, GenerationMode.MANUAL);
        assertEquals(1, manualLogs.size());
        assertEquals("rkmaurya080217@gmail.com", manualLogs.get(0).recipientEmail());
    }

    @Test
    @DisplayName("Batch 26 â€” 20: Automation Acceptance Test - Generates, Publishes and Emails ONLY ONE Cycle")
    void testAutomationAcceptance_EndToEndSingleCycle() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        LocalDate upcomingMonday = schedulerService.calculateUpcomingWeekStart(today);
        LocalDate upcomingSunday = upcomingMonday.plusDays(6);

        RosterCycle cycle = new RosterCycle();
        cycle.setId(301L);
        cycle.setStartDate(upcomingMonday);
        cycle.setEndDate(upcomingSunday);
        cycle.setGenerationMode(GenerationMode.AUTOMATIC);

        RosterCycleResponse genResp = new RosterCycleResponse(
                301L, upcomingMonday, upcomingSunday, LocalDateTime.now(), GenerationMode.AUTOMATIC, "PUBLISHED", List.of(), null
        );

        RosterHealthReport passedHealth = new RosterHealthReport(
                301L, upcomingMonday, upcomingSunday, RosterStatus.GENERATED, true, "READY TO PUBLISH (All Safety Rules Passed)",
                "PASSED", "PASSED", "PASSED", "PASSED", "PASSED", "PASSED", "PASSED", "PASSED", "PASSED",
                0, 0, 0, 0, 0, List.of()
        );

        when(cycleRepository.findByStartDateAndEndDate(upcomingMonday, upcomingSunday)).thenReturn(Optional.empty());
        when(cycleRepository.findOverlappingCycles(upcomingMonday, upcomingSunday)).thenReturn(List.of());
        when(rosterService.generateWeeklyRoster(upcomingMonday, GenerationMode.AUTOMATIC)).thenReturn(genResp);
        when(cycleRepository.findById(301L)).thenReturn(Optional.of(cycle));
        when(rosterHealthService.getCycleHealth(301L)).thenReturn(passedHealth);
        when(rosterService.cycle(301L)).thenReturn(genResp);

        // Execute automatic scheduled flow
        RosterCycleResponse result = schedulerService.executeAutoGeneration(upcomingMonday);

        assertNotNull(result);
        assertEquals(301L, result.id());
        assertEquals(upcomingMonday, result.startDate());
        assertEquals(upcomingSunday, result.endDate());

        // Verify exactly one generation call
        verify(rosterService, times(1)).generateWeeklyRoster(upcomingMonday, GenerationMode.AUTOMATIC);
        // Verify exactly one publish status update
        verify(cycleRepository, times(1)).save(cycle);
        assertTrue(cycle.getStatus() == RosterStatus.PUBLISHED || cycle.getStatus() == RosterStatus.TENTATIVE);
    }
}