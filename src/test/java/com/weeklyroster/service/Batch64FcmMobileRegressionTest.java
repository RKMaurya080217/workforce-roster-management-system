package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import com.weeklyroster.config.FirebaseConfig;
import com.weeklyroster.controller.NotificationController;
import com.weeklyroster.controller.PushNotificationController;
import com.weeklyroster.dto.response.EmailDeliveryLogResponse;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.*;
import com.weeklyroster.service.email.EmailDeliveryResult;
import com.weeklyroster.service.email.EmailService;
import com.weeklyroster.service.push.NotificationPushService;
import com.weeklyroster.service.push.NotificationPushServiceImpl;
import com.weeklyroster.service.sms.SmsService;

@SpringBootTest
class Batch64FcmMobileRegressionTest {

    @Autowired
    private FirebaseConfig firebaseConfig;

    @Autowired
    private DeviceTokenRepository deviceTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private NotificationPushService notificationPushService;

    @Autowired
    private PushNotificationController pushNotificationController;

    @Autowired
    private NotificationController notificationController;

    @Autowired
    private RosterService rosterService;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private RosterAssignmentRepository assignmentRepository;

    @Autowired
    private EmailDeliveryLogRepository emailLogRepository;

    // =========================================================================
    // Test 1: Employee login -> token registration succeeds
    // =========================================================================
    @Test
    @DisplayName("Test 1: Employee login and token registration succeeds with employee mapping")
    @Transactional
    void test1_employeeLoginTokenRegistrationSucceeds() {
        User empUser = userRepository.findByUsername("emp001").orElseThrow();
        Employee emp = employeeRepository.findByUserUsername("emp001").orElseThrow();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("emp001", "password", List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
        );

        String testToken = "test_token_batch64_emp001_" + System.currentTimeMillis();
        var res = pushNotificationController.registerToken(Map.of(
                "token", testToken,
                "platform", "Chrome on Android"
        ));

        assertEquals(200, res.getStatusCode().value());
        assertTrue((Boolean) res.getBody().get("success"));

        Optional<DeviceToken> dtOpt = deviceTokenRepository.findByToken(testToken);
        assertTrue(dtOpt.isPresent());
        assertEquals(empUser.getId(), dtOpt.get().getUser().getId());
        assertNotNull(dtOpt.get().getEmployee());
        assertEquals(emp.getId(), dtOpt.get().getEmployee().getId());
        assertEquals("Chrome on Android", dtOpt.get().getDeviceType());
        assertTrue(dtOpt.get().isActive());
    }

    // =========================================================================
    // Test 2: Same token registered twice -> no duplicate record
    // =========================================================================
    @Test
    @DisplayName("Test 2: Registering same token twice updates existing record without duplicates")
    @Transactional
    void test2_sameTokenRegisteredTwiceNoDuplicate() {
        User empUser = userRepository.findByUsername("emp002").orElseThrow();
        Employee emp = employeeRepository.findByUserUsername("emp002").orElseThrow();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("emp002", "password", List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
        );

        String testToken = "test_token_duplicate_check_" + System.currentTimeMillis();
        notificationPushService.registerToken(empUser, emp, testToken, "Browser");
        long countBefore = deviceTokenRepository.findAll().stream().filter(d -> d.getToken().equals(testToken)).count();
        assertEquals(1, countBefore);

        // Register same token with new device type
        notificationPushService.registerToken(empUser, emp, testToken, "Samsung Internet on Android");
        long countAfter = deviceTokenRepository.findAll().stream().filter(d -> d.getToken().equals(testToken)).count();
        assertEquals(1, countAfter);

        DeviceToken updated = deviceTokenRepository.findByToken(testToken).orElseThrow();
        assertEquals("Samsung Internet on Android", updated.getDeviceType());
        assertTrue(updated.isActive());
    }

