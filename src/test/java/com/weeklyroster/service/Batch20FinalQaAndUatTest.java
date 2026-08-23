package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.*;

import com.weeklyroster.dto.request.*;
import com.weeklyroster.dto.response.*;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.*;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class Batch20FinalQaAndUatTest {

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private ProfileChangeRequestService profileChangeRequestService;

    @Autowired
    private RosterService rosterService;

    @Autowired
    private RosterSchedulerService schedulerService;

    @Autowired
    private RosterVersionService versionService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private LeaveService leaveService;

    @Autowired
    private RosterEmailService emailService;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private DevCredentialMirrorService devCredentialMirrorService;

    private void authenticateAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "N/A", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );
    }

    private void authenticateEmployee(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "N/A", List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
        );
    }

    @BeforeEach
    void setup() {
        authenticateAdmin();
    }

    @Test
    @DisplayName("Batch 20 â€” 2: Admin End-to-End Journey - Generate -> Publish -> Version -> Audit")
    void testAdminEndToEndJourney() {
        authenticateAdmin();
        LocalDate monday = LocalDate.of(2027, 1, 11);

        // 1. Generate Roster
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL);
        assertNotNull(cycle.id());
        assertEquals(49, cycle.assignments().size());

        // 2. Lock & Publish Roster
        RosterCycleResponse published = rosterService.publishRoster(cycle.id());
        assertEquals(RosterStatus.PUBLISHED, published.status());

        // 3. Version History captures snapshots
        List<RosterVersionResponse> versions = versionService.getCycleVersions(cycle.id());
        assertTrue(versions.size() >= 2);
        assertEquals("PUBLISHED", versions.get(0).action());

        // 4. Distribute Emails
        RosterCycle cycleEntity = cycleRepository.findById(cycle.id()).orElseThrow();
        List<EmailDeliveryLogResponse> logs = emailService.distributeRosterEmails(cycleEntity, published, GenerationMode.MANUAL);
        assertNotNull(logs);
        assertFalse(logs.isEmpty());
    }

    @Test
    @DisplayName("Batch 20 â€” 2: Employee End-to-End Journey - View Profile -> Update -> Leave -> Notifications")
    void testEmployeeEndToEndJourney() {
        String testUser = "emp001";
        authenticateEmployee(testUser);
        Employee emp = employeeRepository.findByUserUsername(testUser).orElseThrow();

        // 1. Fetch profile & verify target email
        EmployeeResponse profile = employeeService.getMyProfile();
        assertNotNull(profile);
        assertEquals("rkmaurya080217@gmail.com", profile.email());

        // 2. Apply for Leave
        LocalDate leaveDate = LocalDate.of(2027, 1, 15);
        LeaveResponse leaveResp = leaveService.apply(
                new ApplyLeaveRequest(emp.getId(), leaveDate, leaveDate, "Medical appointment")
        );
        assertNotNull(leaveResp.id());
        assertEquals(LeaveStatus.PENDING, leaveResp.status());

        // 3. Admin approves leave
        authenticateAdmin();
        LeaveResponse approved = leaveService.approve(leaveResp.id(), new LeaveDecisionRequest("Approved by HR"));
        assertEquals(LeaveStatus.APPROVED, approved.status());

        // 4. Employee checks notifications
        authenticateEmployee(testUser);
        List<NotificationResponse> notifs = notificationService.getMyNotifications(testUser);
        assertTrue(notifs.stream().anyMatch(n -> n.type() == NotificationType.LEAVE_DECISION));
    }

    @Test
    @DisplayName("Batch 20 â€” 4: Email Persistence - rkmaurya080217@gmail.com Survives Schedulers & Sync")
    void testEmailPersistenceAcrossOperations() {
        String testUser = "emp001";
        String expectedEmail = "rkmaurya080217@gmail.com";

        authenticateEmployee(testUser);
        EmployeeResponse p1 = employeeService.getMyProfile();
        assertEquals(expectedEmail, p1.email());

        // Run auto-scheduler for upcoming week
        authenticateAdmin();
        LocalDate targetMonday = schedulerService.calculateTargetMonday(null);
        schedulerService.executeAutoGeneration(targetMonday);

        // Run CSV sync
        Employee emp = employeeRepository.findByUserUsername(testUser).orElseThrow();
        if (devCredentialMirrorService != null) {
            devCredentialMirrorService.updateProfile(emp, null);
        }

        // Verify profile in DB still holds expected email
        Employee dbEmp = employeeRepository.findByUserUsername(testUser).orElseThrow();
        assertEquals(expectedEmail, dbEmp.getEmail(), "Email must remain persistent without being reset");
    }

    @Test
    @DisplayName("Batch 20 â€” 8 & 9: Hard Roster Safety - Sunday-Only, Immediate Week Only, Manual Priority")
    void testHardRosterSafetyAndManualPriority() {
        authenticateAdmin();
        LocalDate immediateMonday = schedulerService.calculateTargetMonday(null);

        // Scenario A: Auto-generate immediate week
        RosterCycleResponse autoCycle = schedulerService.executeAutoGeneration(immediateMonday);
        assertNotNull(autoCycle);

        // Scenario B: Next-next week must throw BusinessException
        assertThrows(BusinessException.class, () -> schedulerService.executeAutoGeneration(immediateMonday.plusWeeks(1)));

        // Scenario C: Re-running scheduler when cycle already exists does nothing (idempotent)
        RosterCycleResponse rerun = schedulerService.executeAutoGeneration(immediateMonday);
        assertEquals(autoCycle.id(), rerun.id());
    }
}