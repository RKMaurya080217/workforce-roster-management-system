package com.weeklyroster.service;

import com.weeklyroster.dto.response.ConflictItem;
import com.weeklyroster.dto.response.RosterHealthReport;
import com.weeklyroster.dto.response.SmartCommandCenterResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class Batch39SmartCommandCenterTest {

    @Mock
    private RosterCycleRepository cycleRepository;

    @Mock
    private RosterAssignmentRepository assignmentRepository;

    @Mock
    private RosterHealthService healthService;

    @Mock
    private LeaveRequestRepository leaveRequestRepository;

    @Mock
    private EmployeePreferenceRepository preferenceRepository;

    @Mock
    private RosterOverrideRepository overrideRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private NotificationRepository notificationRepository;

    @InjectMocks
    private SmartCommandCenterService commandCenterService;

    private RosterCycle testCycle;
    private Employee rajat;
    private Employee priya;
    private List<RosterAssignment> assignments;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "pass", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );

        testCycle = new RosterCycle();
        testCycle.setId(101L);
        testCycle.setStartDate(LocalDate.of(2026, 8, 31));
        testCycle.setEndDate(LocalDate.of(2026, 9, 6));
        testCycle.setStatus(RosterStatus.TENTATIVE);
        testCycle.setGenerationMode(GenerationMode.AUTOMATIC);
        testCycle.setGeneratedAt(LocalDateTime.now());

        rajat = new Employee();
        rajat.setId(1L);
        rajat.setFirstName("Rajat");
        rajat.setLastName("Maurya");
        rajat.setEmployeeCode("EMP001");
        rajat.setGender(Gender.MALE);
        rajat.setActive(true);

        priya = new Employee();
        priya.setId(2L);
        priya.setFirstName("Priya");
        priya.setLastName("Sharma");
        priya.setEmployeeCode("EMP002");
        priya.setGender(Gender.FEMALE);
        priya.setActive(true);

        assignments = new ArrayList<>();
        RosterAssignment a1 = new RosterAssignment();
        a1.setId(10L);
        a1.setCycle(testCycle);
        a1.setEmployee(rajat);
        a1.setRosterDate(testCycle.getStartDate());
        assignments.add(a1);
    }

    private RosterHealthReport buildHealthyReport() {
        return new RosterHealthReport(
                101L, testCycle.getStartDate(), testCycle.getEndDate(),
                RosterStatus.TENTATIVE, true, "VALID",
                "PASSED", "PASSED", "PASSED", "PASSED", "PASSED",
                "PASSED", "PASSED", "PASSED", "PASSED",
                0, 0, 0, 0, 0,
                Collections.emptyList(),
                94.0, 96.0, "All Satisfied", "VALID",
                93.0, 92.0
        );
    }

    @Test
    @DisplayName("Scenario 1: Command Center Active Cycle Summary - Tentative Ready")
    void testCommandCenter_ActiveCycleSummary_TentativeReady() {
        when(cycleRepository.findById(101L)).thenReturn(Optional.of(testCycle));
        when(assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(testCycle)).thenReturn(assignments);
        when(healthService.evaluateHealth(testCycle, assignments)).thenReturn(buildHealthyReport());
        when(preferenceRepository.findByStatusOrderByCreatedAtDesc(PreferenceStatus.PENDING)).thenReturn(Collections.emptyList());
        when(leaveRequestRepository.findByStatusOrderByRequestedAtAsc(LeaveStatus.PENDING)).thenReturn(Collections.emptyList());
        when(overrideRepository.findAll()).thenReturn(Collections.emptyList());
        when(auditLogRepository.findAllByOrderByTimestampDesc()).thenReturn(Collections.emptyList());
        when(notificationRepository.findAll()).thenReturn(Collections.emptyList());

        SmartCommandCenterResponse summary = commandCenterService.getCycleSummary(101L);

        assertNotNull(summary);
        assertEquals(101L, summary.cycleId());
        assertEquals(RosterStatus.TENTATIVE, summary.status());
        assertTrue(summary.lifecycleStage().contains("TENTATIVE"));
        assertEquals("Sunday 4:00 PM IST", summary.reviewDeadline());
        assertEquals(94.0, summary.healthScore());
        assertEquals(0, summary.pendingRequestsCount());
        assertEquals("READY", summary.finalizationReadiness());
        assertTrue(summary.finalizationStatusMessage().contains("READY FOR FINALIZATION"));
        assertNotNull(summary.smartSummary());
        assertTrue(summary.smartSummary().contains("ready for finalization"));
    }

    @Test
    @DisplayName("Scenario 2: Pending Employee Requests block Finalization Readiness")
    void testCommandCenter_PendingEmployeeRequests_BlocksReadiness() {
        when(cycleRepository.findById(101L)).thenReturn(Optional.of(testCycle));
        when(assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(testCycle)).thenReturn(assignments);
        when(healthService.evaluateHealth(testCycle, assignments)).thenReturn(buildHealthyReport());

        // Pending Leave
        LeaveRequest leave = new LeaveRequest();
        leave.setId(55L);
        leave.setEmployee(rajat);
        leave.setStartDate(LocalDate.of(2026, 9, 2));
        leave.setEndDate(LocalDate.of(2026, 9, 3));
        leave.setReason("Urgent family commitment");
        leave.setStatus(LeaveStatus.PENDING);
        when(leaveRequestRepository.findByStatusOrderByRequestedAtAsc(LeaveStatus.PENDING)).thenReturn(List.of(leave));

        // Pending Preference
        EmployeePreference pref = new EmployeePreference();
        pref.setId(66L);
        pref.setEmployee(priya);
        pref.setPreferredShiftTypes("MORNING");
        pref.setStatus(PreferenceStatus.PENDING);
        when(preferenceRepository.findByStatusOrderByCreatedAtDesc(PreferenceStatus.PENDING)).thenReturn(List.of(pref));

        when(overrideRepository.findAll()).thenReturn(Collections.emptyList());
        when(auditLogRepository.findAllByOrderByTimestampDesc()).thenReturn(Collections.emptyList());
        when(notificationRepository.findAll()).thenReturn(Collections.emptyList());

        SmartCommandCenterResponse summary = commandCenterService.getCycleSummary(101L);

        assertNotNull(summary);
        assertEquals(2, summary.pendingRequestsCount());
        assertEquals(2, summary.pendingChanges().size());
        assertEquals("NOT_READY", summary.finalizationReadiness());
        assertTrue(summary.finalizationStatusMessage().contains("NOT READY"));
        assertTrue(summary.finalizationBlockers().stream().anyMatch(b -> b.contains("pending employee approval")));
        assertTrue(summary.smartSummary().contains("2 employee request(s) are pending approval"));
    }

    @Test
    @DisplayName("Scenario 3: Final Locked Cycle shows Completed and Optimization Locked")
    void testCommandCenter_FinalLockedCycle() {
        testCycle.setStatus(RosterStatus.FINAL);
        when(cycleRepository.findById(101L)).thenReturn(Optional.of(testCycle));
        when(assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(testCycle)).thenReturn(assignments);
        when(healthService.evaluateHealth(testCycle, assignments)).thenReturn(buildHealthyReport());
        when(preferenceRepository.findByStatusOrderByCreatedAtDesc(PreferenceStatus.PENDING)).thenReturn(Collections.emptyList());
        when(leaveRequestRepository.findByStatusOrderByRequestedAtAsc(LeaveStatus.PENDING)).thenReturn(Collections.emptyList());
        when(overrideRepository.findAll()).thenReturn(Collections.emptyList());
        when(auditLogRepository.findAllByOrderByTimestampDesc()).thenReturn(Collections.emptyList());
        when(notificationRepository.findAll()).thenReturn(Collections.emptyList());

        SmartCommandCenterResponse summary = commandCenterService.getCycleSummary(101L);

        assertNotNull(summary);
        assertEquals(RosterStatus.FINAL, summary.status());
        assertEquals("COMPLETED", summary.finalizationReadiness());
        assertTrue(summary.finalizationStatusMessage().contains("FINALIZED & LOCKED"));
        assertEquals("LOCKED", summary.optimizationSummary().status());
        assertFalse(summary.optimizationSummary().optimizationAvailable());
    }

    @Test
    @DisplayName("Scenario 4: Admin Overrides Summary accurately reported")
    void testCommandCenter_AdminOverrideSummary() {
        when(cycleRepository.findById(101L)).thenReturn(Optional.of(testCycle));
        when(assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(testCycle)).thenReturn(assignments);
        when(healthService.evaluateHealth(testCycle, assignments)).thenReturn(buildHealthyReport());
        when(preferenceRepository.findByStatusOrderByCreatedAtDesc(PreferenceStatus.PENDING)).thenReturn(Collections.emptyList());
        when(leaveRequestRepository.findByStatusOrderByRequestedAtAsc(LeaveStatus.PENDING)).thenReturn(Collections.emptyList());

        RosterAssignment target = assignments.get(0);
        target.setOverridden(true);

        RosterOverride override = new RosterOverride();
        override.setId(88L);
        override.setAssignment(target);
        override.setPreviousShiftType(ShiftType.GENERAL);
        override.setNewShiftType(ShiftType.MORNING);
        override.setReason("Admin manual coverage fix");
        override.setCreatedAt(LocalDateTime.now());
        when(overrideRepository.findAll()).thenReturn(List.of(override));

        when(auditLogRepository.findAllByOrderByTimestampDesc()).thenReturn(Collections.emptyList());
        when(notificationRepository.findAll()).thenReturn(Collections.emptyList());

        SmartCommandCenterResponse summary = commandCenterService.getCycleSummary(101L);

        assertNotNull(summary);
        assertEquals(1, summary.adminOverridesSummary().activeOverridesCount());
        assertEquals(1, summary.adminOverridesSummary().items().size());
        assertEquals("MORNING", summary.adminOverridesSummary().items().get(0).shiftType());
        assertTrue(summary.adminOverridesSummary().items().get(0).reason().contains("coverage fix"));
    }

    @Test
    @DisplayName("Scenario 5: Hard Constraint violation results in BLOCKED status")
    void testCommandCenter_HardConstraintViolation_BlocksFinalization() {
        ConflictItem restConflict = new ConflictItem(
                LocalDate.of(2026, 9, 3), 1L, "Rajat Maurya",
                ShiftType.MORNING, "12-Hour Rest Rule", "Evening -> Morning", "Min 12h Rest",
                "12-hour rest rule violated between Evening and Morning shift", "CRITICAL", "Reassign shift", false
        );

        RosterHealthReport badReport = new RosterHealthReport(
                101L, testCycle.getStartDate(), testCycle.getEndDate(),
                RosterStatus.TENTATIVE, false, "INVALID",
                "PASSED", "FAILED", "PASSED", "PASSED", "PASSED",
                "PASSED", "PASSED", "PASSED", "PASSED",
                1, 0, 0, 0, 0,
                List.of(restConflict),
                65.0, 70.0, "All Satisfied", "INVALID",
                70.0, 75.0
        );

        when(cycleRepository.findById(101L)).thenReturn(Optional.of(testCycle));
        when(assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(testCycle)).thenReturn(assignments);
        when(healthService.evaluateHealth(testCycle, assignments)).thenReturn(badReport);
        when(preferenceRepository.findByStatusOrderByCreatedAtDesc(PreferenceStatus.PENDING)).thenReturn(Collections.emptyList());
        when(leaveRequestRepository.findByStatusOrderByRequestedAtAsc(LeaveStatus.PENDING)).thenReturn(Collections.emptyList());
        when(overrideRepository.findAll()).thenReturn(Collections.emptyList());
        when(auditLogRepository.findAllByOrderByTimestampDesc()).thenReturn(Collections.emptyList());
        when(notificationRepository.findAll()).thenReturn(Collections.emptyList());

        SmartCommandCenterResponse summary = commandCenterService.getCycleSummary(101L);

        assertNotNull(summary);
        assertEquals("BLOCKED", summary.finalizationReadiness());
        assertTrue(summary.finalizationStatusMessage().contains("BLOCKED"));
        assertTrue(summary.finalizationBlockers().stream().anyMatch(b -> b.contains("12-hour minimum rest") || b.contains("critical")));
        assertEquals(1, summary.criticalConflictsCount());
        assertEquals(1, summary.exceptions().size());
        assertEquals("CRITICAL", summary.exceptions().get(0).severity());
    }

    @Test
    @DisplayName("Scenario 6: Night and Continuity summaries calculated accurately")
    void testCommandCenter_NightAndContinuitySummaries() {
        when(cycleRepository.findById(101L)).thenReturn(Optional.of(testCycle));
        when(assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(testCycle)).thenReturn(assignments);
        when(healthService.evaluateHealth(testCycle, assignments)).thenReturn(buildHealthyReport());
        when(preferenceRepository.findByStatusOrderByCreatedAtDesc(PreferenceStatus.PENDING)).thenReturn(Collections.emptyList());
        when(leaveRequestRepository.findByStatusOrderByRequestedAtAsc(LeaveStatus.PENDING)).thenReturn(Collections.emptyList());
        when(overrideRepository.findAll()).thenReturn(Collections.emptyList());
        when(auditLogRepository.findAllByOrderByTimestampDesc()).thenReturn(Collections.emptyList());
        when(notificationRepository.findAll()).thenReturn(Collections.emptyList());

        SmartCommandCenterResponse summary = commandCenterService.getCycleSummary(101L);

        assertNotNull(summary);
        assertNotNull(summary.nightAllocationSummary());
        assertEquals(0, summary.nightAllocationSummary().femaleNightCount());
        assertNotNull(summary.continuitySummary());
        assertEquals(93.0, summary.continuitySummary().score());
        assertNotNull(summary.workloadSummary());
    }
}
