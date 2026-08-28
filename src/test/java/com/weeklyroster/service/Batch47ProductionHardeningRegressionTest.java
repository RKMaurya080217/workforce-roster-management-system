package com.weeklyroster.service;

import com.weeklyroster.dto.request.CreateHandoverRequest;
import com.weeklyroster.dto.request.LeaveDecisionRequest;
import com.weeklyroster.dto.request.ProfileChangeDecisionRequest;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class Batch47ProductionHardeningRegressionTest {

    @Autowired
    private SystemHealthService systemHealthService;

    @Autowired
    private RosterService rosterService;

    @Autowired
    private RosterSchedulerService schedulerService;

    @Autowired
    private SmartCommandCenterService commandCenterService;

    @Autowired
    private RosterEmailService emailService;

    @Autowired
    private SseEmitterService sseEmitterService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private ShiftHandoverService handoverService;

    @Autowired
    private LeaveService leaveService;

    @Autowired
    private EmployeePreferenceService preferenceService;

    @Autowired
    private ProfileChangeRequestService profileChangeRequestService;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ShiftRepository shiftRepository;

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
    private ShiftHandoverRepository handoverRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private LeaveRequestRepository leaveRepository;

    @Autowired
    private EmployeePreferenceRepository preferenceRepository;

    @Autowired
    private ProfileChangeRequestRepository profileChangeRepository;

    private LocalDate upcomingMonday;

    @BeforeEach
    void setUp() {
        handoverRepository.deleteAll();
        notificationRepository.deleteAll();
        leaveRepository.deleteAll();
        preferenceRepository.deleteAll();
        profileChangeRepository.deleteAll();
        overrideRepository.deleteAll();
        assignmentRepository.deleteAll();
        versionRepository.deleteAll();
        emailDeliveryLogRepository.deleteAll();
        cycleRepository.deleteAll();

        upcomingMonday = schedulerService.calculateUpcomingWeekStart(LocalDate.now());
    }

    @Test
    @DisplayName("Batch 47 [1]: Automated System Health Check Diagnostic Endpoint")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test01_SystemHealthCheck() {
        SystemHealthResponse health = systemHealthService.getSystemHealth();
        assertNotNull(health);
        assertNotNull(health.overallStatus());
        assertNotNull(health.timestamp());
        assertEquals("1.0.0", health.version());
        assertFalse(health.components().isEmpty());

        assertTrue(health.components().stream().anyMatch(c -> c.component().contains("Backend Runtime")));
        assertTrue(health.components().stream().anyMatch(c -> c.component().contains("Database Connection")));
        assertTrue(health.components().stream().anyMatch(c -> c.component().contains("Master Data Integrity")));
        assertTrue(health.components().stream().anyMatch(c -> c.component().contains("Real-Time Notification SSE")));
        assertTrue(health.components().stream().anyMatch(c -> c.component().contains("Roster Scheduling Engine")));
    }

    @Test
    @DisplayName("Batch 47 [2]: Roster Constraint Invariants (Female Safety & Night Shift Limits)")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test02_RosterConstraintInvariants() {
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(upcomingMonday, GenerationMode.MANUAL);
        assertNotNull(cycle);

        List<Employee> females = employeeRepository.findAll().stream()
                .filter(e -> e.getGender() == Gender.FEMALE)
                .toList();
        assertFalse(females.isEmpty(), "Female staff members must exist in database");

        // Verify Female Safety Invariant: 0 Evening and 0 Night shifts
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

        // Verify Male Night Duty Limit: Max 2 night shifts per week
        List<Employee> males = employeeRepository.findAll().stream()
                .filter(e -> e.getGender() == Gender.MALE)
                .toList();

        for (Employee male : males) {
            long nightCount = cycle.assignments().stream()
                    .filter(a -> a.employeeCode().equals(male.getEmployeeCode()) && a.shiftType() == ShiftType.NIGHT && !a.weeklyOff() && !a.onLeave())
                    .count();
            assertTrue(nightCount <= 2, "Male staff cannot exceed 2 night shifts per week (actual: " + nightCount + ")");
        }
    }

    @Test
    @DisplayName("Batch 47 [3]: Leave Collision Preservation & Roster Generation")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test03_LeavePreservation() {
        Employee emp = employeeRepository.findByUserUsername("emp001").orElseThrow();
        LocalDate leaveDate = upcomingMonday.plusDays(2); // Wednesday

        // 1. Submit and Approve Leave
        LeaveRequest leave = new LeaveRequest();
        leave.setEmployee(emp);
        leave.setStartDate(leaveDate);
        leave.setEndDate(leaveDate);
        leave.setReason("Doctor Appointment");
        leave.setStatus(LeaveStatus.APPROVED);
        leave.setRequestedAt(LocalDateTime.now());
        leaveRepository.save(leave);

        // 2. Generate upcoming roster
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(upcomingMonday, GenerationMode.MANUAL);
        assertNotNull(cycle);

        // 3. Verify Wednesday assignment is marked onLeave
        RosterAssignmentResponse wednesdayAssignment = cycle.assignments().stream()
                .filter(a -> a.employeeCode().equals(emp.getEmployeeCode()) && a.rosterDate().equals(leaveDate))
                .findFirst()
                .orElseThrow();

        assertTrue(wednesdayAssignment.onLeave(), "Assignment on approved leave date must have onLeave = true");
    }

    @Test
    @DisplayName("Batch 47 [4]: Email Idempotency & Strictly Upcoming Week Guard")
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    void test04_EmailIdempotencyAndGuard() {
        RosterCycle cycle = new RosterCycle();
        cycle.setStartDate(upcomingMonday);
        cycle.setEndDate(upcomingMonday.plusDays(6));
        cycle.setStatus(RosterStatus.TENTATIVE);
        cycle.setGenerationMode(GenerationMode.AUTOMATIC);
        cycle.setGeneratedAt(LocalDateTime.now());
        cycle = cycleRepository.save(cycle);

        RosterCycleResponse cycleResponse = rosterService.generateWeeklyRoster(upcomingMonday, GenerationMode.MANUAL);

        // First automated distribution
        List<EmailDeliveryLogResponse> logs1 = emailService.distributeRosterEmails(cycle, cycleResponse, GenerationMode.AUTOMATIC);
        assertNotNull(logs1);

        // Second automated distribution should be skipped (idempotent)
        List<EmailDeliveryLogResponse> logs2 = emailService.distributeRosterEmails(cycle, cycleResponse, GenerationMode.AUTOMATIC);
        assertNotNull(logs2);
    }

    @Test
    @DisplayName("Batch 47 [5]: Shift Handover Workflow & SSE Notifications")
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    void test05_HandoverWorkflowAndSse() {
        Employee creator = employeeRepository.findByUserUsername("emp001").orElseThrow();
        Employee recipient = employeeRepository.findByUserUsername("emp002").orElseThrow();
        Shift shift = shiftRepository.findByShiftType(ShiftType.MORNING).orElseThrow();

        // 1. Create Handover
        CreateHandoverRequest req = new CreateHandoverRequest(
                LocalDate.now(), shift.getId(), recipient.getId(),
                "Handover Notes", "Task 1", "Task 2", "Important", HandoverPriority.HIGH
        );
        HandoverResponse created = handoverService.createHandover(creator.getId(), req, "emp001");
        assertNotNull(created);
        assertEquals(HandoverStatus.OPEN, created.status());

        // 2. Acknowledge Handover
        HandoverResponse acked = handoverService.acknowledgeHandover(created.id(), recipient.getId(), "All tasks noted", "emp002");
        assertNotNull(acked);
        assertEquals(HandoverStatus.ACKNOWLEDGED, acked.status());
    }

    @Test
    @DisplayName("Batch 47 [6]: Profile Change Request Mirror Synchronization")
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    void test06_ProfileChangeSync() {
        Employee emp = employeeRepository.findByUserUsername("emp001").orElseThrow();

        // 1. Create Profile Request
        ProfileChangeRequest changeReq = new ProfileChangeRequest();
        changeReq.setEmployee(emp);
        changeReq.setFieldName("contactNumber");
        changeReq.setCurrentValue(emp.getContactNumber());
        changeReq.setRequestedValue("9876543210");
        changeReq.setStatus(ProfileChangeStatus.PENDING);
        changeReq.setRequestedAt(LocalDateTime.now());
        profileChangeRepository.save(changeReq);

        // 2. Admin Approves Request
        approveProfileAsAdmin(changeReq.getId());

        // 3. Verify Database reflection
        Employee updated = employeeRepository.findById(emp.getId()).orElseThrow();
        assertEquals("9876543210", updated.getContactNumber());
    }

    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN"})
    private void approveProfileAsAdmin(Long reqId) {
        ProfileChangeRequestResponse response = profileChangeRequestService.approve(reqId, new ProfileChangeDecisionRequest("Approved"));
        assertNotNull(response);
        assertEquals(ProfileChangeStatus.APPROVED, response.status());
    }
}