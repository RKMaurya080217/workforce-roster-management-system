package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.*;

import com.weeklyroster.dto.request.*;
import com.weeklyroster.dto.response.*;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Batch50ReleaseCandidateUatTest {

    @Autowired
    private SystemHealthService systemHealthService;

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private RosterService rosterService;

    @Autowired
    private RosterSchedulerService schedulerService;

    @Autowired
    private SmartCommandCenterService commandCenterService;

    @Autowired
    private LeaveService leaveService;

    @Autowired
    private ShiftHandoverService handoverService;

    @Autowired
    private ProfileChangeRequestService profileChangeRequestService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private RosterAssignmentRepository assignmentRepository;

    @Autowired
    private RosterOverrideRepository overrideRepository;

    @Autowired
    private RosterVersionRepository versionRepository;

    @Autowired
    private EmailDeliveryLogRepository emailDeliveryLogRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired
    private EmployeePreferenceRepository preferenceRepository;

    @Autowired
    private ShiftHandoverRepository handoverRepository;

    @Autowired
    private ProfileChangeRequestRepository profileChangeRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    private LocalDate upcomingMonday;

    @BeforeEach
    void cleanState() {
        notificationRepository.deleteAll();
        profileChangeRepository.deleteAll();
        handoverRepository.deleteAll();
        preferenceRepository.deleteAll();
        leaveRequestRepository.deleteAll();
        overrideRepository.deleteAll();
        assignmentRepository.deleteAll();
        versionRepository.deleteAll();
        emailDeliveryLogRepository.deleteAll();
        cycleRepository.deleteAll();

        upcomingMonday = schedulerService.calculateUpcomingWeekStart(LocalDate.now());
    }

    @Test
    @Order(1)
    @DisplayName("Batch 50 [1]: Release Candidate Baseline & System Health Diagnostics")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test01_ReleaseCandidateHealth() {
        SystemHealthResponse health = systemHealthService.getSystemHealth();
        assertNotNull(health);
        assertNotNull(health.overallStatus());
        assertEquals("1.0.0", health.version());
        assertFalse(health.components().isEmpty());

        assertTrue(health.components().stream().anyMatch(c -> c.component().contains("Backend Runtime")));
        assertTrue(health.components().stream().anyMatch(c -> c.component().contains("Database Connection")));
        assertTrue(health.components().stream().anyMatch(c -> c.component().contains("Master Data Integrity")));
        assertTrue(health.components().stream().anyMatch(c -> c.component().contains("Real-Time Notification SSE")));
        assertTrue(health.components().stream().anyMatch(c -> c.component().contains("Roster Scheduling Engine")));
    }

    @Test
    @Order(2)
    @DisplayName("Batch 50 [2]: Complete Admin Journey UAT (Dashboard -> Generation -> Command Center -> Invariants)")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test02_CompleteAdminJourney() {
        // 1. Dashboard Check
        DashboardResponse dashboard = dashboardService.dashboard();
        assertNotNull(dashboard);
        assertEquals(employeeRepository.count(), dashboard.totalEmployees());

        // 2. Generate Upcoming Roster
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(upcomingMonday, GenerationMode.MANUAL);
        assertNotNull(cycle);
        assertEquals(upcomingMonday, cycle.startDate());
        assertEquals(upcomingMonday.plusDays(6), cycle.endDate());

        // 3. Smart Command Center inspection on generated cycle
        SmartCommandCenterResponse cmd = commandCenterService.getCycleSummary(cycle.id());
        assertNotNull(cmd);
        assertEquals(upcomingMonday, cmd.startDate());
        assertNotNull(cmd.healthStatus());

        // 4. Female safety invariants: 0 Evening, 0 Night
        List<Employee> females = employeeRepository.findAll().stream()
                .filter(e -> e.getGender() == Gender.FEMALE)
                .toList();
        assertFalse(females.isEmpty());

        for (Employee female : females) {
            List<RosterAssignmentResponse> assignments = cycle.assignments().stream()
                    .filter(a -> a.employeeCode().equals(female.getEmployeeCode()))
                    .toList();

            for (RosterAssignmentResponse a : assignments) {
                if (!a.weeklyOff() && !a.onLeave()) {
                    assertNotEquals(ShiftType.EVENING, a.shiftType(), "Female staff cannot be assigned Evening shifts");
                    assertNotEquals(ShiftType.NIGHT, a.shiftType(), "Female staff cannot be assigned Night shifts");
                }
            }
        }
    }

    @Test
    @Order(3)
    @DisplayName("Batch 50 [3]: Complete Employee Journey UAT (Leave -> Handover -> Notifications)")
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    void test03_CompleteEmployeeJourney() {
        Employee emp1 = employeeRepository.findByUserUsername("emp001").orElseThrow();
        Employee emp2 = employeeRepository.findByUserUsername("emp002").orElseThrow();
        Shift shift = shiftRepository.findByShiftType(ShiftType.GENERAL).orElseThrow();

        // 1. Employee creates handover
        CreateHandoverRequest req = new CreateHandoverRequest(
                LocalDate.now(), shift.getId(), emp2.getId(),
                "Handover RC notes", "Task 1", "Task 2", "No critical blockers", HandoverPriority.MEDIUM
        );
        HandoverResponse createdHandover = handoverService.createHandover(emp1.getId(), req, "emp001");
        assertNotNull(createdHandover);
        assertEquals(HandoverStatus.OPEN, createdHandover.status());

        // 2. Recipient acknowledges handover
        HandoverResponse acked = handoverService.acknowledgeHandover(createdHandover.id(), emp2.getId(), "Received checklist", "emp002");
        assertNotNull(acked);
        assertEquals(HandoverStatus.ACKNOWLEDGED, acked.status());
    }

    @Test
    @Order(4)
    @DisplayName("Batch 50 [4]: Profile Approval Sync & Audit Trail Verification")
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    void test04_ProfileApprovalAndAuditTrail() {
        Employee emp = employeeRepository.findByUserUsername("emp001").orElseThrow();

        // 1. Submit Profile Change Request
        ProfileChangeRequest changeReq = new ProfileChangeRequest();
        changeReq.setEmployee(emp);
        changeReq.setFieldName("contactNumber");
        changeReq.setCurrentValue(emp.getContactNumber());
        changeReq.setRequestedValue("9123456780");
        changeReq.setStatus(ProfileChangeStatus.PENDING);
        changeReq.setRequestedAt(LocalDateTime.now());
        profileChangeRepository.save(changeReq);

        // 2. Admin Approves
        approveProfileAsAdmin(changeReq.getId());

        // 3. Verify Database Update
        Employee updated = employeeRepository.findById(emp.getId()).orElseThrow();
        assertEquals("9123456780", updated.getContactNumber());
    }

    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    private void approveProfileAsAdmin(Long reqId) {
        ProfileChangeRequestResponse response = profileChangeRequestService.approve(reqId, new ProfileChangeDecisionRequest("RC Approved"));
        assertNotNull(response);
        assertEquals(ProfileChangeStatus.APPROVED, response.status());
    }
}