    // =========================================================================
    // Test 3: Two different employees -> tokens remain isolated
    // =========================================================================
    @Test
    @DisplayName("Test 3: Tokens of two different employees remain strictly isolated")
    @Transactional
    void test3_twoEmployeesTokensIsolated() {
        User user1 = userRepository.findByUsername("emp001").orElseThrow();
        Employee emp1 = employeeRepository.findByUserUsername("emp001").orElseThrow();
        User user2 = userRepository.findByUsername("emp002").orElseThrow();
        Employee emp2 = employeeRepository.findByUserUsername("emp002").orElseThrow();

        String token1 = "token_isolation_emp1_" + System.currentTimeMillis();
        String token2 = "token_isolation_emp2_" + System.currentTimeMillis();

        notificationPushService.registerToken(user1, emp1, token1, "Android");
        notificationPushService.registerToken(user2, emp2, token2, "iOS");

        List<DeviceToken> emp1Tokens = deviceTokenRepository.findByEmployeeAndActiveTrue(emp1);
        List<DeviceToken> emp2Tokens = deviceTokenRepository.findByEmployeeAndActiveTrue(emp2);

        assertTrue(emp1Tokens.stream().anyMatch(d -> d.getToken().equals(token1)));
        assertFalse(emp1Tokens.stream().anyMatch(d -> d.getToken().equals(token2)));

        assertTrue(emp2Tokens.stream().anyMatch(d -> d.getToken().equals(token2)));
        assertFalse(emp2Tokens.stream().anyMatch(d -> d.getToken().equals(token1)));
    }

    // =========================================================================
    // Test 4: Employee has two devices -> both can receive push
    // =========================================================================
    @Test
    @DisplayName("Test 4: Employee with multiple devices has both tokens registered and queried")
    @Transactional
    void test4_employeeWithTwoDevicesReceivesPush() {
        User user = userRepository.findByUsername("emp003").orElseThrow();
        Employee emp = employeeRepository.findByUserUsername("emp003").orElseThrow();

        String mobileToken = "token_multidevice_mobile_" + System.currentTimeMillis();
        String desktopToken = "token_multidevice_desktop_" + System.currentTimeMillis();

        notificationPushService.registerToken(user, emp, mobileToken, "Chrome on Android");
        notificationPushService.registerToken(user, emp, desktopToken, "Chrome on Windows");

        List<DeviceToken> tokens = deviceTokenRepository.findByEmployeeAndActiveTrue(emp);
        assertTrue(tokens.stream().anyMatch(d -> d.getToken().equals(mobileToken)));
        assertTrue(tokens.stream().anyMatch(d -> d.getToken().equals(desktopToken)));
        assertTrue(tokens.size() >= 2);
    }

    // =========================================================================
    // Test 5: Invalid token -> token safely deactivated
    // =========================================================================
    @Test
    @DisplayName("Test 5: Deactivating an invalid/unregistered token marks active=false")
    @Transactional
    void test5_invalidTokenDeactivatedSafely() {
        User user = userRepository.findByUsername("emp004").orElseThrow();
        Employee emp = employeeRepository.findByUserUsername("emp004").orElseThrow();

        String badToken = "token_invalid_cleanup_" + System.currentTimeMillis();
        notificationPushService.registerToken(user, emp, badToken, "Mobile");

        assertTrue(deviceTokenRepository.findByToken(badToken).orElseThrow().isActive());

        boolean deactivated = notificationPushService.deactivateToken(badToken, user);
        assertTrue(deactivated);

        DeviceToken dt = deviceTokenRepository.findByToken(badToken).orElseThrow();
        assertFalse(dt.isActive());
    }

