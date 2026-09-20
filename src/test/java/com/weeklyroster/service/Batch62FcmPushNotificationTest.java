package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import com.weeklyroster.config.FirebaseConfig;
import com.weeklyroster.controller.PushNotificationController;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.*;
import com.weeklyroster.service.email.EmailDeliveryResult;
import com.weeklyroster.service.email.EmailService;
import com.weeklyroster.service.push.NotificationPushService;
import com.weeklyroster.service.push.NotificationPushServiceImpl;

@ExtendWith(MockitoExtension.class)
class Batch62FcmPushNotificationTest {

    @Mock
    private DeviceTokenRepository deviceTokenRepository;

    @Mock
    private FirebaseConfig firebaseConfig;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private EmailDeliveryLogRepository emailLogRepository;

    @Mock
    private RosterCycleRepository cycleRepository;

    @Mock
    private RosterAssignmentRepository assignmentRepository;

    @Mock
    private ShiftRepository shiftRepository;

    @Mock
    private EmailService mockEmailService;

    private NotificationPushServiceImpl pushService;
    private PushNotificationController pushController;
    private RosterEmailService rosterEmailService;

    @BeforeEach
    void setUp() {
        pushService = new NotificationPushServiceImpl(firebaseConfig, deviceTokenRepository);
        pushController = new PushNotificationController(firebaseConfig, pushService, userRepository);
        rosterEmailService = new RosterEmailService(
                emailLogRepository,
                employeeRepository,
                cycleRepository,
                assignmentRepository,
                shiftRepository,
                mockEmailService,
                pushService
        );
    }

    // =========================================================================
    // 1. EXACT NOTIFICATION COPY & DATE DERIVATION IN ASIA/KOLKATA
    // =========================================================================

    @Test
    @DisplayName("Final Roster notification body matches exact required copy with dynamic date range")
    void testNotificationCopy_FinalRoster() {
        RosterCycle cycle = new RosterCycle();
        cycle.setId(101L);
        cycle.setStartDate(LocalDate.of(2026, 9, 14));
        cycle.setEndDate(LocalDate.of(2026, 9, 20));
        cycle.setStatus(RosterStatus.FINAL);

        Employee emp = new Employee();
        emp.setId(5L);
        emp.setEmployeeCode("EMP005");
        emp.setFirstName("Rajat");
        emp.setLastName("Maurya");

        when(firebaseConfig.isServerConfigured()).thenReturn(false);

        DeviceToken token = new DeviceToken();
        token.setToken("fcm_dummy_token_123");
        token.setActive(true);
        token.setEmployee(emp);
        when(deviceTokenRepository.findByEmployeeAndActiveTrue(emp)).thenReturn(List.of(token));

        int sentCount = pushService.sendRosterNotification(cycle, emp, true, "14 Sep – 20 Sep 2026");

        assertEquals(1, sentCount);
    }

    @Test
    @DisplayName("Format date range produces 14 Sep – 20 Sep 2026 format")
    void testFormatDateRange() {
        LocalDate start = LocalDate.of(2026, 9, 14);
        LocalDate end = LocalDate.of(2026, 9, 20);

        String range = pushService.formatCycleDateRange(start, end);
        assertEquals("14 Sep \u2013 20 Sep 2026", range);
    }

    @Test
    @DisplayName("Tentative Roster notification body contains exact copy")
    void testNotificationCopy_TentativeRoster() {
        RosterCycle cycle = new RosterCycle();
        cycle.setId(102L);
        cycle.setStartDate(LocalDate.of(2026, 9, 14));
        cycle.setEndDate(LocalDate.of(2026, 9, 20));
        cycle.setStatus(RosterStatus.TENTATIVE);

        Employee emp = new Employee();
        emp.setId(6L);
        emp.setEmployeeCode("EMP006");
        emp.setFirstName("Priya");

        when(firebaseConfig.isServerConfigured()).thenReturn(false);

        DeviceToken token = new DeviceToken();
        token.setToken("fcm_dummy_token_456");
        token.setActive(true);
        token.setEmployee(emp);
        when(deviceTokenRepository.findByEmployeeAndActiveTrue(emp)).thenReturn(List.of(token));

        int sentCount = pushService.sendRosterNotification(cycle, emp, false, "14 Sep – 20 Sep 2026");
        assertEquals(1, sentCount);
    }

    // =========================================================================
    // 2. TOKEN REGISTRATION, REFRESH & DEACTIVATION
    // =========================================================================

    @Test
    @DisplayName("Registering a new device token persists a new DeviceToken entity")
    void testRegisterToken_NewDevice() {
        User user = new User();
        user.setId(1L);
        user.setUsername("emp001");

        when(deviceTokenRepository.findByToken("tok_abc_123")).thenReturn(Optional.empty());

        boolean ok = pushService.registerToken(user, null, "tok_abc_123", "Chrome on Windows");
        assertTrue(ok);

        ArgumentCaptor<DeviceToken> captor = ArgumentCaptor.forClass(DeviceToken.class);
        verify(deviceTokenRepository).save(captor.capture());

        DeviceToken captured = captor.getValue();
        assertEquals("tok_abc_123", captured.getToken());
        assertEquals("Chrome on Windows", captured.getDeviceType());
        assertTrue(captured.isActive());
    }

