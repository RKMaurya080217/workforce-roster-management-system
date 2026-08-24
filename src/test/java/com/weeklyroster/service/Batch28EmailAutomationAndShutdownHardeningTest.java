package com.weeklyroster.service;

import com.weeklyroster.dto.response.EmailDeliveryLogResponse;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.entity.EmailDeliveryLog;
import com.weeklyroster.entity.EmailDeliveryStatus;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.GenerationMode;
import com.weeklyroster.entity.RosterCycle;
import com.weeklyroster.entity.RosterStatus;
import com.weeklyroster.entity.Shift;
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.EmailDeliveryLogRepository;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.RosterAssignmentRepository;
import com.weeklyroster.repository.RosterCycleRepository;
import com.weeklyroster.repository.ShiftRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Batch28EmailAutomationAndShutdownHardeningTest {

    @Mock
    private EmailDeliveryLogRepository emailLogRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private RosterCycleRepository cycleRepository;

    @Mock
    private RosterAssignmentRepository assignmentRepository;

    @Mock
    private ShiftRepository shiftRepository;

    @Mock
    private RosterService rosterService;

    private RosterEmailService emailService;
    private RosterSchedulerService schedulerService;

    private LocalDate today;
    private LocalDate upcomingMonday;
    private LocalDate upcomingSunday;
    private Employee emp1;
    private Shift morningShift;

    @BeforeEach
    void setUp() {
        emailService = new RosterEmailService(emailLogRepository, employeeRepository, cycleRepository, assignmentRepository, shiftRepository);
        schedulerService = new RosterSchedulerService(rosterService, emailService, cycleRepository);

        today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        upcomingMonday = schedulerService.calculateUpcomingWeekStart(today);
        upcomingSunday = schedulerService.calculateUpcomingWeekEnd(today);

        emp1 = new Employee();
        emp1.setId(1L);
        emp1.setEmployeeCode("EMP001");
        emp1.setFirstName("Rajat");
        emp1.setLastName("Maurya");
        emp1.setEmail("rkmaurya080217@gmail.com");
        emp1.setActive(true);

        morningShift = new Shift();
        morningShift.setId(1L);
        morningShift.setShiftType(ShiftType.MORNING);
        morningShift.setActive(true);

        when(employeeRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(emp1));
        when(shiftRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(morningShift));
        when(emailLogRepository.save(any(EmailDeliveryLog.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("Shutdown Scenario 1: Scheduler aborts automatic generation when shutting down")
    void test1_SchedulerAbortsOnShutdown() {
        schedulerService.onShutdown();
        RosterCycleResponse resp = schedulerService.executeAutoGeneration(upcomingMonday);
        assertNull(resp, "Scheduler executeAutoGeneration must return null and abort when application is shutting down");
        verify(rosterService, never()).generateWeeklyRoster(any(), any());
    }

    @Test
    @DisplayName("Shutdown Scenario 2: EmailService skips all outgoing emails when shutting down")
    void test2_EmailServiceSkipsOnShutdown() {
        RosterCycle cycle = new RosterCycle();
        cycle.setId(101L);
        cycle.setStartDate(upcomingMonday);
        cycle.setEndDate(upcomingSunday);
        cycle.setGenerationMode(GenerationMode.AUTOMATIC);
        cycle.setStatus(RosterStatus.PUBLISHED);

        RosterCycleResponse cycleResp = new RosterCycleResponse(101L, upcomingMonday, upcomingSunday, LocalDateTime.now(), List.of());

        emailService.onShutdown();
        List<EmailDeliveryLogResponse> logs = emailService.distributeRosterEmails(cycle, cycleResp, GenerationMode.AUTOMATIC);

        assertTrue(logs.isEmpty(), "Email distribution must immediately return empty list when application is shutting down");
        verify(emailLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("Idempotency Scenario: Per-employee check prevents duplicate email sending on same cycle")
    void test3_PerEmployeeEmailIdempotency() {
        RosterCycle cycle = new RosterCycle();
        cycle.setId(101L);
        cycle.setStartDate(upcomingMonday);
        cycle.setEndDate(upcomingSunday);
        cycle.setGenerationMode(GenerationMode.AUTOMATIC);
        cycle.setStatus(RosterStatus.PUBLISHED);

        RosterCycleResponse cycleResp = new RosterCycleResponse(101L, upcomingMonday, upcomingSunday, LocalDateTime.now(), List.of());

        EmailDeliveryLog existingSent = new EmailDeliveryLog();
        existingSent.setId(501L);
        existingSent.setCycle(cycle);
        existingSent.setEmployee(emp1);
        existingSent.setRecipientEmail(emp1.getEmail());
        existingSent.setStatus(EmailDeliveryStatus.SENT);
        existingSent.setSentAt(LocalDateTime.now());
        existingSent.setMode(GenerationMode.AUTOMATIC);

        when(emailLogRepository.findByCycleAndStatus(cycle, EmailDeliveryStatus.SENT)).thenReturn(List.of(existingSent));

        List<EmailDeliveryLogResponse> logs = emailService.distributeRosterEmails(cycle, cycleResp, GenerationMode.AUTOMATIC);

        assertEquals(1, logs.size());
        assertEquals(EmailDeliveryStatus.SENT, logs.get(0).status());
        // Verify no new save operation was performed because it was already sent
        verify(emailLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("Batch 26 Strict Safety: Email service rejects past, current, and far-future cycles in automatic mode")
    void test4_Batch26UpcomingWeekEmailSafety() {
        LocalDate currentStart = schedulerService.calculateCurrentWeekStart(today);
        LocalDate currentEnd = schedulerService.calculateCurrentWeekEnd(today);

        RosterCycle currentCycle = new RosterCycle();
        currentCycle.setId(201L);
        currentCycle.setStartDate(currentStart);
        currentCycle.setEndDate(currentEnd);
        currentCycle.setGenerationMode(GenerationMode.AUTOMATIC);

        RosterCycleResponse currentResp = new RosterCycleResponse(201L, currentStart, currentEnd, LocalDateTime.now(), List.of());

        List<EmailDeliveryLogResponse> currentLogs = emailService.distributeRosterEmails(currentCycle, currentResp, GenerationMode.AUTOMATIC);
        assertTrue(currentLogs.isEmpty(), "Automatic email distribution must reject current week");

        RosterCycle futureCycle = new RosterCycle();
        futureCycle.setId(202L);
        futureCycle.setStartDate(upcomingMonday.plusWeeks(1));
        futureCycle.setEndDate(upcomingSunday.plusWeeks(1));
        futureCycle.setGenerationMode(GenerationMode.AUTOMATIC);

        RosterCycleResponse futureResp = new RosterCycleResponse(202L, upcomingMonday.plusWeeks(1), upcomingSunday.plusWeeks(1), LocalDateTime.now(), List.of());

        List<EmailDeliveryLogResponse> futureLogs = emailService.distributeRosterEmails(futureCycle, futureResp, GenerationMode.AUTOMATIC);
        assertTrue(futureLogs.isEmpty(), "Automatic email distribution must reject next-next week");

        RosterCycle dec2026Cycle = new RosterCycle();
        dec2026Cycle.setId(203L);
        dec2026Cycle.setStartDate(LocalDate.of(2026, 12, 14));
        dec2026Cycle.setEndDate(LocalDate.of(2026, 12, 20));
        dec2026Cycle.setGenerationMode(GenerationMode.AUTOMATIC);

        RosterCycleResponse dec2026Resp = new RosterCycleResponse(203L, LocalDate.of(2026, 12, 14), LocalDate.of(2026, 12, 20), LocalDateTime.now(), List.of());

        List<EmailDeliveryLogResponse> decLogs = emailService.distributeRosterEmails(dec2026Cycle, dec2026Resp, GenerationMode.AUTOMATIC);
        assertTrue(decLogs.isEmpty(), "Automatic email distribution must reject far-future cycle (Dec 2026)");
    }

    @Test
    @DisplayName("Admin Manual Override: Admin can distribute emails for any selected cycle independently")
    void test5_AdminManualEmailDistributionAllowed() {
        LocalDate customStart = LocalDate.of(2026, 9, 14);
        LocalDate customEnd = LocalDate.of(2026, 9, 20);
        RosterCycle manualCycle = new RosterCycle();
        manualCycle.setId(301L);
        manualCycle.setStartDate(customStart);
        manualCycle.setEndDate(customEnd);
        manualCycle.setGenerationMode(GenerationMode.MANUAL);

        RosterCycleResponse manualResp = new RosterCycleResponse(301L, customStart, customEnd, LocalDateTime.now(), List.of());

        List<EmailDeliveryLogResponse> logs = emailService.distributeRosterEmails(manualCycle, manualResp, GenerationMode.MANUAL);
        assertFalse(logs.isEmpty(), "Admin manual email distribution must be permitted for arbitrary cycle");
        verify(emailLogRepository).save(any());
    }

    @Test
    @DisplayName("Exact Date Calculations: 24 Aug, 31 Aug, 07 Sep, 14 Sep produce exactly one immediate upcoming Monday-Sunday week")
    void test6_ExactDateCalculations() {
        // Date 1: Monday 24 Aug 2026
        LocalDate d1 = LocalDate.of(2026, 8, 24);
        assertEquals(LocalDate.of(2026, 8, 24), schedulerService.calculateCurrentWeekStart(d1));
        assertEquals(LocalDate.of(2026, 8, 30), schedulerService.calculateCurrentWeekEnd(d1));
        assertEquals(LocalDate.of(2026, 8, 31), schedulerService.calculateUpcomingWeekStart(d1));
        assertEquals(LocalDate.of(2026, 9, 6), schedulerService.calculateUpcomingWeekEnd(d1));

        // Date 2: Monday 31 Aug 2026
        LocalDate d2 = LocalDate.of(2026, 8, 31);
        assertEquals(LocalDate.of(2026, 8, 31), schedulerService.calculateCurrentWeekStart(d2));
        assertEquals(LocalDate.of(2026, 9, 6), schedulerService.calculateCurrentWeekEnd(d2));
        assertEquals(LocalDate.of(2026, 9, 7), schedulerService.calculateUpcomingWeekStart(d2));
        assertEquals(LocalDate.of(2026, 9, 13), schedulerService.calculateUpcomingWeekEnd(d2));

        // Date 3: Monday 07 Sep 2026
        LocalDate d3 = LocalDate.of(2026, 9, 7);
        assertEquals(LocalDate.of(2026, 9, 7), schedulerService.calculateCurrentWeekStart(d3));
        assertEquals(LocalDate.of(2026, 9, 13), schedulerService.calculateCurrentWeekEnd(d3));
        assertEquals(LocalDate.of(2026, 9, 14), schedulerService.calculateUpcomingWeekStart(d3));
        assertEquals(LocalDate.of(2026, 9, 20), schedulerService.calculateUpcomingWeekEnd(d3));

        // Date 4: Monday 14 Sep 2026
        LocalDate d4 = LocalDate.of(2026, 9, 14);
        assertEquals(LocalDate.of(2026, 9, 14), schedulerService.calculateCurrentWeekStart(d4));
        assertEquals(LocalDate.of(2026, 9, 20), schedulerService.calculateCurrentWeekEnd(d4));
        assertEquals(LocalDate.of(2026, 9, 21), schedulerService.calculateUpcomingWeekStart(d4));
        assertEquals(LocalDate.of(2026, 9, 27), schedulerService.calculateUpcomingWeekEnd(d4));
    }

    @Test
    @DisplayName("Scheduler Status & Preview: Returns diagnostic info without executing side effects")
    void test7_SchedulerStatusAndPreview() {
        Map<String, Object> status = schedulerService.getSchedulerStatus();
        assertNotNull(status);
        assertEquals("ACTIVE", status.get("status"));
        assertEquals("Asia/Kolkata", status.get("timezone"));
        assertNotNull(status.get("currentTimeIst"));
        assertNotNull(status.get("currentTimeUtc"));
        assertNotNull(status.get("currentWeek"));
        assertNotNull(status.get("upcomingWeek"));

        Map<String, Object> preview = schedulerService.previewUpcomingCycle();
        assertNotNull(preview);
        assertNotNull(preview.get("today"));
        assertNotNull(preview.get("targetUpcomingStart"));
        assertNotNull(preview.get("targetUpcomingEnd"));
    }
}
