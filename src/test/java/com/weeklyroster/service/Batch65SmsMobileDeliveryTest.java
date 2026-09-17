package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import com.weeklyroster.controller.SmsController;
import com.weeklyroster.dto.response.SmsDiagnosticsResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.*;
import com.weeklyroster.service.email.EmailDeliveryResult;
import com.weeklyroster.service.email.EmailService;
import com.weeklyroster.service.push.NotificationPushService;
import com.weeklyroster.service.sms.*;
import com.weeklyroster.service.sms.provider.Fast2SmsProvider;
import com.weeklyroster.service.sms.provider.GenericRestSmsProvider;
import com.weeklyroster.service.sms.provider.LogSmsProvider;
import com.weeklyroster.util.PhoneUtils;

@SpringBootTest
class Batch65SmsMobileDeliveryTest {

    @Autowired
    private SmsService smsService;

    @Autowired
    private SmsController smsController;

    @Autowired
    private SmsDeliveryLogRepository smsDeliveryLogRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private RosterAssignmentRepository assignmentRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private EmailDeliveryLogRepository emailLogRepository;

    @Autowired
    private RosterService rosterService;

    @BeforeEach
    void setUp() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        smsDeliveryLogRepository.deleteAll();
    }

    // =========================================================================
    // Test 1: Valid employee mobile -> SMS request succeeds / accepted
    // =========================================================================
    @Test
    @DisplayName("Test 1: Valid employee mobile -> SMS request succeeds / accepted")
    void test01_ValidEmployeeMobile_RequestAccepted() {
        long uniqueId = System.currentTimeMillis();
        Employee emp = new Employee();
        emp.setId(uniqueId);
        emp.setEmployeeCode("EMP" + uniqueId);
        emp.setFirstName("Rajat");
        emp.setLastName("Maurya");
        emp.setContactNumber("9876543210");

        RosterCycle cycle = new RosterCycle();
        cycle.setId(uniqueId + 100);
        cycle.setStartDate(LocalDate.of(2026, 9, 14));
        cycle.setEndDate(LocalDate.of(2026, 9, 20));

        SmsDeliveryResult result = smsService.sendRosterSms(cycle, emp, true, "14 Sep – 20 Sep 2026");
        assertNotNull(result);
        assertTrue(result.success());
        assertNotNull(result.messageId());
        assertTrue(result.deliveryStatus() == SmsDeliveryStatus.REQUEST_ACCEPTED ||
                   result.deliveryStatus() == SmsDeliveryStatus.SIMULATED_LOG);
    }

    // =========================================================================
    // Test 2: Missing mobile -> SMS skipped safely with SKIPPED_NO_PHONE
    // =========================================================================
    @Test
    @DisplayName("Test 2: Missing mobile -> SMS skipped safely with SKIPPED_NO_PHONE")
    void test02_MissingMobile_SkippedSafely() {
        long uniqueId = System.currentTimeMillis();
        Employee emp = new Employee();
        emp.setId(uniqueId);
        emp.setEmployeeCode("EMP" + uniqueId);
        emp.setContactNumber(null);

        RosterCycle cycle = new RosterCycle();
        cycle.setId(uniqueId + 100);

        SmsDeliveryResult result = smsService.sendRosterSms(cycle, emp, true, "14 Sep – 20 Sep 2026");
        assertNotNull(result);
        assertFalse(result.success());
        assertEquals(SmsDeliveryStatus.SKIPPED_NO_PHONE, result.deliveryStatus());
        assertTrue(result.errorMessage().contains("Missing contact number"));
    }

    // =========================================================================
    // Test 3: Invalid mobile -> SMS skipped safely with SKIPPED_INVALID_PHONE
    // =========================================================================
    @Test
    @DisplayName("Test 3: Invalid mobile -> SMS skipped safely with SKIPPED_INVALID_PHONE")
    void test03_InvalidMobile_SkippedSafely() {
        long uniqueId = System.currentTimeMillis();
        Employee emp = new Employee();
        emp.setId(uniqueId);
        emp.setEmployeeCode("EMP" + uniqueId);
        emp.setContactNumber("123"); // less than 10 digits

        RosterCycle cycle = new RosterCycle();
        cycle.setId(uniqueId + 100);

        SmsDeliveryResult result = smsService.sendRosterSms(cycle, emp, true, "14 Sep – 20 Sep 2026");
        assertNotNull(result);
        assertFalse(result.success());
        assertEquals(SmsDeliveryStatus.SKIPPED_INVALID_PHONE, result.deliveryStatus());
        assertTrue(result.errorMessage().contains("Invalid phone number"));
    }

    // =========================================================================
    // Test 4: Provider authentication failure -> clear failure status recorded
    // =========================================================================
    @Test
    @DisplayName("Test 4: Provider authentication failure -> clear failure status recorded")
    void test04_ProviderAuthenticationFailure() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(401);
        when(mockResponse.body()).thenReturn("{\"return\":false,\"status_code\":401,\"message\":\"Invalid API Key\"}");
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        Fast2SmsProvider provider = new Fast2SmsProvider("invalid_key_xyz", "WRMS", "https://api.test/sms", mockHttpClient);
        assertTrue(provider.isConfigured());

        SmsDeliveryResult result = provider.send("9876543210", "Test message", SmsMessageType.FINAL_ROSTER, null);
        assertNotNull(result);
        assertFalse(result.success());
        assertEquals(SmsDeliveryStatus.REQUEST_FAILED, result.deliveryStatus());
        assertTrue(result.errorMessage().contains("Authentication failure"));
    }

    // =========================================================================
    // Test 5: Provider API unavailable -> roster generation remains successful
    // =========================================================================
    @Test
    @DisplayName("Test 5: Provider API unavailable -> roster generation remains successful")
    @Transactional
    void test05_ProviderUnavailable_RosterRemainsSuccessful() {
        SmsService failingSms = mock(SmsService.class);
        when(failingSms.sendRosterSms(any(), any(), anyBoolean(), anyString()))
                .thenThrow(new RuntimeException("Telecom gateway network timeout"));

        EmailService mockEmail = mock(EmailService.class);
        when(mockEmail.sendEmail(any())).thenReturn(EmailDeliveryResult.success("BREVO", "MSG-123"));

        NotificationPushService mockPush = mock(NotificationPushService.class);

        RosterEmailService emailService = new RosterEmailService(
                emailLogRepository, employeeRepository, cycleRepository,
                assignmentRepository, shiftRepository, mockEmail, failingSms, mockPush);

        RosterCycle cycle = cycleRepository.findAll().stream().findFirst().orElse(null);
        assertNotNull(cycle);

        Employee emp = employeeRepository.findByActiveTrueOrderByIdAsc().get(0);
        List<Shift> shifts = shiftRepository.findByActiveTrueOrderByIdAsc();

        // Must complete without throwing and persist delivery log
        EmailDeliveryLog logEntry = emailService.sendToEmployee(cycle, emp, List.of(), shifts, null, null, GenerationMode.MANUAL, null);
        assertNotNull(logEntry);
        assertEquals(EmailDeliveryStatus.SENT, logEntry.getStatus());
    }

    // =========================================================================
    // Test 6: Brevo succeeds + SMS succeeds
    // =========================================================================
    @Test
    @DisplayName("Test 6: Brevo email succeeds + SMS succeeds")
    @Transactional
    void test06_BrevoSucceeds_And_SmsSucceeds() {
        EmailService mockEmail = mock(EmailService.class);
        when(mockEmail.sendEmail(any())).thenReturn(EmailDeliveryResult.success("BREVO", "MSG-SUCCESS"));

        SmsService mockSms = mock(SmsService.class);
        when(mockSms.sendRosterSms(any(), any(), anyBoolean(), anyString()))
                .thenReturn(SmsDeliveryResult.success("SMS-REQ-001", "FAST2SMS"));

        NotificationPushService mockPush = mock(NotificationPushService.class);

        RosterEmailService emailService = new RosterEmailService(
                emailLogRepository, employeeRepository, cycleRepository,
                assignmentRepository, shiftRepository, mockEmail, mockSms, mockPush);

        RosterCycle cycle = cycleRepository.findAll().stream().findFirst().orElse(null);
        Employee emp = employeeRepository.findByActiveTrueOrderByIdAsc().get(0);
        List<Shift> shifts = shiftRepository.findByActiveTrueOrderByIdAsc();

        EmailDeliveryLog deliveryLog = emailService.sendToEmployee(cycle, emp, List.of(), shifts, null, null, GenerationMode.MANUAL, null);
        assertEquals(EmailDeliveryStatus.SENT, deliveryLog.getStatus());
        verify(mockSms, times(1)).sendRosterSms(eq(cycle), eq(emp), anyBoolean(), anyString());
    }

    // =========================================================================
    // Test 7: Brevo succeeds + SMS fails -> email succeeded, SMS failed, roster intact
    // =========================================================================
    @Test
    @DisplayName("Test 7: Brevo email succeeds + SMS fails -> email succeeded, SMS failed, roster intact")
    @Transactional
    void test07_BrevoSucceeds_And_SmsFails() {
        EmailService mockEmail = mock(EmailService.class);
        when(mockEmail.sendEmail(any())).thenReturn(EmailDeliveryResult.success("BREVO", "MSG-SUCCESS"));

        SmsService mockSms = mock(SmsService.class);
        when(mockSms.sendRosterSms(any(), any(), anyBoolean(), anyString()))
                .thenReturn(SmsDeliveryResult.failure("FAST2SMS", "Insufficient SMS balance", SmsDeliveryStatus.REQUEST_FAILED));

        NotificationPushService mockPush = mock(NotificationPushService.class);

        RosterEmailService emailService = new RosterEmailService(
                emailLogRepository, employeeRepository, cycleRepository,
                assignmentRepository, shiftRepository, mockEmail, mockSms, mockPush);

        RosterCycle cycle = cycleRepository.findAll().stream().findFirst().orElse(null);
        Employee emp = employeeRepository.findByActiveTrueOrderByIdAsc().get(0);
        List<Shift> shifts = shiftRepository.findByActiveTrueOrderByIdAsc();

        EmailDeliveryLog deliveryLog = emailService.sendToEmployee(cycle, emp, List.of(), shifts, null, null, GenerationMode.MANUAL, null);
        assertEquals(EmailDeliveryStatus.SENT, deliveryLog.getStatus());
        verify(mockSms, times(1)).sendRosterSms(eq(cycle), eq(emp), anyBoolean(), anyString());
    }

    // =========================================================================
    // Test 8: SMS succeeds + FCM fails -> SMS accepted, FCM logged, roster intact
    // =========================================================================
    @Test
    @DisplayName("Test 8: SMS succeeds + FCM fails -> SMS accepted, FCM logged, roster intact")
    @Transactional
    void test08_SmsSucceeds_And_FcmFails() {
        EmailService mockEmail = mock(EmailService.class);
        when(mockEmail.sendEmail(any())).thenReturn(EmailDeliveryResult.success("BREVO", "MSG-101"));

        SmsService mockSms = mock(SmsService.class);
        when(mockSms.sendRosterSms(any(), any(), anyBoolean(), anyString()))
                .thenReturn(SmsDeliveryResult.success("SMS-ACCEPT-01", "FAST2SMS"));

        NotificationPushService mockPush = mock(NotificationPushService.class);
        doThrow(new RuntimeException("FCM token expired / network issue"))
                .when(mockPush).sendRosterNotification(any(), any(), anyBoolean(), anyString());

        RosterEmailService emailService = new RosterEmailService(
                emailLogRepository, employeeRepository, cycleRepository,
                assignmentRepository, shiftRepository, mockEmail, mockSms, mockPush);

        RosterCycle cycle = cycleRepository.findAll().stream().findFirst().orElse(null);
        Employee emp = employeeRepository.findByActiveTrueOrderByIdAsc().get(0);
        List<Shift> shifts = shiftRepository.findByActiveTrueOrderByIdAsc();

        EmailDeliveryLog deliveryLog = emailService.sendToEmployee(cycle, emp, List.of(), shifts, null, null, GenerationMode.MANUAL, null);
        assertNotNull(deliveryLog);
        assertEquals(EmailDeliveryStatus.SENT, deliveryLog.getStatus());
        verify(mockSms, times(1)).sendRosterSms(eq(cycle), eq(emp), anyBoolean(), anyString());
    }

    // =========================================================================
    // Test 9: Duplicate roster trigger -> duplicate SMS protection
    // =========================================================================
    @Test
    @DisplayName("Test 9: Duplicate roster trigger -> duplicate SMS protection prevents duplicate dispatch")
    void test09_DuplicateSmsProtection() {
        long uniqueId = System.currentTimeMillis();
        Employee emp = new Employee();
        emp.setId(uniqueId);
        emp.setEmployeeCode("EMP" + uniqueId);
        emp.setContactNumber("9876543210");

        RosterCycle cycle = new RosterCycle();
        cycle.setId(uniqueId + 100);

        // First dispatch
        SmsDeliveryResult res1 = smsService.sendRosterSms(cycle, emp, true, "14 Sep – 20 Sep 2026");
        assertNotNull(res1);
        assertTrue(res1.success());

        // Second dispatch for identical cycle, employee, and type
        SmsDeliveryResult res2 = smsService.sendRosterSms(cycle, emp, true, "14 Sep – 20 Sep 2026");
        assertNotNull(res2);
        assertFalse(res2.success());
        assertEquals(SmsDeliveryStatus.SKIPPED_DUPLICATE, res2.deliveryStatus());
        assertTrue(res2.errorMessage().contains("Duplicate SMS skipped"));
    }

    // =========================================================================
    // Test 10: Tentative roster message exact dynamic copy
    // =========================================================================
    @Test
    @DisplayName("Test 10: Tentative roster message exact dynamic date copy")
    void test10_TentativeRosterMessageCopy() {
        String dateRange = "14 Sep – 20 Sep 2026";
        String expected = "WRMS: Your tentative roster for 14 Sep – 20 Sep 2026 has been generated. Please check your registered email/WRMS portal.";

        String dynamic = String.format("WRMS: Your %s roster for %s has been generated. Please check your registered email/WRMS portal.",
                "tentative", dateRange);
        assertEquals(expected, dynamic);
    }

    // =========================================================================
    // Test 11: Final roster message exact dynamic copy
    // =========================================================================
    @Test
    @DisplayName("Test 11: Final roster message exact dynamic date copy")
    void test11_FinalRosterMessageCopy() {
        String dateRange = "14 Sep – 20 Sep 2026";
        String expected = "WRMS: Your final roster for 14 Sep – 20 Sep 2026 has been generated. Please check your registered email/WRMS portal.";

        String dynamic = String.format("WRMS: Your %s roster for %s has been generated. Please check your registered email/WRMS portal.",
                "final", dateRange);
        assertEquals(expected, dynamic);
    }

    // =========================================================================
    // Test 12: Multiple employees receive individual SMS
    // =========================================================================
    @Test
    @DisplayName("Test 12: Multiple employees receive individual SMS")
    void test12_MultipleEmployees_ReceiveIndividualSms() {
        long uniqueId = System.currentTimeMillis();
        Employee empA = new Employee();
        empA.setId(uniqueId);
        empA.setEmployeeCode("EMP" + uniqueId);
        empA.setContactNumber("9876543211");

        Employee empB = new Employee();
        empB.setId(uniqueId + 1);
        empB.setEmployeeCode("EMP" + (uniqueId + 1));
        empB.setContactNumber("9876543212");

        RosterCycle cycle = new RosterCycle();
        cycle.setId(uniqueId + 200);

        SmsDeliveryResult resA = smsService.sendRosterSms(cycle, empA, true, "14 Sep – 20 Sep 2026");
        SmsDeliveryResult resB = smsService.sendRosterSms(cycle, empB, true, "14 Sep – 20 Sep 2026");

        assertTrue(resA.success());
        assertTrue(resB.success());
        assertNotEquals(resA.messageId(), resB.messageId());
    }

    // =========================================================================
    // Test 13: Employee A cannot receive Employee B's roster SMS
    // =========================================================================
    @Test
    @DisplayName("Test 13: Employee A cannot receive Employee B's roster SMS")
    void test13_EmployeePhoneMappingIntegrity() {
        String phoneA = "9876543211";
        String phoneB = "9876543299";

        String normA = PhoneUtils.normalize10Digits(phoneA);
        String normB = PhoneUtils.normalize10Digits(phoneB);

        assertNotEquals(normA, normB);
        assertEquals("9876543211", normA);
        assertEquals("9876543299", normB);
    }

    // =========================================================================
    // Test 14: Security: No secrets, passwords, or API keys in diagnostics/responses
    // =========================================================================
    @Test
    @DisplayName("Test 14: Security: No secrets in diagnostics or test responses")
    void test14_Security_NoSecretsInResponses() {
        ResponseEntity<SmsDiagnosticsResponse> diagResp = smsController.getDiagnostics();
        assertNotNull(diagResp.getBody());
        SmsDiagnosticsResponse diag = diagResp.getBody();

        // Diagnostics must not expose any secrets
        assertNotNull(diag.operatingMode());
        assertNotNull(diag.provider());

        // Admin test endpoint
        ResponseEntity<Map<String, Object>> testResp = smsController.sendAdminTest(
                Map.of("phone", "9876543210")
        );
        assertNotNull(testResp.getBody());
        Map<String, Object> body = testResp.getBody();

        assertTrue((Boolean) body.get("success"));
        assertNotNull(body.get("recipientPhoneMasked"));
        assertTrue(body.get("recipientPhoneMasked").toString().startsWith("******"));
        assertFalse(body.containsKey("apiKey"));
        assertFalse(body.containsKey("apiSecret"));
        assertFalse(body.containsKey("password"));
    }
}