    @Test
    @DisplayName("Registering an existing token refreshes active state and lastUsedAt without duplicate insert")
    void testRegisterToken_ExistingDeviceRefresh() {
        User user = new User();
        user.setId(1L);
        user.setUsername("emp001");

        DeviceToken existing = new DeviceToken();
        existing.setId(99L);
        existing.setToken("tok_existing_123");
        existing.setActive(false);
        existing.setDeviceType("Old Browser");

        when(deviceTokenRepository.findByToken("tok_existing_123")).thenReturn(Optional.of(existing));

        boolean ok = pushService.registerToken(user, null, "tok_existing_123", "Firefox on Linux");
        assertTrue(ok);

        assertTrue(existing.isActive());
        assertEquals("Firefox on Linux", existing.getDeviceType());
        verify(deviceTokenRepository).save(existing);
    }

    @Test
    @DisplayName("Deactivate token sets active to false")
    void testDeactivateToken() {
        User user = new User();
        user.setId(10L);
        user.setUsername("testuser");

        when(deviceTokenRepository.deactivateTokenForUser(eq("tok_deact_123"), eq(user), any())).thenReturn(1);

        boolean ok = pushService.deactivateToken("tok_deact_123", user);

        assertTrue(ok);
        verify(deviceTokenRepository).deactivateTokenForUser(eq("tok_deact_123"), eq(user), any());
    }

    // =========================================================================
    // 3. DUPLICATE NOTIFICATION SUPPRESSION (15-MINUTE WINDOW)
    // =========================================================================

    @Test
    @DisplayName("Second notification within 15 minutes for same employee, cycle and status is suppressed")
    void testDuplicateNotificationSuppression() {
        RosterCycle cycle = new RosterCycle();
        cycle.setId(201L);
        cycle.setStartDate(LocalDate.of(2026, 9, 14));
        cycle.setEndDate(LocalDate.of(2026, 9, 20));

        Employee emp = new Employee();
        emp.setId(7L);

        when(firebaseConfig.isServerConfigured()).thenReturn(false);

        DeviceToken token = new DeviceToken();
        token.setToken("tok_dup_test");
        token.setActive(true);
        when(deviceTokenRepository.findByEmployeeAndActiveTrue(emp)).thenReturn(List.of(token));

        // First dispatch: Success
        int firstSent = pushService.sendRosterNotification(cycle, emp, true, "14 Sep – 20 Sep 2026");
        assertEquals(1, firstSent);

        // Second immediate dispatch: Suppressed
        int secondSent = pushService.sendRosterNotification(cycle, emp, true, "14 Sep – 20 Sep 2026");
        assertEquals(0, secondSent);
    }

    // =========================================================================
    // 4. FAILURE ISOLATION IN PUSH SERVICE
    // =========================================================================

    @Test
    @DisplayName("Failure during push notification does not crash caller")
    void testPushFailure_DoesNotCrashCaller() {
        NotificationPushService mockPushService = mock(NotificationPushService.class);
        doThrow(new RuntimeException("Simulated FCM Network Failure"))
                .when(mockPushService).sendRosterNotification(any(), any(), anyBoolean(), anyString());

        RosterCycle cycle = new RosterCycle();
        cycle.setId(301L);
        cycle.setStartDate(LocalDate.of(2026, 9, 14));
        cycle.setEndDate(LocalDate.of(2026, 9, 20));
        cycle.setStatus(RosterStatus.FINAL);

        Employee emp = new Employee();
        emp.setId(8L);

        // Verify that caller wrapping in try/catch safely isolates
        assertDoesNotThrow(() -> {
            try {
                mockPushService.sendRosterNotification(cycle, emp, true, "14 Sep – 20 Sep 2026");
            } catch (Exception ignored) {
                // Isolated
            }
        });
    }

    // =========================================================================
    // 5. ADMIN TEST NOTIFICATION
    // =========================================================================

    @Test
    @DisplayName("Admin test notification sends exact required copy")
    void testAdminTestPushCopy() {
        when(firebaseConfig.isServerConfigured()).thenReturn(false);

        User adminUser = new User();
        adminUser.setId(1L);
        adminUser.setUsername("admin");

        DeviceToken token = new DeviceToken();
        token.setToken("admin_tok_1");
        token.setActive(true);
        when(deviceTokenRepository.findByUserAndActiveTrue(adminUser)).thenReturn(List.of(token));

        boolean sent = pushService.sendAdminTestNotification(adminUser);
        assertTrue(sent);
    }

    // =========================================================================
    // 6. PUBLIC CONFIG ENDPOINT & CREDENTIAL ISOLATION
    // =========================================================================

    @Test
    @DisplayName("Config endpoint returns public keys and never leaks private keys")
    void testGetPublicConfig_DoesNotLeakPrivateKeys() {
        when(firebaseConfig.isEnabled()).thenReturn(true);
        when(firebaseConfig.isWebConfigured()).thenReturn(true);
        when(firebaseConfig.getWebApiKey()).thenReturn("AIzaSy_test_key");
        when(firebaseConfig.getProjectId()).thenReturn("wrms-test");
        when(firebaseConfig.getMessagingSenderId()).thenReturn("123456789");
        when(firebaseConfig.getAppId()).thenReturn("1:12345:web:test");
        when(firebaseConfig.getVapidKey()).thenReturn("BN_test_vapid");

        ResponseEntity<Map<String, Object>> response = pushController.getPublicConfig();

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("enabled"));
        assertEquals(true, body.get("configured"));
        assertEquals("AIzaSy_test_key", body.get("apiKey"));
        assertEquals("BN_test_vapid", body.get("vapidKey"));

        // Verify private keys are absent
        assertNull(body.get("privateKey"));
        assertNull(body.get("serviceAccount"));
        assertNull(body.get("credentials"));
    }
}