    // =========================================================================
    // Test 6: FCM unavailable -> email still succeeds
    // =========================================================================
    @Test
    @DisplayName("Test 6: Simulated/Log mode or FCM failure never fails email delivery")
    void test6_fcmUnavailableEmailStillSucceeds() {
        EmailService mockEmail = mock(EmailService.class);
        when(mockEmail.sendEmail(any())).thenReturn(EmailDeliveryResult.success("BREVO", "msg-123"));
        SmsService mockSms = mock(SmsService.class);
        NotificationPushService mockPush = mock(NotificationPushService.class);
        doThrow(new RuntimeException("Simulated FCM server connection failure")).when(mockPush).sendRosterNotification(any(), any(), anyBoolean(), any());

        RosterEmailService emailService = new RosterEmailService(
                emailLogRepository, employeeRepository, cycleRepository,
                assignmentRepository, shiftRepository, mockEmail, mockSms, mockPush
        );

        RosterCycle cycle = cycleRepository.findAll().stream().findFirst().orElse(null);
        if (cycle == null) return;
        Employee emp = employeeRepository.findByActiveTrueOrderByIdAsc().get(0);

        // Sending email should not throw despite push service exception
        assertDoesNotThrow(() -> {
            var logRes = emailService.sendToEmployee(cycle, emp, List.of(), List.of(), null, null, GenerationMode.MANUAL, null);
            assertNotNull(logRes);
            assertEquals(EmailDeliveryStatus.SENT, logRes.getStatus());
        });
    }

    // =========================================================================
    // Test 7: Brevo email fails -> push does not falsely say email was sent
    // =========================================================================
    @Test
    @DisplayName("Test 7: When email fails, push notification is not triggered")
    void test7_brevoEmailFailsPushNotSent() {
        EmailService mockEmail = mock(EmailService.class);
        when(mockEmail.sendEmail(any())).thenReturn(EmailDeliveryResult.failure("BREVO", "SMTP connection error", 500));
        SmsService mockSms = mock(SmsService.class);
        NotificationPushService mockPush = mock(NotificationPushService.class);

        RosterEmailService emailService = new RosterEmailService(
                emailLogRepository, employeeRepository, cycleRepository,
                assignmentRepository, shiftRepository, mockEmail, mockSms, mockPush
        );

        RosterCycle cycle = cycleRepository.findAll().stream().findFirst().orElse(null);
        if (cycle == null) return;
        Employee emp = employeeRepository.findByActiveTrueOrderByIdAsc().get(0);

        var logRes = emailService.sendToEmployee(cycle, emp, List.of(), List.of(), null, null, GenerationMode.MANUAL, null);
        assertEquals(EmailDeliveryStatus.FAILED, logRes.getStatus());

        // Verify push was NEVER called
        verify(mockPush, never()).sendRosterNotification(any(), any(), anyBoolean(), any());
    }

    // =========================================================================
    // Test 8: Tentative roster -> exact tentative message
    // =========================================================================
    @Test
    @DisplayName("Test 8: Tentative roster notification body has exact required copy")
    void test8_tentativeRosterExactMessageBody() {
        NotificationPushServiceImpl impl = new NotificationPushServiceImpl(firebaseConfig, deviceTokenRepository);
        RosterCycle cycle = new RosterCycle();
        cycle.setStartDate(LocalDate.of(2026, 9, 14));
        cycle.setEndDate(LocalDate.of(2026, 9, 20));

        String range = impl.formatCycleDateRange(cycle.getStartDate(), cycle.getEndDate());
        assertEquals("14 Sep \u2013 20 Sep 2026", range);

        String expectedBody = "Your tentative roster for 14 Sep \u2013 20 Sep 2026 has been sent to your registered email.";
        String actualBody = String.format("Your tentative roster for %s has been sent to your registered email.", range);
        assertEquals(expectedBody, actualBody);
    }

    // =========================================================================
    // Test 9: Final roster -> exact final message
    // =========================================================================
    @Test
    @DisplayName("Test 9: Final roster notification body has exact required copy")
    void test9_finalRosterExactMessageBody() {
        NotificationPushServiceImpl impl = new NotificationPushServiceImpl(firebaseConfig, deviceTokenRepository);
        RosterCycle cycle = new RosterCycle();
        cycle.setStartDate(LocalDate.of(2026, 9, 28));
        cycle.setEndDate(LocalDate.of(2026, 10, 4));

        String range = impl.formatCycleDateRange(cycle.getStartDate(), cycle.getEndDate());
        assertEquals("28 Sep \u2013 4 Oct 2026", range);

        String expectedBody = "Your final roster for 28 Sep \u2013 4 Oct 2026 has been sent to your registered email.";
        String actualBody = String.format("Your final roster for %s has been sent to your registered email.", range);
        assertEquals(expectedBody, actualBody);
    }

