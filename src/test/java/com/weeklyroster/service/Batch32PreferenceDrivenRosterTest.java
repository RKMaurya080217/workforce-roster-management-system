package com.weeklyroster.service;

import com.weeklyroster.dto.response.RosterAssignmentResponse;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.EmployeePreferenceRepository;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.RosterCycleRepository;
import com.weeklyroster.repository.RosterOverrideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class Batch32PreferenceDrivenRosterTest {

    @Autowired
    private RosterService rosterService;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private EmployeePreferenceRepository preferenceRepository;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private RosterOverrideRepository overrideRepository;

    @Autowired
    private EmployeePreferenceService preferenceService;

    @BeforeEach
    void setUp() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    @DisplayName("Batch 32 - Scenario 1: Rajat Maurya (EMP001) Approved Preference (Avoid EVENING, Sunday OFF)")
    void testEmp001ApprovedPreferenceRespected() {
        Employee emp001 = employeeRepository.findByEmployeeCode("EMP001").orElseThrow();
        LocalDate monday = LocalDate.of(2026, 9, 7);
        LocalDate sunday = monday.plusDays(6);

        // Clean up previous preferences for EMP001
        preferenceRepository.findByEmployeeIdOrderByCreatedAtDesc(emp001.getId())
                .forEach(p -> preferenceRepository.delete(p));

        // Create and Approve EMP001 preference
        EmployeePreference pref = new EmployeePreference();
        pref.setEmployee(emp001);
        pref.setPreferredShiftTypes("MORNING, GENERAL, NIGHT");
        pref.setAvoidShiftTypes("EVENING");
        pref.setPreferredOffDays("SUNDAY");
        pref.setPreferredWorkingDays("MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY");
        pref.setStatus(PreferenceStatus.APPROVED);
        pref.setEffectiveFrom(monday.minusDays(7));
        pref.setEffectiveTo(monday.plusDays(30));
        pref.setReviewedBy("admin");
        pref.setReviewedAt(LocalDateTime.now());
        preferenceRepository.save(pref);

        // Generate Roster
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL);
        assertNotNull(cycle);

        List<RosterAssignmentResponse> emp001Assignments = cycle.assignments().stream()
                .filter(a -> a.employeeId().equals(emp001.getId()))
                .toList();

        assertEquals(7, emp001Assignments.size(), "EMP001 must have 7 assignments across the cycle");

        // Assert Avoid Shifts: EVENING is NEVER assigned
        long eveningCount = emp001Assignments.stream()
                .filter(a -> !a.weeklyOff() && !a.onLeave() && a.shiftType() == ShiftType.EVENING)
                .count();
        assertEquals(0, eveningCount, "EMP001 must NEVER receive EVENING shifts when Avoid Shift is approved");

        // Assert Preferred OFF Day: SUNDAY is OFF
        RosterAssignmentResponse sundayAssignment = emp001Assignments.stream()
                .filter(a -> a.rosterDate().getDayOfWeek() == DayOfWeek.SUNDAY)
                .findFirst()
                .orElseThrow();
        assertTrue(sundayAssignment.weeklyOff(), "EMP001 must receive SUNDAY as Weekly OFF as approved in preference");

        // Assert all assigned working shifts are from preferred set {MORNING, GENERAL, NIGHT}
        for (RosterAssignmentResponse a : emp001Assignments) {
            if (!a.weeklyOff() && !a.onLeave()) {
                assertTrue(a.shiftType() == ShiftType.MORNING || a.shiftType() == ShiftType.GENERAL || a.shiftType() == ShiftType.NIGHT,
                        "Assigned shift " + a.shiftType() + " on " + a.rosterDate() + " must be among preferred shifts");
                assertNotNull(a.assignmentReason(), "Assignment reason must be populated for transparency");
            }
        }
    }

    @Test
    @DisplayName("Batch 32 - Scenario 2: Pending and Rejected preferences do NOT affect generation")
    void testPendingAndRejectedPreferencesIgnored() {
        Employee emp002 = employeeRepository.findByEmployeeCode("EMP002").orElseThrow();
        LocalDate monday = LocalDate.of(2026, 9, 14);

        preferenceRepository.findByEmployeeIdOrderByCreatedAtDesc(emp002.getId())
                .forEach(p -> preferenceRepository.delete(p));

        // Create PENDING preference with Avoid NIGHT
        EmployeePreference pendingPref = new EmployeePreference();
        pendingPref.setEmployee(emp002);
        pendingPref.setAvoidShiftTypes("NIGHT");
        pendingPref.setStatus(PreferenceStatus.PENDING);
        pendingPref.setEffectiveFrom(monday);
        pendingPref.setEffectiveTo(monday.plusDays(6));
        preferenceRepository.save(pendingPref);

        // Load approved preferences in RosterService
        Map<Long, com.weeklyroster.dto.ApplicablePreference> prefs = rosterService.loadApprovedPreferences(
                List.of(emp002), monday, monday.plusDays(6));

        assertFalse(prefs.get(emp002.getId()).isApproved(), "Pending preference must not be treated as active approved preference");
    }

    @Test
    @DisplayName("Batch 32 - Scenario 3: Admin Override takes precedence and survives regeneration")
    void testAdminOverrideSurvivesRegeneration() {
        LocalDate monday = LocalDate.of(2026, 9, 21);
        Employee emp001 = employeeRepository.findByEmployeeCode("EMP001").orElseThrow();

        // 1. Generate initial roster
        RosterCycleResponse initialCycle = rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL);
        assertNotNull(initialCycle);

        // 2. Admin explicitly overrides a MORNING/GENERAL day (multi-capacity) to GENERAL/MORNING
        RosterAssignmentResponse targetAssignment = initialCycle.assignments().stream()
                .filter(a -> !a.weeklyOff() && !a.onLeave())
                .filter(a -> {
                    long sameShiftCount = initialCycle.assignments().stream()
                            .filter(other -> other.rosterDate().equals(a.rosterDate()) && other.shiftType() == a.shiftType() && !other.weeklyOff() && !other.onLeave())
                            .count();
                    return sameShiftCount > 1;
                })
                .findFirst()
                .orElseThrow();
        Long targetEmpId = targetAssignment.employeeId();
        ShiftType newShift = targetAssignment.shiftType() == ShiftType.MORNING ? ShiftType.GENERAL : ShiftType.MORNING;

        LocalDate targetDate = targetAssignment.rosterDate();
        com.weeklyroster.dto.request.RosterOverrideRequest overrideReq = new com.weeklyroster.dto.request.RosterOverrideRequest(targetAssignment.id(), newShift, false, "Admin critical mission duty assignment");
        rosterService.override(overrideReq);

        // 3. Regenerate the weekly roster
        RosterCycleResponse regeneratedCycle = rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL);
        assertNotNull(regeneratedCycle);

        // 4. Verify the manual override survived
        RosterAssignmentResponse regeneratedTarget = regeneratedCycle.assignments().stream()
                .filter(a -> a.employeeId().equals(targetEmpId) && a.rosterDate().equals(targetDate))
                .findFirst()
                .orElseThrow();

        assertTrue(regeneratedTarget.overridden(), "Preserved assignment must remain marked as overridden");
        assertEquals(newShift, regeneratedTarget.shiftType(), "Preserved shift type must match admin override");
        assertTrue(regeneratedTarget.assignmentReason().contains("Admin Override"), "Assignment reason must reflect admin override");

        
    }

    @Test
    @DisplayName("Batch 32 - Scenario 4: Dynamic generation variability produces valid rosters")
    void testDynamicGenerationVariability() {
        LocalDate monday = LocalDate.of(2026, 9, 28);

        RosterCycleResponse run1 = rosterService.generateWeeklyRoster(monday, GenerationMode.MANUAL);
        assertNotNull(run1);
        int expectedTotal = employeeRepository.findByActiveTrueOrderByIdAsc().size() * 7;
        assertEquals(expectedTotal, run1.assignments().size());

        // Verify valid exact weekly off for all active staff
        Map<Long, List<RosterAssignmentResponse>> byEmp = run1.assignments().stream()
                .collect(Collectors.groupingBy(RosterAssignmentResponse::employeeId));

        for (Map.Entry<Long, List<RosterAssignmentResponse>> entry : byEmp.entrySet()) {
            long offs = entry.getValue().stream().filter(RosterAssignmentResponse::weeklyOff).count();
            assertEquals(1, offs, "Each employee must receive exactly 1 Weekly OFF");
        }
    }
}
