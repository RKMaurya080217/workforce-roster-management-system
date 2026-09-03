package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.weeklyroster.dto.response.EmailDeliveryLogResponse;
import com.weeklyroster.dto.response.RosterAssignmentResponse;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.entity.EmailDeliveryLog;
import com.weeklyroster.entity.EmailDeliveryStatus;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.Gender;
import com.weeklyroster.entity.GenerationMode;
import com.weeklyroster.entity.RosterCycle;
import com.weeklyroster.entity.Shift;
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.repository.EmailDeliveryLogRepository;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.RosterAssignmentRepository;
import com.weeklyroster.repository.RosterCycleRepository;
import com.weeklyroster.repository.ShiftRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RosterEmailServiceTest {

    private EmailDeliveryLogRepository emailLogRepository;
    private EmployeeRepository employeeRepository;
    private RosterCycleRepository cycleRepository;
    private RosterAssignmentRepository assignmentRepository;
    private ShiftRepository shiftRepository;
    private RosterEmailService emailService;

    @BeforeEach
    void setUp() {
        emailLogRepository = mock(EmailDeliveryLogRepository.class);
        employeeRepository = mock(EmployeeRepository.class);
        cycleRepository = mock(RosterCycleRepository.class);
        assignmentRepository = mock(RosterAssignmentRepository.class);
        shiftRepository = mock(ShiftRepository.class);
        com.weeklyroster.service.email.EmailDeliveryResult okResult = com.weeklyroster.service.email.EmailDeliveryResult.success("BREVO", "msg-test-123");
        com.weeklyroster.service.email.EmailService mockEmailService = mock(com.weeklyroster.service.email.EmailService.class);
        org.mockito.Mockito.lenient().when(mockEmailService.sendEmail(any(com.weeklyroster.service.email.EmailMessage.class))).thenReturn(okResult);
        org.mockito.Mockito.lenient().when(mockEmailService.getActiveProviderName()).thenReturn("BREVO");
        emailService = new RosterEmailService(emailLogRepository, employeeRepository, cycleRepository, assignmentRepository, shiftRepository, mockEmailService);
    }

    @Test
    @DisplayName("Should build formatted personal schedule text for an employee")
    void testBuildPersonalSchedule() {
        LocalDate start = LocalDate.of(2026, 8, 24);
        LocalDate end = start.plusDays(6);

        List<Shift> shifts = List.of(
                createShift(1L, ShiftType.MORNING, LocalTime.of(7, 0), LocalTime.of(15, 0)),
                createShift(2L, ShiftType.GENERAL, LocalTime.of(9, 30), LocalTime.of(18, 0)),
                createShift(3L, ShiftType.EVENING, LocalTime.of(14, 0), LocalTime.of(22, 0)),
                createShift(4L, ShiftType.NIGHT, LocalTime.of(22, 0), LocalTime.of(7, 0))
        );

        List<RosterAssignmentResponse> myAssignments = List.of(
                new RosterAssignmentResponse(1L, 1L, start, 10L, "EMP001", "Aarav Sharma", Gender.MALE, ShiftType.MORNING, false, false, false),
                new RosterAssignmentResponse(2L, 1L, start.plusDays(1), 10L, "EMP001", "Aarav Sharma", Gender.MALE, ShiftType.MORNING, false, false, false),
                new RosterAssignmentResponse(3L, 1L, start.plusDays(2), 10L, "EMP001", "Aarav Sharma", Gender.MALE, ShiftType.OFF, true, false, false),
                new RosterAssignmentResponse(4L, 1L, start.plusDays(3), 10L, "EMP001", "Aarav Sharma", Gender.MALE, ShiftType.GENERAL, false, false, false),
                new RosterAssignmentResponse(5L, 1L, start.plusDays(4), 10L, "EMP001", "Aarav Sharma", Gender.MALE, ShiftType.GENERAL, false, false, false),
                new RosterAssignmentResponse(6L, 1L, start.plusDays(5), 10L, "EMP001", "Aarav Sharma", Gender.MALE, ShiftType.NIGHT, false, false, false),
                new RosterAssignmentResponse(7L, 1L, start.plusDays(6), 10L, "EMP001", "Aarav Sharma", Gender.MALE, ShiftType.OFF, false, true, false)
        );

        String schedule = emailService.buildPersonalSchedule(start, end, myAssignments, shifts);

        assertNotNull(schedule);
        assertTrue(schedule.contains("Monday"), "Must mention Monday");
        assertTrue(schedule.contains("MORNING (07:00–15:00)"), "Must include Morning shift timings");
        assertTrue(schedule.contains("WEEKLY OFF"), "Must mention Weekly Off");
        assertTrue(schedule.contains("NIGHT (22:00–07:00 next day)"), "Must mention Night shift overnight timing");
        assertTrue(schedule.contains("ON LEAVE"), "Must mention On Leave");
    }

    @Test
    @DisplayName("Should distribute emails to all active employees and record delivery logs")
    void testDistributeRosterEmails() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        LocalDate start = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).plusDays(7);
        LocalDate end = start.plusDays(6);

        RosterCycle cycle = new RosterCycle();
        cycle.setId(1L);
        cycle.setStartDate(start);
        cycle.setEndDate(end);

        Employee e1 = createEmployee(1L, "EMP001", "Aarav", "Sharma", "aarav@example.com");
        Employee e2 = createEmployee(2L, "EMP002", "Priya", "Patel", "priya@example.com");

        when(employeeRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(e1, e2));
        when(shiftRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of());
        when(emailLogRepository.save(any(EmailDeliveryLog.class))).thenAnswer(inv -> inv.getArgument(0));

        RosterCycleResponse cycleResp = new RosterCycleResponse(1L, start, end, LocalDateTime.now(), List.of());

        List<EmailDeliveryLogResponse> logs = emailService.distributeRosterEmails(cycle, cycleResp, GenerationMode.AUTOMATIC);

        assertNotNull(logs);
        assertEquals(2, logs.size());
        assertEquals(EmailDeliveryStatus.SENT, logs.get(0).status());
        assertEquals(EmailDeliveryStatus.SENT, logs.get(1).status());

        verify(emailLogRepository, times(2)).save(any(EmailDeliveryLog.class));
    }

    @Test
    @DisplayName("Should retry only failed email recipients for a given cycle")
    void testRetryFailedEmails() {
        RosterCycle cycle = new RosterCycle();
        cycle.setId(5L);
        cycle.setStartDate(LocalDate.of(2026, 8, 24));
        cycle.setEndDate(LocalDate.of(2026, 8, 30));

        Employee e1 = createEmployee(1L, "EMP001", "Aarav", "Sharma", "aarav@example.com");

        EmailDeliveryLog failedLog = new EmailDeliveryLog();
        failedLog.setId(99L);
        failedLog.setCycle(cycle);
        failedLog.setEmployee(e1);
        failedLog.setStatus(EmailDeliveryStatus.FAILED);
        failedLog.setErrorMessage("Connection timed out");
        failedLog.setMode(GenerationMode.MANUAL);

        when(cycleRepository.findById(5L)).thenReturn(Optional.of(cycle));
        when(emailLogRepository.findByCycleAndStatus(cycle, EmailDeliveryStatus.FAILED)).thenReturn(List.of(failedLog));
        when(shiftRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of());
        when(assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(cycle)).thenReturn(List.of());
        when(emailLogRepository.save(any(EmailDeliveryLog.class))).thenAnswer(inv -> inv.getArgument(0));

        List<EmailDeliveryLogResponse> results = emailService.retryFailedEmails(5L);

        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals(EmailDeliveryStatus.SENT, results.get(0).status());
    }

    @Test
    @DisplayName("Should return FAILED when sending test email without configured provider credentials")
    void testSendTestEmail_BlockedWithoutPassword() {
        RosterEmailService unconfigured = new RosterEmailService(emailLogRepository, employeeRepository, cycleRepository, assignmentRepository, shiftRepository);
        var result = unconfigured.sendTestEmail("rajatkumarmaury@gmail.com");

        assertNotNull(result);
        assertEquals("FAILED", result.get("status"));
        assertTrue(result.get("message").toString().contains("EMAIL_NOT_CONFIGURED"));
    }

    @Test
    @DisplayName("Should deliver test email via active provider when credentials configured")
    void testSendTestEmail_WithConfiguredProvider() {
        com.weeklyroster.service.email.EmailService mockEmailService = mock(com.weeklyroster.service.email.EmailService.class);
        when(mockEmailService.getActiveProviderName()).thenReturn("BREVO");
        when(mockEmailService.sendEmail(any(com.weeklyroster.service.email.EmailMessage.class)))
                .thenReturn(com.weeklyroster.service.email.EmailDeliveryResult.success("BREVO", "msg-12345"));

        RosterEmailService serviceWithProvider = new RosterEmailService(emailLogRepository, employeeRepository, cycleRepository, assignmentRepository, shiftRepository, mockEmailService);

        var result = serviceWithProvider.sendTestEmail("rajatkumarmaury@gmail.com");

        assertNotNull(result);
        assertEquals("SUCCESS", result.get("status"));
        assertEquals("BREVO", result.get("provider"));
        assertEquals("msg-12345", result.get("messageId"));
        verify(mockEmailService, times(1)).sendEmail(any(com.weeklyroster.service.email.EmailMessage.class));
    }

    private Employee createEmployee(Long id, String code, String first, String last, String email) {
        Employee e = new Employee();
        e.setId(id);
        e.setEmployeeCode(code);
        e.setFirstName(first);
        e.setLastName(last);
        e.setEmail(email);
        e.setGender(Gender.MALE);
        e.setActive(true);
        return e;
    }

    private Shift createShift(Long id, ShiftType type, LocalTime start, LocalTime end) {
        Shift s = new Shift();
        s.setId(id);
        s.setShiftType(type);
        s.setStartTime(start);
        s.setEndTime(end);
        s.setActive(true);
        s.setOvernight(type == ShiftType.NIGHT);
        return s;
    }
}