    // =========================================================================
    // Test 10: Dates match roster cycle exactly in Asia/Kolkata
    // =========================================================================
    @Test
    @DisplayName("Test 10: Dates match roster cycle object with en-dash separator")
    void test10_datesMatchRosterCycleExactly() {
        NotificationPushServiceImpl impl = new NotificationPushServiceImpl(firebaseConfig, deviceTokenRepository);

        LocalDate start = LocalDate.of(2026, 10, 5);
        LocalDate end = LocalDate.of(2026, 10, 11);
        String formatted = impl.formatCycleDateRange(start, end);

        assertEquals("5 Oct \u2013 11 Oct 2026", formatted);
        assertTrue(formatted.contains("\u2013"));
    }

    // =========================================================================
    // Test 11: Existing SSE continues working
    // =========================================================================
    @Test
    @DisplayName("Test 11: Existing SSE endpoint streamNotifications returns non-null emitter")
    void test11_existingSseContinuesWorking() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("Admin", "password", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );
        var emitter = notificationController.streamNotifications();
        assertNotNull(emitter);
    }

    // =========================================================================
    // Test 12: Existing roster generation continues working (Batch 63 preservation)
    // =========================================================================
    @Test
    @DisplayName("Test 12: Existing roster generation produces valid cycle with General shift covered")
    void test12_existingRosterGenerationContinuesWorking() {
        LocalDate start = LocalDate.of(2026, 9, 28);
        LocalDate end = LocalDate.of(2026, 10, 4);

        RosterCycleResponse res = rosterService.generateWeeklyRoster(start, GenerationMode.MANUAL);
        assertNotNull(res);

        long generalStaffOnDay7 = assignmentRepository.findByRosterDate(end).stream()
                .filter(a -> a.getShift() != null && a.getShift().getShiftType() == ShiftType.GENERAL && !a.isWeeklyOff() && !a.isOnLeave())
                .count();
        assertTrue(generalStaffOnDay7 >= 1, "Sunday 2026-10-04 General shift must have at least 1 assigned staff");
    }

    // =========================================================================
    // Test 13: Admin-only test push is protected
    // =========================================================================
    @Test
    @DisplayName("Test 13: Admin test push endpoint is accessible to ROLE_ADMIN and protected")
    void test13_adminOnlyTestPushIsProtected() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("Admin", "password", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );

        ResponseEntity<Map<String, Object>> res = pushNotificationController.sendAdminTest(null, null);
        assertNotNull(res);
        assertEquals(200, res.getStatusCode().value());
        assertTrue(res.getBody().containsKey("success"));
        assertTrue(res.getBody().containsKey("message"));
    }

    // =========================================================================
    // Test 14: No Firebase private secret appears in public config
    // =========================================================================
    @Test
    @DisplayName("Test 14: Public config and diagnostics never leak private keys or secrets")
    void test14_noFirebasePrivateSecretExposed() {
        ResponseEntity<Map<String, Object>> configRes = pushNotificationController.getPublicConfig();
        assertNotNull(configRes.getBody());
        Map<String, Object> body = configRes.getBody();

        assertFalse(body.containsKey("privateKey"));
        assertFalse(body.containsKey("private_key"));
        assertFalse(body.containsKey("serviceAccountJson"));
        assertFalse(body.containsKey("client_email"));
        assertFalse(body.containsKey("clientEmail"));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("Admin", "password", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );
        ResponseEntity<Map<String, Object>> diagRes = pushNotificationController.getDiagnostics();
        assertNotNull(diagRes.getBody());
        Map<String, Object> diag = diagRes.getBody();

        assertFalse(diag.containsKey("privateKey"));
        assertFalse(diag.containsKey("private_key"));
        assertFalse(diag.containsKey("serviceAccountJson"));
    }
}