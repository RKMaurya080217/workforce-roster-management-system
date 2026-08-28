package com.weeklyroster.service;

import com.weeklyroster.dto.request.CreateHandoverRequest;
import com.weeklyroster.dto.request.UnlockRosterRequest;
import com.weeklyroster.dto.response.*;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class Batch45ProjectStabilizationTest {

    @Autowired
    private RosterService rosterService;

    @Autowired
    private RosterSchedulerService schedulerService;

    @Autowired
    private SmartCommandCenterService commandCenterService;

    @Autowired
    private SseEmitterService sseEmitterService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private ShiftHandoverService handoverService;

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private RosterEmailService emailService;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private RosterAssignmentRepository assignmentRepository;

    @Autowired
    private RosterVersionRepository versionRepository;

    @Autowired
    private EmailDeliveryLogRepository emailDeliveryLogRepository;

    @Autowired
    private RosterOverrideRepository overrideRepository;

    @Autowired
    private ShiftHandoverRepository handoverRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    private LocalDate upcomingMonday;

    @BeforeEach
    void setUp() {
        handoverRepository.deleteAll();
        notificationRepository.deleteAll();
        overrideRepository.deleteAll();
        assignmentRepository.deleteAll();
        versionRepository.deleteAll();
        emailDeliveryLogRepository.deleteAll();
        cycleRepository.deleteAll();

        upcomingMonday = schedulerService.calculateUpcomingWeekStart(LocalDate.now());
    }

    @Test
    @DisplayName("Batch 45 [1]: Real-Time SSE Notification Subscription & Event Dispatch")
    void test01_RealTimeSseSubscriptionAndDispatch() {
        // 1. Subscribe user to SSE stream
        SseEmitter emitter = sseEmitterService.subscribe("emp001");
        assertNotNull(emitter);
        assertTrue(sseEmitterService.getActiveConnectionCount() >= 1);

        // 2. Dispatch real-time notification
        Notification notif = notificationService.createNotification(
                "emp001", 1L, "Shift Handover Note", "New handover assigned to you",
                NotificationType.HANDOVER_CREATED, "handovers", 101L
        );
        assertNotNull(notif);
        assertEquals("Shift Handover Note", notif.getTitle());

        // 3. Heartbeat ping execution
        assertDoesNotThrow(() -> sseEmitterService.sendHeartbeat());
    }

    @Test
    @DisplayName("Batch 45 [2]: Smart Roster Command Center Summary & Lifecycle Actions")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test02_SmartCommandCenterSummaryAndActions() {
        // 1. Generate upcoming roster via Command Center action
        SmartCommandCenterResponse summary = commandCenterService.generateUpcomingRoster();
        assertNotNull(summary);
        assertEquals(upcomingMonday, summary.startDate());
        assertNotNull(summary.lifecycleStage());
        assertNotNull(summary.smartSummary());
        assertTrue(summary.coveragePercentage() >= 90.0);

        // 2. Query cycle summary directly
        SmartCommandCenterResponse cycleSummary = commandCenterService.getCycleSummary(summary.cycleId());
        assertNotNull(cycleSummary);
        assertEquals(summary.cycleId(), cycleSummary.cycleId());

        // 3. Publish cycle via Command Center action
        SmartCommandCenterResponse published = commandCenterService.publishCycle(summary.cycleId());
        assertNotNull(published);
        assertEquals(RosterStatus.PUBLISHED, published.status());

        // 4. Lock cycle via Command Center action
        SmartCommandCenterResponse locked = commandCenterService.lockCycle(summary.cycleId());
        assertNotNull(locked);
        assertEquals(RosterStatus.LOCKED, locked.status());
    }

    @Test
    @DisplayName("Batch 45 [3]: Shift Handover Lifecycle & Real-Time Flow")
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    void test03_ShiftHandoverLifecycle() {
        Employee creator = employeeRepository.findByUserUsername("emp001").orElseThrow();
        Employee recipient = employeeRepository.findByUserUsername("emp002").orElseThrow();
        Shift morningShift = shiftRepository.findByShiftType(ShiftType.MORNING).orElseThrow();

        // 1. Create Shift Handover Note
        CreateHandoverRequest createReq = new CreateHandoverRequest(
                LocalDate.now(), morningShift.getId(), recipient.getId(),
                "Critical Server Alert", "Monitor disk IO", "Restart indexing job",
                "High CPU load observed on database server during morning shift",
                HandoverPriority.HIGH
        );

        HandoverResponse created = handoverService.createHandover(creator.getId(), createReq, "emp001");
        assertNotNull(created);
        assertEquals("Critical Server Alert", created.summary());
        assertEquals(HandoverStatus.OPEN, created.status());

        // 2. Recipient views incoming handovers
        List<HandoverResponse> incoming = handoverService.getIncomingHandovers(recipient.getId());
        assertFalse(incoming.isEmpty());
        assertTrue(incoming.stream().anyMatch(h -> h.id().equals(created.id())));

        // 3. Recipient acknowledges handover
        HandoverResponse acked = handoverService.acknowledgeHandover(created.id(), recipient.getId(), "Checked and monitoring", "emp002");
        assertNotNull(acked);
        assertEquals(HandoverStatus.ACKNOWLEDGED, acked.status());
    }

    @Test
    @DisplayName("Batch 45 [4]: Admin Dashboard Dynamic Metric Precision")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test04_AdminDashboardDynamicMetrics() {
        DashboardResponse dashboard = dashboardService.dashboard();
        assertNotNull(dashboard);
        assertTrue(dashboard.totalEmployees() >= 7);
        assertTrue(dashboard.activeEmployees() >= 7);

        DashboardDetailResponse details = dashboardService.dashboardDetails();
        assertNotNull(details);
        assertNotNull(details.activeEmployees());
        assertNotNull(details.summary());
    }

    @Test
    @DisplayName("Batch 45 [5]: Email SMTP Test Diagnostic Endpoint Safety")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test05_EmailSmtpTestDiagnostic() {
        Map<String, Object> result = emailService.sendTestEmail("test@example.com");
        assertNotNull(result);
        assertTrue(result.containsKey("status"));
        assertTrue(result.containsKey("recipient"));
    }
}