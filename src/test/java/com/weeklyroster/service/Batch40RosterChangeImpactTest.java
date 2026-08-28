package com.weeklyroster.service;

import com.weeklyroster.dto.ApplicablePreference;
import com.weeklyroster.dto.response.ConflictItem;
import com.weeklyroster.dto.response.RosterAssignmentResponse;
import com.weeklyroster.dto.response.RosterChangeImpactResponse;
import com.weeklyroster.dto.response.RosterHealthReport;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class Batch40RosterChangeImpactTest {

    @Mock
    private RosterAssignmentRepository assignmentRepository;

    @Mock
    private RosterCycleRepository cycleRepository;

    @Mock
    private RosterHealthService healthService;

    @Mock
    private RosterService rosterService;

    @Mock
    private EmployeePreferenceRepository preferenceRepository;

    @Mock
    private LeaveRequestRepository leaveRequestRepository;

    @Mock
    private RosterOverrideRepository overrideRepository;

    @Mock
    private ShiftRepository shiftRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private NotificationService notificationService;

    private RosterChangeImpactService impactService;

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
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "pass", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );

        impactService = new RosterChangeImpactService(
                assignmentRepository, cycleRepository, healthService, rosterService,
                preferenceRepository, leaveRequestRepository, overrideRepository,
                shiftRepository, auditService, notificationService
        );

        testCycle = new RosterCycle();
        testCycle.setId(101L);
        testCycle.setStartDate(LocalDate.of(2026, 8, 31));
        testCycle.setEndDate(LocalDate.of(2026, 9, 6));
        testCycle.setStatus(RosterStatus.TENTATIVE);

        rajatMale = new Employee();
        rajatMale.setId(1L);
        rajatMale.setFirstName("Rajat");
        rajatMale.setLastName("Maurya");
        rajatMale.setEmployeeCode("EMP001");
        rajatMale.setGender(Gender.MALE);
        rajatMale.setActive(true);

        priyaFemale = new Employee();
        priyaFemale.setId(2L);
        priyaFemale.setFirstName("Priya");
        priyaFemale.setLastName("Sharma");
        priyaFemale.setEmployeeCode("EMP002");
        priyaFemale.setGender(Gender.FEMALE);
        priyaFemale.setActive(true);

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

        when(shiftRepository.findAll()).thenReturn(List.of(morningShift, generalShift, eveningShift, nightShift, offShift));
    }

    private RosterHealthReport createMockHealth(double score) {
        return new RosterHealthReport(
                101L, testCycle.getStartDate(), testCycle.getEndDate(),
                RosterStatus.TENTATIVE, true, "VALID",
                "PASSED", "PASSED", "PASSED", "PASSED", "PASSED",
                "PASSED", "PASSED", "PASSED", "PASSED",
                0, 0, 0, 0, 0,
                Collections.emptyList(),
                score, 95.0, "All Satisfied", "VALID",
                92.0, 90.0
        );
    }

    @Test
    @DisplayName("TEST 1: Safe change (GENERAL -> MORNING) is marked SAFE and can apply")
    void testPreviewImpact_SafeChange_ReturnsSafe() {
        RosterAssignment assignment = new RosterAssignment();
        assignment.setId(10L);
        assignment.setCycle(testCycle);
        assignment.setEmployee(rajatMale);
        assignment.setRosterDate(LocalDate.of(2026, 9, 2));
        assignment.setShift(generalShift);
        assignment.setWeeklyOff(false);

        RosterAssignment otherAssignment = new RosterAssignment();
        otherAssignment.setId(11L);
        otherAssignment.setCycle(testCycle);
        otherAssignment.setEmployee(priyaFemale);
        otherAssignment.setRosterDate(LocalDate.of(2026, 9, 2));
        otherAssignment.setShift(generalShift);
        otherAssignment.setWeeklyOff(false);

        when(assignmentRepository.findById(10L)).thenReturn(Optional.of(assignment));
        when(leaveRequestRepository.findByStatusOrderByRequestedAtAsc(LeaveStatus.APPROVED)).thenReturn(Collections.emptyList());
        when(assignmentRepository.findByEmployeeIdAndRosterDate(1L, LocalDate.of(2026, 9, 1))).thenReturn(Collections.emptyList());
        when(assignmentRepository.findByEmployeeIdAndRosterDate(1L, LocalDate.of(2026, 9, 3))).thenReturn(Collections.emptyList());
        when(assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(testCycle)).thenReturn(List.of(assignment, otherAssignment));
        when(healthService.evaluateHealth(eq(testCycle), anyList())).thenReturn(createMockHealth(94.0));
        when(preferenceRepository.findTopByEmployeeIdAndStatusOrderByCreatedAtDesc(1L, PreferenceStatus.APPROVED)).thenReturn(Optional.empty());
        when(preferenceRepository.findByEmployeeIdOrderByCreatedAtDesc(1L)).thenReturn(Collections.emptyList());

        RosterChangeImpactResponse response = impactService.previewImpact(10L, ShiftType.MORNING, false);

        assertNotNull(response);
        assertEquals("SAFE", response.impactStatus());
        assertTrue(response.canApply());
        assertEquals("Safe", response.restImpact());
        assertEquals("Safe", response.genderImpact());
        assertTrue(response.blockers().isEmpty());
    }

    @Test
    @DisplayName("TEST 2: 12-Hour Rest Violation (Night -> Morning next day) is BLOCKED")
    void testPreviewImpact_RestViolation_ReturnsBlocked() {
        RosterAssignment prevDayNight = new RosterAssignment();
        prevDayNight.setId(100L);
        prevDayNight.setCycle(testCycle);
        prevDayNight.setEmployee(rajatMale);
        prevDayNight.setRosterDate(LocalDate.of(2026, 9, 1));
        prevDayNight.setShift(nightShift);

        RosterAssignment currentAssignment = new RosterAssignment();
        currentAssignment.setId(101L);
        currentAssignment.setCycle(testCycle);
        currentAssignment.setEmployee(rajatMale);
        currentAssignment.setRosterDate(LocalDate.of(2026, 9, 2));
        currentAssignment.setShift(generalShift);

        when(assignmentRepository.findById(101L)).thenReturn(Optional.of(currentAssignment));
        when(leaveRequestRepository.findByStatusOrderByRequestedAtAsc(LeaveStatus.APPROVED)).thenReturn(Collections.emptyList());
        when(assignmentRepository.findByEmployeeIdAndRosterDate(1L, LocalDate.of(2026, 9, 1))).thenReturn(List.of(prevDayNight));
        when(rosterService.hasMinimumRest(eq(LocalDate.of(2026, 9, 1)), eq(nightShift), eq(LocalDate.of(2026, 9, 2)), eq(morningShift))).thenReturn(false);
        when(assignmentRepository.findByEmployeeIdAndRosterDate(1L, LocalDate.of(2026, 9, 3))).thenReturn(Collections.emptyList());
        when(assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(testCycle)).thenReturn(List.of(prevDayNight, currentAssignment));
        when(healthService.evaluateHealth(eq(testCycle), anyList())).thenReturn(createMockHealth(70.0));
        when(preferenceRepository.findTopByEmployeeIdAndStatusOrderByCreatedAtDesc(1L, PreferenceStatus.APPROVED)).thenReturn(Optional.empty());
        when(preferenceRepository.findByEmployeeIdOrderByCreatedAtDesc(1L)).thenReturn(Collections.emptyList());

        RosterChangeImpactResponse response = impactService.previewImpact(101L, ShiftType.MORNING, false);

        assertNotNull(response);
        assertEquals("BLOCKED", response.impactStatus());
        assertFalse(response.canApply());
        assertEquals("Blocked", response.restImpact());
        assertTrue(response.blockers().stream().anyMatch(b -> b.contains("12-hour rest rule violated")));
    }

    @Test
    @DisplayName("TEST 3 & 4: Female assigned to EVENING or NIGHT is strictly BLOCKED")
    void testPreviewImpact_FemaleEveningOrNight_ReturnsBlocked() {
        RosterAssignment femaleAssignment = new RosterAssignment();
        femaleAssignment.setId(201L);
        femaleAssignment.setCycle(testCycle);
        femaleAssignment.setEmployee(priyaFemale);
        femaleAssignment.setRosterDate(LocalDate.of(2026, 9, 2));
        femaleAssignment.setShift(morningShift);

        when(assignmentRepository.findById(201L)).thenReturn(Optional.of(femaleAssignment));
        when(leaveRequestRepository.findByStatusOrderByRequestedAtAsc(LeaveStatus.APPROVED)).thenReturn(Collections.emptyList());
        when(assignmentRepository.findByEmployeeIdAndRosterDate(2L, LocalDate.of(2026, 9, 1))).thenReturn(Collections.emptyList());
        when(assignmentRepository.findByEmployeeIdAndRosterDate(2L, LocalDate.of(2026, 9, 3))).thenReturn(Collections.emptyList());
        when(assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(testCycle)).thenReturn(List.of(femaleAssignment));
        when(healthService.evaluateHealth(eq(testCycle), anyList())).thenReturn(createMockHealth(60.0));
        when(preferenceRepository.findTopByEmployeeIdAndStatusOrderByCreatedAtDesc(2L, PreferenceStatus.APPROVED)).thenReturn(Optional.empty());
        when(preferenceRepository.findByEmployeeIdOrderByCreatedAtDesc(2L)).thenReturn(Collections.emptyList());

        // Female to Evening
        RosterChangeImpactResponse eveResponse = impactService.previewImpact(201L, ShiftType.EVENING, false);
        assertEquals("BLOCKED", eveResponse.impactStatus());
        assertFalse(eveResponse.canApply());
        assertEquals("Blocked", eveResponse.genderImpact());
        assertTrue(eveResponse.blockers().stream().anyMatch(b -> b.contains("Female safety policy violation")));

        // Female to Night
        RosterChangeImpactResponse nightResponse = impactService.previewImpact(201L, ShiftType.NIGHT, false);
        assertEquals("BLOCKED", nightResponse.impactStatus());
        assertFalse(nightResponse.canApply());
        assertEquals("Blocked", nightResponse.genderImpact());
    }

    @Test
    @DisplayName("TEST 5: Male exceeding 2 Night shifts (Night 2 -> Night 3) is BLOCKED")
    void testPreviewImpact_MaleExceedingTwoNights_ReturnsBlocked() {
        RosterAssignment night1 = new RosterAssignment();
        night1.setId(301L);
        night1.setCycle(testCycle);
        night1.setEmployee(rajatMale);
        night1.setRosterDate(LocalDate.of(2026, 8, 31));
        night1.setShift(nightShift);

        RosterAssignment night2 = new RosterAssignment();
        night2.setId(302L);
        night2.setCycle(testCycle);
        night2.setEmployee(rajatMale);
        night2.setRosterDate(LocalDate.of(2026, 9, 1));
        night2.setShift(nightShift);

        RosterAssignment targetGen = new RosterAssignment();
        targetGen.setId(303L);
        targetGen.setCycle(testCycle);
        targetGen.setEmployee(rajatMale);
        targetGen.setRosterDate(LocalDate.of(2026, 9, 4));
        targetGen.setShift(generalShift);

        when(assignmentRepository.findById(303L)).thenReturn(Optional.of(targetGen));
        when(leaveRequestRepository.findByStatusOrderByRequestedAtAsc(LeaveStatus.APPROVED)).thenReturn(Collections.emptyList());
        when(assignmentRepository.findByEmployeeIdAndRosterDate(1L, LocalDate.of(2026, 9, 3))).thenReturn(Collections.emptyList());
        when(assignmentRepository.findByEmployeeIdAndRosterDate(1L, LocalDate.of(2026, 9, 5))).thenReturn(Collections.emptyList());
        when(assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(testCycle)).thenReturn(List.of(night1, night2, targetGen));
        when(healthService.evaluateHealth(eq(testCycle), anyList())).thenReturn(createMockHealth(65.0));
        when(preferenceRepository.findTopByEmployeeIdAndStatusOrderByCreatedAtDesc(1L, PreferenceStatus.APPROVED)).thenReturn(Optional.empty());
        when(preferenceRepository.findByEmployeeIdOrderByCreatedAtDesc(1L)).thenReturn(Collections.emptyList());

        RosterChangeImpactResponse response = impactService.previewImpact(303L, ShiftType.NIGHT, false);

        assertNotNull(response);
        assertEquals("BLOCKED", response.impactStatus());
        assertFalse(response.canApply());
        assertEquals("Blocked", response.nightImpact());
        assertTrue(response.blockers().stream().anyMatch(b -> b.contains("Maximum 2 Night shifts limit exceeded")));
    }

    @Test
    @DisplayName("TEST 7: Avoided shift preference triggers WARNING but permits Admin override")
    void testPreviewImpact_AvoidedShift_ReturnsWarning() {
        RosterAssignment assignment = new RosterAssignment();
        assignment.setId(401L);
        assignment.setCycle(testCycle);
        assignment.setEmployee(rajatMale);
        assignment.setRosterDate(LocalDate.of(2026, 9, 2));
        assignment.setShift(generalShift);

        EmployeePreference pref = new EmployeePreference();
        pref.setId(10L);
        pref.setEmployee(rajatMale);
        pref.setPreferredShiftTypes("MORNING,GENERAL");
        pref.setAvoidShiftTypes("EVENING");
        pref.setStatus(PreferenceStatus.APPROVED);

        when(assignmentRepository.findById(401L)).thenReturn(Optional.of(assignment));
        when(leaveRequestRepository.findByStatusOrderByRequestedAtAsc(LeaveStatus.APPROVED)).thenReturn(Collections.emptyList());
        when(assignmentRepository.findByEmployeeIdAndRosterDate(1L, LocalDate.of(2026, 9, 1))).thenReturn(Collections.emptyList());
        when(assignmentRepository.findByEmployeeIdAndRosterDate(1L, LocalDate.of(2026, 9, 3))).thenReturn(Collections.emptyList());
        when(assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(testCycle)).thenReturn(List.of(assignment));
        when(healthService.evaluateHealth(eq(testCycle), anyList())).thenReturn(createMockHealth(88.0));
        when(preferenceRepository.findTopByEmployeeIdAndStatusOrderByCreatedAtDesc(1L, PreferenceStatus.APPROVED)).thenReturn(Optional.of(pref));

        RosterChangeImpactResponse response = impactService.previewImpact(401L, ShiftType.EVENING, false);

        assertNotNull(response);
        assertEquals("WARNING", response.impactStatus());
        assertTrue(response.canApply());
        assertTrue(response.requiresAdminConfirmation());
        assertEquals("Avoided", response.preferenceImpact());
        assertTrue(response.warnings().stream().anyMatch(w -> w.contains("avoid-shift conflict")));
    }

    @Test
    @DisplayName("TEST 8: Applying change records Admin Override and Audit Trail")
    void testApplyChange_RecordsOverrideAndAudit() {
        RosterAssignment assignment = new RosterAssignment();
        assignment.setId(501L);
        assignment.setCycle(testCycle);
        assignment.setEmployee(rajatMale);
        assignment.setRosterDate(LocalDate.of(2026, 9, 2));
        assignment.setShift(generalShift);
        assignment.setWeeklyOff(false);

        when(assignmentRepository.findById(501L)).thenReturn(Optional.of(assignment));
        when(leaveRequestRepository.findByStatusOrderByRequestedAtAsc(LeaveStatus.APPROVED)).thenReturn(Collections.emptyList());
        when(assignmentRepository.findByEmployeeIdAndRosterDate(1L, LocalDate.of(2026, 9, 1))).thenReturn(Collections.emptyList());
        when(assignmentRepository.findByEmployeeIdAndRosterDate(1L, LocalDate.of(2026, 9, 3))).thenReturn(Collections.emptyList());
        when(assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(testCycle)).thenReturn(List.of(assignment));
        when(healthService.evaluateHealth(eq(testCycle), anyList())).thenReturn(createMockHealth(94.0));
        when(preferenceRepository.findTopByEmployeeIdAndStatusOrderByCreatedAtDesc(1L, PreferenceStatus.APPROVED)).thenReturn(Optional.empty());
        when(preferenceRepository.findByEmployeeIdOrderByCreatedAtDesc(1L)).thenReturn(Collections.emptyList());

        when(assignmentRepository.save(any(RosterAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        RosterAssignmentResponse result = impactService.applyChangeWithValidation(501L, ShiftType.MORNING, false, "Operational need");

        assertNotNull(result);
        verify(overrideRepository, times(1)).save(any(RosterOverride.class));
        verify(auditService, times(1)).log(eq(AuditAction.SHIFT_OVERRIDDEN), eq("ROSTER_ASSIGNMENT"), eq(501L), any(), any(), any(), any(), any(), eq("Operational need"), eq("ADMIN"));
    }

    @Test
    @DisplayName("TEST 10: Locked / FINAL Roster prohibits manual change")
    void testPreviewImpact_FinalLockedRoster_ReturnsBlocked() {
        testCycle.setStatus(RosterStatus.FINAL);
        testCycle.setLockedAt(LocalDateTime.now());

        RosterAssignment assignment = new RosterAssignment();
        assignment.setId(601L);
        assignment.setCycle(testCycle);
        assignment.setEmployee(rajatMale);
        assignment.setRosterDate(LocalDate.of(2026, 9, 2));
        assignment.setShift(generalShift);

        when(assignmentRepository.findById(601L)).thenReturn(Optional.of(assignment));
        when(leaveRequestRepository.findByStatusOrderByRequestedAtAsc(LeaveStatus.APPROVED)).thenReturn(Collections.emptyList());
        when(assignmentRepository.findByEmployeeIdAndRosterDate(1L, LocalDate.of(2026, 9, 1))).thenReturn(Collections.emptyList());
        when(assignmentRepository.findByEmployeeIdAndRosterDate(1L, LocalDate.of(2026, 9, 3))).thenReturn(Collections.emptyList());
        when(assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(testCycle)).thenReturn(List.of(assignment));
        when(healthService.evaluateHealth(eq(testCycle), anyList())).thenReturn(createMockHealth(95.0));
        when(preferenceRepository.findTopByEmployeeIdAndStatusOrderByCreatedAtDesc(1L, PreferenceStatus.APPROVED)).thenReturn(Optional.empty());
        when(preferenceRepository.findByEmployeeIdOrderByCreatedAtDesc(1L)).thenReturn(Collections.emptyList());

        RosterChangeImpactResponse response = impactService.previewImpact(601L, ShiftType.MORNING, false);

        assertNotNull(response);
        assertEquals("BLOCKED", response.impactStatus());
        assertFalse(response.canApply());
        assertTrue(response.blockers().stream().anyMatch(b -> b.contains("FINAL / LOCKED")));
    }
}
