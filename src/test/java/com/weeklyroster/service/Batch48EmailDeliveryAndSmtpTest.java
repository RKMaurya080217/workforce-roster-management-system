package com.weeklyroster.service;

import com.weeklyroster.dto.response.CoverageReportResponse;
import com.weeklyroster.dto.response.EmailDeliveryLogResponse;
import com.weeklyroster.dto.response.RosterAssignmentResponse;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.*;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class Batch48EmailDeliveryAndSmtpTest {

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
    private JavaMailSender mailSender;

    @InjectMocks
    private RosterEmailService rosterEmailService;

    private Employee mockEmployee;
    private Shift mockShift;
    private RosterCycle mockCycle;
    private RosterCycleResponse mockCycleResponse;

    @BeforeEach
    void setUp() {
        mockEmployee = new Employee();
        mockEmployee.setId(1L);
        mockEmployee.setEmployeeCode("EMP001");
        mockEmployee.setFirstName("Rajat");
        mockEmployee.setLastName("Maurya");
        mockEmployee.setEmail("rkmaurya080217@gmail.com");
        mockEmployee.setActive(true);

        mockShift = new Shift();
        mockShift.setId(1L);
        mockShift.setShiftType(ShiftType.MORNING);
        mockShift.setCapacity(2);
        mockShift.setActive(true);

        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        LocalDate nextMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusDays(7);
        LocalDate nextSunday = nextMonday.plusDays(6);

        mockCycle = new RosterCycle();
        mockCycle.setId(101L);
        mockCycle.setStartDate(nextMonday);
        mockCycle.setEndDate(nextSunday);
        mockCycle.setStatus(RosterStatus.TENTATIVE);
        mockCycle.setGenerationMode(GenerationMode.AUTOMATIC);

        RosterAssignmentResponse assignment = new RosterAssignmentResponse(
                1L, 101L, nextMonday, 1L, "EMP001", "Rajat Maurya",
                Gender.MALE, ShiftType.MORNING, false, false, false, null
        );

        mockCycleResponse = new RosterCycleResponse(
                101L, nextMonday, nextSunday, LocalDateTime.now(),
                GenerationMode.AUTOMATIC, "TENTATIVE", List.of(assignment),
                new CoverageReportResponse(7, 7, 7, 7, 0, 1, List.of(), List.of())
        );

        lenient().when(employeeRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(mockEmployee));
        lenient().when(shiftRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(mockShift));
        lenient().when(emailLogRepository.save(any(EmailDeliveryLog.class))).thenAnswer(i -> {
            EmailDeliveryLog log = i.getArgument(0);
            log.setId(1L);
            return log;
        });
    }

    @Test
    @DisplayName("Test 1: Genuine SMTP delivery calls JavaMailSender.send and returns SENT status")
    void testGenuineSmtpDelivery() {
        ReflectionTestUtils.setField(rosterEmailService, "mailUsername", "rajatkumarmaury@gmail.com");
        ReflectionTestUtils.setField(rosterEmailService, "mailPassword", "test-app-password");

        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));

        List<EmailDeliveryLogResponse> logs = rosterEmailService.distributeRosterEmails(mockCycle, mockCycleResponse, GenerationMode.MANUAL);

        assertNotNull(logs);
        assertEquals(1, logs.size());
        assertEquals(EmailDeliveryStatus.SENT, logs.get(0).status());
        assertNull(logs.get(0).errorMessage());

        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("Test 2: Missing MAIL_APP_PASSWORD sets FAILED status and does NOT claim SENT")
    void testMissingPasswordDoesNotClaimSent() {
        ReflectionTestUtils.setField(rosterEmailService, "mailUsername", "rajatkumarmaury@gmail.com");
        ReflectionTestUtils.setField(rosterEmailService, "mailPassword", ""); // Missing password

        List<EmailDeliveryLogResponse> logs = rosterEmailService.distributeRosterEmails(mockCycle, mockCycleResponse, GenerationMode.MANUAL);

        assertNotNull(logs);
        assertEquals(1, logs.size());
        assertEquals(EmailDeliveryStatus.FAILED, logs.get(0).status());
        assertNotNull(logs.get(0).errorMessage());
        assertTrue(logs.get(0).errorMessage().contains("MAIL_APP_PASSWORD"));

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("Test 3: SMTP Exception sets FAILED status with error details and safe logging")
    void testSmtpExceptionSetsFailedStatus() {
        ReflectionTestUtils.setField(rosterEmailService, "mailUsername", "rajatkumarmaury@gmail.com");
        ReflectionTestUtils.setField(rosterEmailService, "mailPassword", "test-app-password");

        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailSendException("535-5.7.8 Username and Password not accepted")).when(mailSender).send(any(MimeMessage.class));

        List<EmailDeliveryLogResponse> logs = rosterEmailService.distributeRosterEmails(mockCycle, mockCycleResponse, GenerationMode.MANUAL);

        assertNotNull(logs);
        assertEquals(1, logs.size());
        assertEquals(EmailDeliveryStatus.FAILED, logs.get(0).status());
        assertTrue(logs.get(0).errorMessage().contains("535-5.7.8"));
    }

    @Test
    @DisplayName("Test 4: sendTestEmail returns accurate SENT result when SMTP succeeds")
    void testSendTestEmailSuccess() {
        ReflectionTestUtils.setField(rosterEmailService, "mailUsername", "rajatkumarmaury@gmail.com");
        ReflectionTestUtils.setField(rosterEmailService, "mailPassword", "test-app-password");

        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));

        Map<String, Object> result = rosterEmailService.sendTestEmail("test@example.com");

        assertNotNull(result);
        assertEquals("SENT", result.get("status"));
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }
}
