package com.weeklyroster.service;

import com.weeklyroster.dto.request.RosterChangeDecisionRequest;
import com.weeklyroster.dto.request.SubmitRosterChangeRequest;
import com.weeklyroster.dto.response.*;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class Batch41RosterReviewTest {

    @Mock
    private RosterChangeRequestRepository changeRequestRepository;

    @Mock
    private RosterReviewRecordRepository reviewRecordRepository;

    @Mock
    private RosterAssignmentRepository assignmentRepository;

    @Mock
    private RosterCycleRepository cycleRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private EmployeePreferenceRepository preferenceRepository;

    @Mock
    private RosterChangeImpactService impactService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private AuditService auditService;

    @Mock
    private RosterHealthService healthService;

    private RosterReviewService reviewService;

    private RosterCycle testCycle;
    private Employee rajatMale;
    private Employee priyaFemale;
    private Shift morningShift;
    private Shift generalShift;
    private Shift eveningShift;
    private Shift nightShift;
    private Shift offShift;

    @BeforeEach
    void setUp() {
        reviewService = new RosterReviewService(
                changeRequestRepository, reviewRecordRepository, assignmentRepository,
                cycleRepository, employeeRepository, preferenceRepository,
                impactService, notificationService, auditService, healthService
        );

        testCycle = new RosterCycle();
        testCycle.setId(101L);
        testCycle.setStartDate(LocalDate.of(2026, 8, 31));
        testCycle.setEndDate(LocalDate.of(2026, 9, 6));
        testCycle.setStatus(RosterStatus.TENTATIVE);

        User rajatUser = new User();
        rajatUser.setUsername("emp001");

        rajatMale = new Employee();
        rajatMale.setId(1L);
        rajatMale.setFirstName("Rajat");
        rajatMale.setLastName("Maurya");
        rajatMale.setEmployeeCode("EMP001");
        rajatMale.setGender(Gender.MALE);
        rajatMale.setActive(true);
        rajatMale.setUser(rajatUser);

        User priyaUser = new User();
        priyaUser.setUsername("emp002");

        priyaFemale = new Employee();
        priyaFemale.setId(2L);
        priyaFemale.setFirstName("Priya");
        priyaFemale.setLastName("Sharma");
        priyaFemale.setEmployeeCode("EMP002");
        priyaFemale.setGender(Gender.FEMALE);
        priyaFemale.setActive(true);
        priyaFemale.setUser(priyaUser);

        morningShift = new Shift();
        morningShift.setId(1L);
        morningShift.setShiftType(ShiftType.MORNING);

        generalShift = new Shift();
        generalShift.setId(2L);
        generalShift.setShiftType(ShiftType.GENERAL);

        eveningShift = new Shift();
        eveningShift.setId(3L);
        eveningShift.setShiftType(ShiftType.EVENING);

        nightShift = new Shift();
        nightShift.setId(4L);
        nightShift.setShiftType(ShiftType.NIGHT);

        offShift = new Shift();
        offShift.setId(5L);
        offShift.setShiftType(ShiftType.OFF);
    }

    @Test
    @DisplayName("TEST 1: Tentative roster visible in employee review summary")
    void testGetEmployeeReviewSummary_ReturnsTentativeSummary() {
        when(employeeRepository.findByUserUsernameIgnoreCase("emp001")).thenReturn(Optional.of(rajatMale));
        when(cycleRepository.findById(101L)).thenReturn(Optional.of(testCycle));

        RosterAssignment a1 = new RosterAssignment();
        a1.setId(10L);
        a1.setCycle(testCycle);
        a1.setEmployee(rajatMale);
        a1.setRosterDate(LocalDate.of(2026, 8, 31));
        a1.setShift(generalShift);

        when(assignmentRepository.findByEmployeeIdAndRosterDateBetween(eq(1L), any(), any()))
                .thenReturn(List.of(a1));
        when(changeRequestRepository.findByEmployeeIdOrderByCreatedAtDesc(1L)).thenReturn(Collections.emptyList());
        when(reviewRecordRepository.existsByEmployeeIdAndCycleId(1L, 101L)).thenReturn(false);
        when(preferenceRepository.findTopByEmployeeIdAndStatusOrderByCreatedAtDesc(1L, PreferenceStatus.APPROVED)).thenReturn(Optional.empty());

        EmployeeRosterReviewSummaryResponse summary = reviewService.getEmployeeReviewSummary(101L, "emp001");

        assertNotNull(summary);
        assertEquals(101L, summary.cycleId());
        assertEquals(RosterStatus.TENTATIVE, summary.cycleStatus());
        assertEquals("ACTION_REQUIRED", summary.reviewStatus());
        assertEquals(1, summary.totalAssignments());
        assertEquals(0, summary.pendingRequestsCount());
    }

    @Test
    @DisplayName("TEST 2: Employee submits valid shift change request")
    void testSubmitChangeRequest_ValidRequest_Success() {
        when(employeeRepository.findByUserUsernameIgnoreCase("emp001")).thenReturn(Optional.of(rajatMale));

        RosterAssignment a1 = new RosterAssignment();
        a1.setId(10L);
        a1.setCycle(testCycle);
        a1.setEmployee(rajatMale);
        a1.setRosterDate(LocalDate.of(2026, 9, 2));
        a1.setShift(generalShift);

        when(assignmentRepository.findById(10L)).thenReturn(Optional.of(a1));
        when(changeRequestRepository.findByAssignmentIdAndStatus(10L, RosterChangeStatus.PENDING)).thenReturn(Optional.empty());
        when(changeRequestRepository.save(any(RosterChangeRequest.class))).thenAnswer(inv -> {
            RosterChangeRequest r = inv.getArgument(0);
            r.setId(501L);
            return r;
        });

        SubmitRosterChangeRequest req = new SubmitRosterChangeRequest(10L, ShiftType.MORNING, false, "Personal requirement");
        RosterChangeRequestResponse resp = reviewService.submitChangeRequest(req, "emp001");

        assertNotNull(resp);
        assertEquals(501L, resp.id());
        assertEquals(RosterChangeStatus.PENDING, resp.status());
        assertEquals(ShiftType.MORNING, resp.requestedShiftType());
        assertEquals(ShiftType.GENERAL, resp.currentShiftType());
        verify(notificationService, times(1)).createNotification(eq("admin"), any(), any(), any(), eq(NotificationType.ADMIN_ALERT), any(), any());
    }

    @Test
    @DisplayName("TEST 3: Duplicate change request for same assignment is blocked")
    void testSubmitChangeRequest_DuplicatePending_ThrowsBusinessException() {
        when(employeeRepository.findByUserUsernameIgnoreCase("emp001")).thenReturn(Optional.of(rajatMale));

        RosterAssignment a1 = new RosterAssignment();
        a1.setId(10L);
        a1.setCycle(testCycle);
        a1.setEmployee(rajatMale);
        a1.setRosterDate(LocalDate.of(2026, 9, 2));

        RosterChangeRequest existing = new RosterChangeRequest();
        existing.setId(99L);
        existing.setStatus(RosterChangeStatus.PENDING);

        when(assignmentRepository.findById(10L)).thenReturn(Optional.of(a1));
        when(changeRequestRepository.findByAssignmentIdAndStatus(10L, RosterChangeStatus.PENDING)).thenReturn(Optional.of(existing));

        SubmitRosterChangeRequest req = new SubmitRosterChangeRequest(10L, ShiftType.MORNING, false, "Another request");

        BusinessException ex = assertThrows(BusinessException.class, () -> reviewService.submitChangeRequest(req, "emp001"));
        assertTrue(ex.getMessage().contains("already pending admin review"));
    }

    @Test
    @DisplayName("TEST 4: Female employee requesting EVENING or NIGHT shift is blocked by safety regulation")
    void testSubmitChangeRequest_FemaleEveningOrNight_ThrowsBusinessException() {
        when(employeeRepository.findByUserUsernameIgnoreCase("emp002")).thenReturn(Optional.of(priyaFemale));

        RosterAssignment a1 = new RosterAssignment();
        a1.setId(20L);
        a1.setCycle(testCycle);
        a1.setEmployee(priyaFemale);
        a1.setRosterDate(LocalDate.of(2026, 9, 2));
        a1.setShift(morningShift);

        when(assignmentRepository.findById(20L)).thenReturn(Optional.of(a1));
        when(changeRequestRepository.findByAssignmentIdAndStatus(20L, RosterChangeStatus.PENDING)).thenReturn(Optional.empty());

        SubmitRosterChangeRequest req = new SubmitRosterChangeRequest(20L, ShiftType.EVENING, false, "Need evening");

        BusinessException ex = assertThrows(BusinessException.class, () -> reviewService.submitChangeRequest(req, "emp002"));
        assertTrue(ex.getMessage().contains("Female safety regulation"));
    }

    @Test
    @DisplayName("TEST 5: Admin sees team review summary and pending request count")
    void testGetTeamReviewSummary_ReturnsAccurateMetrics() {
        when(cycleRepository.findById(101L)).thenReturn(Optional.of(testCycle));
        when(employeeRepository.countByActiveTrue()).thenReturn(7L);
        when(reviewRecordRepository.countByCycleId(101L)).thenReturn(5L);

        RosterChangeRequest r1 = new RosterChangeRequest();
        r1.setId(1L);
        r1.setEmployee(rajatMale);
        r1.setRosterDate(LocalDate.of(2026, 9, 2));
        r1.setCurrentShiftType(ShiftType.GENERAL);
        r1.setRequestedShiftType(ShiftType.MORNING);
        r1.setStatus(RosterChangeStatus.PENDING);

        when(changeRequestRepository.findByCycleIdOrderByCreatedAtDesc(101L)).thenReturn(List.of(r1));

        TeamRosterReviewSummaryResponse summary = reviewService.getTeamReviewSummary(101L);

        assertNotNull(summary);
        assertEquals(7, summary.totalEmployees());
        assertEquals(5, summary.reviewedEmployeesCount());
        assertEquals(2, summary.pendingReviewEmployeesCount());
        assertEquals(1, summary.pendingRequestsCount());
        assertTrue(summary.attentionStatus().contains("ACTION REQUIRED"));
    }

    @Test
    @DisplayName("TEST 6: Admin approves safe request -> assignment updated and status APPROVED")
    void testDecideChangeRequest_ApproveSafe_Success() {
        RosterAssignment assignment = new RosterAssignment();
        assignment.setId(10L);
        assignment.setCycle(testCycle);
        assignment.setEmployee(rajatMale);
        assignment.setRosterDate(LocalDate.of(2026, 9, 2));
        assignment.setShift(generalShift);

        RosterChangeRequest request = new RosterChangeRequest();
        request.setId(301L);
        request.setEmployee(rajatMale);
        request.setCycle(testCycle);
        request.setAssignment(assignment);
        request.setRosterDate(LocalDate.of(2026, 9, 2));
        request.setCurrentShiftType(ShiftType.GENERAL);
        request.setRequestedShiftType(ShiftType.MORNING);
        request.setReason("Doctor visit");
        request.setStatus(RosterChangeStatus.PENDING);

        when(changeRequestRepository.findById(301L)).thenReturn(Optional.of(request));

        RosterChangeImpactResponse safeImpact = new RosterChangeImpactResponse(
                10L, 101L, 1L, "EMP001", "Rajat Maurya", Gender.MALE,
                LocalDate.of(2026, 9, 2), "Wednesday", ShiftType.GENERAL, ShiftType.MORNING,
                false, false, 94.0, 95.0, "SAFE", "🟢 SAFE TO APPLY",
                true, false, "Safe", "Staffed", "Safe", "Rest maintained",
                "Safe", "None", "Safe", "Maintained", "Neutral", "Unchanged",
                "Unchanged", "None", "Safe", "Day policy ok", "None",
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList()
        );

        when(impactService.previewImpact(10L, ShiftType.MORNING, false)).thenReturn(safeImpact);
        when(changeRequestRepository.save(any(RosterChangeRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        RosterChangeDecisionRequest decision = new RosterChangeDecisionRequest("Approved", "Looks good");
        RosterChangeRequestResponse result = reviewService.decideChangeRequest(301L, true, decision, "admin");

        assertNotNull(result);
        assertEquals(RosterChangeStatus.APPROVED, result.status());
        verify(impactService, times(1)).applyChangeWithValidation(eq(10L), eq(ShiftType.MORNING), eq(false), any());
        verify(notificationService, times(1)).createNotification(eq("emp001"), eq(1L), any(), any(), eq(NotificationType.ROSTER_PUBLISHED), any(), any());
    }

    @Test
    @DisplayName("TEST 7: Admin rejects request with remark -> status REJECTED")
    void testDecideChangeRequest_Reject_Success() {
        RosterChangeRequest request = new RosterChangeRequest();
        request.setId(302L);
        request.setEmployee(rajatMale);
        request.setCycle(testCycle);
        request.setRosterDate(LocalDate.of(2026, 9, 2));
        request.setStatus(RosterChangeStatus.PENDING);

        when(changeRequestRepository.findById(302L)).thenReturn(Optional.of(request));
        when(changeRequestRepository.save(any(RosterChangeRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        RosterChangeDecisionRequest decision = new RosterChangeDecisionRequest(null, "Coverage constraint on morning shift");
        RosterChangeRequestResponse result = reviewService.decideChangeRequest(302L, false, decision, "admin");

        assertNotNull(result);
        assertEquals(RosterChangeStatus.REJECTED, result.status());
        assertEquals("Coverage constraint on morning shift", result.adminRemarks());
        verify(notificationService, times(1)).createNotification(eq("emp001"), eq(1L), any(), any(), eq(NotificationType.ROSTER_PUBLISHED), any(), any());
    }

    @Test
    @DisplayName("TEST 8: Employee cancels pending request -> status CANCELLED")
    void testCancelChangeRequest_Pending_Success() {
        when(employeeRepository.findByUserUsernameIgnoreCase("emp001")).thenReturn(Optional.of(rajatMale));

        RosterChangeRequest request = new RosterChangeRequest();
        request.setId(401L);
        request.setEmployee(rajatMale);
        request.setStatus(RosterChangeStatus.PENDING);
        request.setRosterDate(LocalDate.of(2026, 9, 2));

        when(changeRequestRepository.findById(401L)).thenReturn(Optional.of(request));
        when(changeRequestRepository.save(any(RosterChangeRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        RosterChangeRequestResponse resp = reviewService.cancelChangeRequest(401L, "emp001");

        assertNotNull(resp);
        assertEquals(RosterChangeStatus.CANCELLED, resp.status());
    }

    @Test
    @DisplayName("TEST 9: Employee marks review complete -> does not lock cycle")
    void testMarkReviewComplete_SavesRecordWithoutLockingCycle() {
        when(employeeRepository.findByUserUsernameIgnoreCase("emp001")).thenReturn(Optional.of(rajatMale));
        when(cycleRepository.findById(101L)).thenReturn(Optional.of(testCycle));
        when(reviewRecordRepository.findByEmployeeIdAndCycleId(1L, 101L)).thenReturn(Optional.empty());

        boolean result = reviewService.markReviewComplete(101L, "emp001");

        assertTrue(result);
        assertEquals(RosterStatus.TENTATIVE, testCycle.getStatus());
        verify(reviewRecordRepository, times(1)).save(any(RosterReviewRecord.class));
    }

    @Test
    @DisplayName("TEST 10: Change request on locked / FINAL roster is blocked")
    void testSubmitChangeRequest_FinalOrLockedCycle_ThrowsBusinessException() {
        testCycle.setStatus(RosterStatus.FINAL);
        testCycle.setLockedAt(LocalDateTime.now());

        when(employeeRepository.findByUserUsernameIgnoreCase("emp001")).thenReturn(Optional.of(rajatMale));

        RosterAssignment a1 = new RosterAssignment();
        a1.setId(10L);
        a1.setCycle(testCycle);
        a1.setEmployee(rajatMale);
        a1.setRosterDate(LocalDate.of(2026, 9, 2));

        when(assignmentRepository.findById(10L)).thenReturn(Optional.of(a1));

        SubmitRosterChangeRequest req = new SubmitRosterChangeRequest(10L, ShiftType.MORNING, false, "Late request");

        BusinessException ex = assertThrows(BusinessException.class, () -> reviewService.submitChangeRequest(req, "emp001"));
        assertTrue(ex.getMessage().contains("Roster review window is closed"));
    }

    @Test
    @DisplayName("TEST 11: Change request for another employee is denied")
    void testSubmitChangeRequest_UnauthorizedEmployee_ThrowsAccessDenied() {
        when(employeeRepository.findByUserUsernameIgnoreCase("emp001")).thenReturn(Optional.of(rajatMale));

        RosterAssignment priyaAssignment = new RosterAssignment();
        priyaAssignment.setId(20L);
        priyaAssignment.setCycle(testCycle);
        priyaAssignment.setEmployee(priyaFemale);

        when(assignmentRepository.findById(20L)).thenReturn(Optional.of(priyaAssignment));

        SubmitRosterChangeRequest req = new SubmitRosterChangeRequest(20L, ShiftType.MORNING, false, "Someone else");

        assertThrows(AccessDeniedException.class, () -> reviewService.submitChangeRequest(req, "emp001"));
    }
}
