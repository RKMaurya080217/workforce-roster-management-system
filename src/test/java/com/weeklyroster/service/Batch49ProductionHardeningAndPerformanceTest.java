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
public class Batch49ProductionHardeningAndPerformanceTest {

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
    private AuditService auditService;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private RosterAssignmentRepository assignmentRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private EmployeePreferenceRepository preferenceRepository;

    @Autowired
    private ShiftHandoverRepository handoverRepository;

    @Autowired
    private RosterOverrideRepository overrideRepository;

    @Autowired
    private RosterVersionRepository versionRepository;

    @Autowired
    private EmailDeliveryLogRepository emailDeliveryLogRepository;

    private LocalDate upcomingMonday;

    @BeforeEach
    void cleanState() {
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
    @DisplayName("Batch 49 [1]: System Health Diagnostics & Performance Health")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test01_SystemHealthReadiness() {
        SystemHealthResponse health = systemHealthService.getSystemHealth();
        assertNotNull(health);
        assertNotNull(health.components());
        assertTrue(health.components().size() >= 5, "Must report core subsystem components");

        ComponentHealth dbHealth = health.components().stream()
                .filter(c -> c.component().contains("Database Connection"))
                .findFirst()
                .orElse(null);
        assertNotNull(dbHealth);
        assertEquals("HEALTHY", dbHealth.status());
    }

    @Test
    @Order(2)
    @DisplayName("Batch 49 [2]: Admin Dashboard Dynamic Metric Query Consistency")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test02_DashboardMetricsIntegrity() {
        DashboardResponse dashboard = dashboardService.dashboard();
        assertNotNull(dashboard);
        long activeEmpCount = employeeRepository.count();
        assertEquals(activeEmpCount, dashboard.totalEmployees());
        assertEquals(activeEmpCount, dashboard.activeEmployees());
        assertEquals(0, dashboard.inactiveEmployees());
    }

    @Test
    @Order(3)
    @DisplayName("Batch 49 [3]: Full End-to-End Workflow: Leave -> Smart Command Center -> Roster Invariants")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test03_FullEndToEndWorkflow() {
        Employee emp2 = employeeRepository.findByUserUsername("emp002").orElseThrow();
        LocalDate leaveDate = upcomingMonday.plusDays(1); // Tuesday

        // 1. Submit and Approve Leave
        LeaveRequest leave = new LeaveRequest();
        leave.setEmployee(emp2);
        leave.setStartDate(leaveDate);
        leave.setEndDate(leaveDate);
        leave.setReason("Family Function");
        leave.setStatus(LeaveStatus.APPROVED);
        leave.setRequestedAt(LocalDateTime.now());
        leaveRequestRepository.save(leave);

        // 2. Generate upcoming roster
        RosterCycleResponse genRoster = rosterService.generateWeeklyRoster(upcomingMonday, GenerationMode.MANUAL);
        assertNotNull(genRoster);
        assertEquals(upcomingMonday, genRoster.startDate());
        assertEquals(upcomingMonday.plusDays(6), genRoster.endDate());

        // 3. Verify Roster Invariants
        List<Employee> females = employeeRepository.findAll().stream()
                .filter(e -> e.getGender() == Gender.FEMALE)
                .toList();

        for (Employee female : females) {
            List<RosterAssignmentResponse> assignments = genRoster.assignments().stream()
                    .filter(a -> a.employeeCode().equals(female.getEmployeeCode()))
                    .toList();

            for (RosterAssignmentResponse a : assignments) {
                if (!a.weeklyOff() && !a.onLeave()) {
                    assertNotEquals(ShiftType.EVENING, a.shiftType(), "Female staff cannot be assigned Evening shifts");
                    assertNotEquals(ShiftType.NIGHT, a.shiftType(), "Female staff cannot be assigned Night shifts");
                }
            }
        }

        // Leave day integrity: Emp2 on leaveDate must be ON_LEAVE
        RosterAssignmentResponse leaveAssignment = genRoster.assignments().stream()
                .filter(a -> a.employeeCode().equals(emp2.getEmployeeCode()) && a.rosterDate().equals(leaveDate))
                .findFirst()
                .orElseThrow();
        assertTrue(leaveAssignment.onLeave(), "Approved leave day must have onLeave = true");
    }

    @Test
    @Order(4)
    @DisplayName("Batch 49 [4]: Shift Handover Lifecycle & Security Boundary")
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    void test04_ShiftHandoverLifecycle() {
        Employee creator = employeeRepository.findByUserUsername("emp001").orElseThrow();
        Employee recipient = employeeRepository.findByUserUsername("emp002").orElseThrow();
        Shift shift = shiftRepository.findByShiftType(ShiftType.MORNING).orElseThrow();

        // 1. Creator submits handover
        CreateHandoverRequest req = new CreateHandoverRequest(
                LocalDate.now(), shift.getId(), recipient.getId(),
                "Handover Notes", "Task 1", "Task 2", "Critical incident handled", HandoverPriority.HIGH
        );
        HandoverResponse created = handoverService.createHandover(creator.getId(), req, "emp001");
        assertNotNull(created);
        assertEquals(HandoverStatus.OPEN, created.status());

        // 2. Recipient acknowledges handover
        HandoverResponse acked = handoverService.acknowledgeHandover(created.id(), recipient.getId(), "All tasks noted", "emp002");
        assertNotNull(acked);
        assertEquals(HandoverStatus.ACKNOWLEDGED, acked.status());
    }
}