package com.weeklyroster.service;

import com.weeklyroster.dto.ApplicablePreference;
import com.weeklyroster.dto.response.RosterHealthReport;
import com.weeklyroster.dto.response.ShiftExplanationResponse;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.Gender;
import com.weeklyroster.entity.RosterAssignment;
import com.weeklyroster.entity.RosterCycle;
import com.weeklyroster.entity.RosterStatus;
import com.weeklyroster.entity.Shift;
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.entity.User;
import org.springframework.security.access.AccessDeniedException;
import com.weeklyroster.repository.EmployeePreferenceRepository;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.LeaveRequestRepository;
import com.weeklyroster.repository.RosterAssignmentRepository;
import com.weeklyroster.repository.RosterCycleRepository;
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
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class Batch38ExplainableRosterAndHealthTest {

    @Mock
    private RosterCycleRepository cycleRepository;

    @Mock
    private RosterAssignmentRepository assignmentRepository;

    @Mock
    private LeaveRequestRepository leaveRequestRepository;

    @Mock
    private EmployeePreferenceRepository preferenceRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    private RosterHealthService healthService;

    @InjectMocks
    private RosterService rosterService;

    private RosterCycle cycle;
    private Employee empRajat;
    private Employee empPriya;
    private Shift shiftGeneral;
    private Shift shiftMorning;
    private Shift shiftEvening;
    private Shift shiftNight;
    private Shift shiftOff;

    @BeforeEach
    void setUp() {
        healthService = new RosterHealthService(cycleRepository, assignmentRepository, leaveRequestRepository, preferenceRepository);

        cycle = new RosterCycle();
        cycle.setId(100L);
        cycle.setStartDate(LocalDate.of(2026, 8, 31));
        cycle.setEndDate(LocalDate.of(2026, 9, 6));
        cycle.setStatus(RosterStatus.TENTATIVE);

        User userRajat = new User();
        userRajat.setUsername("emp001");

        empRajat = new Employee();
        empRajat.setId(1L);
        empRajat.setEmployeeCode("EMP001");
        empRajat.setFirstName("Rajat");
        empRajat.setLastName("Maurya");
        empRajat.setGender(Gender.MALE);
        empRajat.setActive(true);
        empRajat.setUser(userRajat);

        User userPriya = new User();
        userPriya.setUsername("emp002");

        empPriya = new Employee();
        empPriya.setId(2L);
        empPriya.setEmployeeCode("EMP002");
        empPriya.setFirstName("Priya");
        empPriya.setLastName("Sharma");
        empPriya.setGender(Gender.FEMALE);
        empPriya.setActive(true);
        empPriya.setUser(userPriya);

        shiftMorning = new Shift();
        shiftMorning.setId(1L);
        shiftMorning.setShiftType(ShiftType.MORNING);
        shiftMorning.setCapacity(2);

        shiftGeneral = new Shift();
        shiftGeneral.setId(2L);
        shiftGeneral.setShiftType(ShiftType.GENERAL);
        shiftGeneral.setCapacity(2);

        shiftEvening = new Shift();
        shiftEvening.setId(3L);
        shiftEvening.setShiftType(ShiftType.EVENING);
        shiftEvening.setCapacity(1);

        shiftNight = new Shift();
        shiftNight.setId(4L);
        shiftNight.setShiftType(ShiftType.NIGHT);
        shiftNight.setCapacity(1);

        shiftOff = new Shift();
        shiftOff.setId(5L);
        shiftOff.setShiftType(ShiftType.OFF);
        shiftOff.setCapacity(0);
    }

    @Test
    @DisplayName("Test Scenario 30: Why This Shift shows Preferred Shift reason")
    void testWhyThisShift_PreferredShift() {
        // Set admin auth
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "pass", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );

        RosterAssignment assignment = new RosterAssignment();
        assignment.setId(10L);
        assignment.setCycle(cycle);
        assignment.setEmployee(empRajat);
        assignment.setRosterDate(LocalDate.of(2026, 9, 4)); // Friday
        assignment.setShift(shiftGeneral);
        assignment.setWeeklyOff(false);
        assignment.setOnLeave(false);
        assignment.setOverridden(false);
        assignment.setAssignmentReason("Preferred Shift (GENERAL)");

        when(assignmentRepository.findById(10L)).thenReturn(Optional.of(assignment));
        when(assignmentRepository.findByEmployeeIdAndRosterDate(1L, LocalDate.of(2026, 9, 3))).thenReturn(Collections.emptyList());
        when(assignmentRepository.findByEmployeeIdAndRosterDate(1L, LocalDate.of(2026, 9, 5))).thenReturn(Collections.emptyList());

        ShiftExplanationResponse explanation = rosterService.getShiftExplanation(10L);

        assertNotNull(explanation);
        assertEquals("Rajat Maurya", explanation.employeeName());
        assertEquals(ShiftType.GENERAL, explanation.shiftType());
        assertFalse(explanation.overridden());
        assertNotNull(explanation.adminDetails()); // Admin has admin details

        boolean hasPref = explanation.reasons().stream()
                .anyMatch(r -> r.title().contains("Preferred") || r.description().contains("GENERAL"));
        assertTrue(hasPref, "Explanation should contain preference reason");
    }

    @Test
    @DisplayName("Test Scenario 32: Why This Shift prominently displays Admin Override")
    void testWhyThisShift_AdminOverride() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "pass", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );

        RosterAssignment assignment = new RosterAssignment();
        assignment.setId(12L);
        assignment.setCycle(cycle);
        assignment.setEmployee(empRajat);
        assignment.setRosterDate(LocalDate.of(2026, 9, 1));
        assignment.setShift(shiftMorning);
        assignment.setWeeklyOff(false);
        assignment.setOnLeave(false);
        assignment.setOverridden(true);
        assignment.setAssignmentReason("Admin Override: Changed by Admin to satisfy operational coverage");

        when(assignmentRepository.findById(12L)).thenReturn(Optional.of(assignment));
        when(assignmentRepository.findByEmployeeIdAndRosterDate(1L, LocalDate.of(2026, 8, 31))).thenReturn(Collections.emptyList());
        when(assignmentRepository.findByEmployeeIdAndRosterDate(1L, LocalDate.of(2026, 9, 2))).thenReturn(Collections.emptyList());

        ShiftExplanationResponse explanation = rosterService.getShiftExplanation(12L);

        assertNotNull(explanation);
        assertTrue(explanation.overridden());
        assertNotNull(explanation.adminOverrideReason());
        assertTrue(explanation.adminOverrideReason().contains("Admin to satisfy operational coverage"));

        boolean hasOverrideBadge = explanation.reasons().stream()
                .anyMatch(r -> "OVERRIDE".equals(r.category()) && r.title().contains("Admin Override"));
        assertTrue(hasOverrideBadge, "Explanation reasons must include Admin Override prominently");
    }

    @Test
    @DisplayName("Test Scenario 33: Why This Shift displays Optimized Assignment")
    void testWhyThisShift_OptimizedAssignment() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "pass", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );

        RosterAssignment assignment = new RosterAssignment();
        assignment.setId(14L);
        assignment.setCycle(cycle);
        assignment.setEmployee(empRajat);
        assignment.setRosterDate(LocalDate.of(2026, 9, 2));
        assignment.setShift(shiftGeneral);
        assignment.setWeeklyOff(false);
        assignment.setOnLeave(false);
        assignment.setOverridden(false);
        assignment.setAssignmentReason("Optimized: Shift changed from EVENING to GENERAL to improve preference compliance and shift continuity");

        when(assignmentRepository.findById(14L)).thenReturn(Optional.of(assignment));
        when(assignmentRepository.findByEmployeeIdAndRosterDate(1L, LocalDate.of(2026, 9, 1))).thenReturn(Collections.emptyList());
        when(assignmentRepository.findByEmployeeIdAndRosterDate(1L, LocalDate.of(2026, 9, 3))).thenReturn(Collections.emptyList());

        ShiftExplanationResponse explanation = rosterService.getShiftExplanation(14L);

        assertNotNull(explanation);
        assertTrue(explanation.optimized());
        assertNotNull(explanation.optimizationReason());

        boolean hasOptReason = explanation.reasons().stream()
                .anyMatch(r -> "OPTIMIZATION".equals(r.category()) && r.title().contains("Optimized Assignment"));
        assertTrue(hasOptReason, "Explanation reasons must include Optimized Assignment");
    }

    @Test
    @DisplayName("Test Scenario 34: Roster Health marks Hard Constraint Failure as INVALID (Never Excellent)")
    void testRosterHealth_HardConstraintRestViolation_IsInvalid() {
        // Create 2 assignments with 12-hour rest violation (Night -> Morning next day)
        RosterAssignment day1Night = new RosterAssignment();
        day1Night.setId(101L);
        day1Night.setCycle(cycle);
        day1Night.setEmployee(empRajat);
        day1Night.setRosterDate(LocalDate.of(2026, 8, 31));
        day1Night.setShift(shiftNight);

        RosterAssignment day2Morning = new RosterAssignment();
        day2Morning.setId(102L);
        day2Morning.setCycle(cycle);
        day2Morning.setEmployee(empRajat);
        day2Morning.setRosterDate(LocalDate.of(2026, 9, 1));
        day2Morning.setShift(shiftMorning);

        List<RosterAssignment> assignments = List.of(day1Night, day2Morning);

        RosterHealthReport report = healthService.evaluateHealth(cycle, assignments);

        assertNotNull(report);
        assertEquals("INVALID", report.overallValidationStatus());
        assertEquals("INVALID — HARD CONSTRAINT FAILURE", report.healthScoreStatus());
        assertEquals("FAILED", report.restRulesCheck());
        assertFalse(report.readyToPublish());
        assertNotEquals("Excellent", report.healthScoreStatus());
    }

    @Test
    @DisplayName("Test Scenario 35: Night Distribution validation for male and female staff")
    void testRosterHealth_NightDistribution() {
        List<RosterAssignment> assignments = new ArrayList<>();
        LocalDate start = cycle.getStartDate();

        // 7 days for Rajat (Male): 1 Night, 5 Morning, 1 OFF
        for (int i = 0; i < 7; i++) {
            LocalDate d = start.plusDays(i);
            RosterAssignment a = new RosterAssignment();
            a.setId((long) (200 + i));
            a.setCycle(cycle);
            a.setEmployee(empRajat);
            a.setRosterDate(d);
            if (i == 0) a.setShift(shiftNight);
            else if (i == 6) { a.setShift(shiftOff); a.setWeeklyOff(true); }
            else a.setShift(shiftMorning);
            assignments.add(a);
        }

        // 7 days for Priya (Female): 6 Morning, 1 OFF (0 Night, 0 Evening)
        for (int i = 0; i < 7; i++) {
            LocalDate d = start.plusDays(i);
            RosterAssignment a = new RosterAssignment();
            a.setId((long) (300 + i));
            a.setCycle(cycle);
            a.setEmployee(empPriya);
            a.setRosterDate(d);
            if (i == 6) { a.setShift(shiftOff); a.setWeeklyOff(true); }
            else a.setShift(shiftMorning);
            assignments.add(a);
        }

        RosterHealthReport report = healthService.evaluateHealth(cycle, assignments);

        assertNotNull(report);
        assertNotNull(report.nightDetails());
        assertEquals(0, report.nightDetails().femaleNightCount(), "Female night count must be strictly 0");
        assertEquals("PASSED", report.genderRulesCheck());
    }

    @Test
    @DisplayName("Test Security: Employee cannot view other employee's shift explanation")
    void testShiftExplanation_SecurityAccessDenied() {
        // Authenticated as emp001 (Rajat)
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("emp001", "pass", List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
        );

        // Assignment belonging to Priya (emp002)
        RosterAssignment priyaAssignment = new RosterAssignment();
        priyaAssignment.setId(50L);
        priyaAssignment.setCycle(cycle);
        priyaAssignment.setEmployee(empPriya);
        priyaAssignment.setRosterDate(LocalDate.of(2026, 9, 1));
        priyaAssignment.setShift(shiftMorning);

        when(assignmentRepository.findById(50L)).thenReturn(Optional.of(priyaAssignment));

        assertThrows(AccessDeniedException.class, () -> {
            rosterService.getShiftExplanation(50L);
        }, "Employee viewing another employee's explanation should throw AccessDeniedException");
    }

    @Test
    @DisplayName("Test Security: Employee viewing own explanation receives no sensitive admin details")
    void testShiftExplanation_EmployeeViewingOwnGetsNoAdminDetails() {
        // Authenticated as emp001 (Rajat)
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("emp001", "pass", List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
        );

        RosterAssignment rajatAssignment = new RosterAssignment();
        rajatAssignment.setId(60L);
        rajatAssignment.setCycle(cycle);
        rajatAssignment.setEmployee(empRajat);
        rajatAssignment.setRosterDate(LocalDate.of(2026, 9, 1));
        rajatAssignment.setShift(shiftMorning);

        when(assignmentRepository.findById(60L)).thenReturn(Optional.of(rajatAssignment));
        when(assignmentRepository.findByEmployeeIdAndRosterDate(1L, LocalDate.of(2026, 8, 31))).thenReturn(Collections.emptyList());
        when(assignmentRepository.findByEmployeeIdAndRosterDate(1L, LocalDate.of(2026, 9, 2))).thenReturn(Collections.emptyList());

        ShiftExplanationResponse explanation = rosterService.getShiftExplanation(60L);

        assertNotNull(explanation);
        assertEquals("Rajat Maurya", explanation.employeeName());
        assertNull(explanation.adminDetails(), "Employee view must NOT contain sensitive adminDetails");
    }
}
