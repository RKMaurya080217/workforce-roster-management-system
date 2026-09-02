package com.weeklyroster.service;

import com.weeklyroster.config.EmailStartupDiagnosticLogger;
import com.weeklyroster.config.RailwayEnvironmentPostProcessor;
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
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class Batch49RailwaySmtpConfigurationTest {

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

    private RosterCycle mockCycle;
    private RosterCycleResponse mockCycleResponse;

    @BeforeEach
    void setUp() {
        Employee emp1 = new Employee();
        emp1.setId(1L);
        emp1.setEmployeeCode("EMP001");
        emp1.setFirstName("Rajat");
        emp1.setLastName("Maurya");
        emp1.setEmail("rkmaurya080217@gmail.com");
        emp1.setActive(true);

        Shift shift = new Shift();
        shift.setId(1L);
        shift.setShiftType(ShiftType.MORNING);
        shift.setCapacity(2);
        shift.setActive(true);

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

        lenient().when(employeeRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(emp1));
        lenient().when(shiftRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(shift));
        lenient().when(emailLogRepository.save(any(EmailDeliveryLog.class))).thenAnswer(i -> {
            EmailDeliveryLog log = i.getArgument(0);
            log.setId(1L);
            return log;
        });
    }

    @Test
    @DisplayName("Test 1: RailwayEnvironmentPostProcessor sanitizes Google App Passwords with spaces and quotes")
    void testPostProcessorSanitizesGoogleAppPasswords() {
        RailwayEnvironmentPostProcessor processor = new RailwayEnvironmentPostProcessor();
        ConfigurableEnvironment env = new StandardEnvironment();

        System.setProperty("MAIL_APP_PASSWORD", "  \"abcd efgh ijkl mnop\"  ");
        System.setProperty("MAIL_USERNAME", "  rajatkumarmaury@gmail.com  ");

        try {
            processor.postProcessEnvironment(env, new SpringApplication());
            String cleanPass = env.getProperty("spring.mail.password");
            String cleanUser = env.getProperty("spring.mail.username");

            assertEquals("abcdefghijklmnop", cleanPass, "Must strip spaces and surrounding quotes from Google App Password");
            assertEquals("rajatkumarmaury@gmail.com", cleanUser, "Must trim username");
        } finally {
            System.clearProperty("MAIL_APP_PASSWORD");
            System.clearProperty("MAIL_USERNAME");
        }
    }

    @Test
    @DisplayName("Test 2: Missing MAIL_APP_PASSWORD reports EMAIL_NOT_CONFIGURED")
    void testMissingPasswordReportsEmailNotConfigured() {
        ReflectionTestUtils.setField(rosterEmailService, "mailUsername", "rajatkumarmaury@gmail.com");
        ReflectionTestUtils.setField(rosterEmailService, "mailPassword", "");

        List<EmailDeliveryLogResponse> logs = rosterEmailService.distributeRosterEmails(mockCycle, mockCycleResponse, GenerationMode.MANUAL);

        assertNotNull(logs);
        assertEquals(1, logs.size());
        assertEquals(EmailDeliveryStatus.FAILED, logs.get(0).status());
        assertTrue(logs.get(0).errorMessage().contains("EMAIL_NOT_CONFIGURED"));
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("Test 3: Configured MAIL_APP_PASSWORD executes JavaMailSender.send and marks SENT")
    void testConfiguredPasswordExecutesSend() {
        ReflectionTestUtils.setField(rosterEmailService, "mailUsername", "rajatkumarmaury@gmail.com");
        ReflectionTestUtils.setField(rosterEmailService, "mailPassword", "abcdefghijklmnop");

        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));

        List<EmailDeliveryLogResponse> logs = rosterEmailService.distributeRosterEmails(mockCycle, mockCycleResponse, GenerationMode.MANUAL);

        assertNotNull(logs);
        assertEquals(1, logs.size());
        assertEquals(EmailDeliveryStatus.SENT, logs.get(0).status());
        assertNull(logs.get(0).errorMessage());
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("Test 4: EmailStartupDiagnosticLogger executes safely without throwing exceptions")
    void testDiagnosticLoggerExecution() {
        EmailStartupDiagnosticLogger logger = new EmailStartupDiagnosticLogger();
        ReflectionTestUtils.setField(logger, "mailHost", "smtp.gmail.com");
        ReflectionTestUtils.setField(logger, "mailPort", "587");
        ReflectionTestUtils.setField(logger, "mailUsername", "rajatkumarmaury@gmail.com");
        ReflectionTestUtils.setField(logger, "mailPassword", "abcdefghijklmnop");
        ReflectionTestUtils.setField(logger, "autoEmailEnabled", true);

        assertDoesNotThrow(() -> logger.run(new DefaultApplicationArguments(new String[0])));
    }
}
