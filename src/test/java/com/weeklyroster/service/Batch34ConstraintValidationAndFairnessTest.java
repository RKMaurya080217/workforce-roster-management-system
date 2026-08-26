package com.weeklyroster.service;

import com.weeklyroster.dto.response.ConflictItem;
import com.weeklyroster.dto.response.EmployeeWorkloadMetric;
import com.weeklyroster.dto.response.RosterAssignmentResponse;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.EmployeePreferenceRepository;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.LeaveRequestRepository;
import com.weeklyroster.repository.RosterCycleRepository;
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
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class Batch34ConstraintValidationAndFairnessTest {

    @Autowired
    private RosterService rosterService;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private EmployeePreferenceRepository preferenceRepository;

    @Autowired
    private LeaveRequestRepository leaveRepository;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @BeforeEach
    void setUpSecurity() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "admin",
                "N/A",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    @DisplayName("Case A & B: Male Night Allocation & Validation Engine Scores")
    void testCaseA_B_MaleNightAllocationAndValidationScores() {
        LocalDate startDate = LocalDate.of(2027, 3, 1); // Monday
        LocalDate endDate = LocalDate.of(2027, 3, 7);

        RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);
        assertNotNull(res);

        assertEquals("VALID", res.generationStatus(), "Generation status must be VALID");
        assertEquals(0, res.criticalConflicts(), "Critical conflicts must be 0");
        assertTrue(res.healthScore() >= 85.0, "Health score should be >= 85.0%, got: " + res.healthScore());
        assertTrue(res.preferenceComplianceScore() >= 70.0, "Preference compliance should be >= 70.0%, got: " + res.preferenceComplianceScore());
        assertNotNull(res.maleNightCoverage());
        assertTrue(res.maleNightCoverage().contains("satisfied"));

        List<RosterAssignmentResponse> assignments = res.assignments();
        List<Employee> activeMales = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.MALE)
                .toList();

        Map<Long, List<RosterAssignmentResponse>> byEmp = assignments.stream()
                .collect(Collectors.groupingBy(RosterAssignmentResponse::employeeId));

        for (Employee male : activeMales) {
            List<RosterAssignmentResponse> assigns = byEmp.getOrDefault(male.getId(), Collections.emptyList());
            long nightCount = assigns.stream()
                    .filter(a -> !a.weeklyOff() && !a.onLeave() && a.shiftType() == ShiftType.NIGHT)
                    .count();
            assertTrue(nightCount >= 1, "Male " + male.getEmployeeCode() + " must receive >= 1 night shift. Got: " + nightCount);
            assertTrue(nightCount <= 2, "Male " + male.getEmployeeCode() + " must receive <= 2 night shifts. Got: " + nightCount);
        }
    }

    @Test
    @DisplayName("Case D: Female Employees Restricted - 0 NIGHT and 0 EVENING")
    void testCaseD_FemaleEmployeesRestricted() {
        LocalDate startDate = LocalDate.of(2027, 3, 8); // Monday
        RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);
        assertNotNull(res);

        List<Employee> females = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.FEMALE)
                .toList();

        Map<Long, List<RosterAssignmentResponse>> byEmp = res.assignments().stream()
                .collect(Collectors.groupingBy(RosterAssignmentResponse::employeeId));

        for (Employee female : females) {
            List<RosterAssignmentResponse> assigns = byEmp.getOrDefault(female.getId(), Collections.emptyList());
            for (RosterAssignmentResponse a : assigns) {
                if (!a.weeklyOff() && !a.onLeave() && a.shiftType() != null) {
                    assertNotEquals(ShiftType.NIGHT, a.shiftType(), "Female employee cannot be assigned NIGHT shift");
                    assertNotEquals(ShiftType.EVENING, a.shiftType(), "Female employee cannot be assigned EVENING shift");
                }
            }
        }
    }

    @Test
    @DisplayName("Case F: Avoid Evening Preference Respected")
    void testCaseF_AvoidEveningPreferenceRespected() {
        LocalDate startDate = LocalDate.of(2027, 3, 15); // Monday
        Employee emp = employeeRepository.findByEmployeeCode("emp001").orElse(null);
        if (emp != null) {
            EmployeePreference pref = new EmployeePreference();
            pref.setEmployee(emp);
            pref.setPreferredShiftTypes("MORNING,GENERAL");
            pref.setAvoidShiftTypes("EVENING");
            pref.setPreferredOffDays("SUNDAY");
            pref.setStatus(PreferenceStatus.APPROVED);
            pref.setEffectiveFrom(startDate.minusDays(10));
            preferenceRepository.save(pref);
        }

        RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);
        assertNotNull(res);

        if (emp != null) {
            List<RosterAssignmentResponse> empAssigns = res.assignments().stream()
                    .filter(a -> a.employeeId().equals(emp.getId()))
                    .toList();

            for (RosterAssignmentResponse a : empAssigns) {
                if (!a.weeklyOff() && !a.onLeave() && a.shiftType() != null) {
                    assertNotEquals(ShiftType.EVENING, a.shiftType(), "Avoided shift EVENING must not be assigned");
                }
            }
        }
    }

    @Test
    @DisplayName("Case G: Preferred Sunday OFF Day Fulfillment")
    void testCaseG_PreferredSundayOffFulfillment() {
        LocalDate startDate = LocalDate.of(2027, 3, 22); // Monday

        Employee emp = employeeRepository.findByEmployeeCode("emp001").orElse(null);
        if (emp != null) {
            EmployeePreference pref = new EmployeePreference();
            pref.setEmployee(emp);
            pref.setPreferredOffDays("SUNDAY");
            pref.setPreferredShiftTypes("MORNING");
            pref.setStatus(PreferenceStatus.APPROVED);
            pref.setEffectiveFrom(startDate.minusDays(10));
            preferenceRepository.save(pref);
        }

        RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);
        assertNotNull(res);

        if (emp != null) {
            RosterAssignmentResponse sundayAssign = res.assignments().stream()
                    .filter(a -> a.employeeId().equals(emp.getId()) && a.rosterDate().getDayOfWeek() == DayOfWeek.SUNDAY)
                    .findFirst()
                    .orElse(null);

            assertNotNull(sundayAssign, "Sunday assignment must exist");
            assertTrue(sundayAssign.weeklyOff() || sundayAssign.onLeave() || sundayAssign.shiftType() == ShiftType.OFF,
                    "Employee with Sunday OFF preference should receive Sunday OFF when feasible");
        }
    }

    @Test
    @DisplayName("Case H: Assignment Reasons and Health Score Transparency")
    void testCaseH_AssignmentReasonsAndHealthScoreTransparency() {
        LocalDate startDate = LocalDate.of(2027, 3, 29); // Monday
        RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);
        assertNotNull(res);

        for (RosterAssignmentResponse a : res.assignments()) {
            assertNotNull(a.assignmentReason(), "Every assignment must have an assignmentReason");
            assertFalse(a.assignmentReason().isBlank(), "Assignment reason cannot be empty");
        }

        assertTrue(res.healthScore() > 0.0, "Health score must be greater than 0");
        assertNotNull(res.workloadMetrics(), "Workload metrics should be calculated");
    }

    @Test
    @DisplayName("Rajat Scenario: Full End-to-End Regression")
    void testRajatScenarioFullValidation() {
        LocalDate startDate = LocalDate.of(2027, 4, 5); // Monday
        Employee rajat = employeeRepository.findByEmployeeCode("emp001").orElse(null);
        if (rajat != null) {
            EmployeePreference pref = new EmployeePreference();
            pref.setEmployee(rajat);
            pref.setPreferredShiftTypes("MORNING,GENERAL,NIGHT");
            pref.setAvoidShiftTypes("EVENING");
            pref.setPreferredOffDays("SUNDAY");
            pref.setPreferredWorkingDays("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY");
            pref.setStatus(PreferenceStatus.APPROVED);
            pref.setEffectiveFrom(startDate.minusDays(10));
            preferenceRepository.save(pref);
        }

        RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);
        assertNotNull(res);
        assertEquals("VALID", res.generationStatus());
        assertEquals(0, res.criticalConflicts());

        if (rajat != null) {
            List<RosterAssignmentResponse> rajatAssigns = res.assignments().stream()
                    .filter(a -> a.employeeId().equals(rajat.getId()))
                    .toList();

            long nightCount = rajatAssigns.stream().filter(a -> a.shiftType() == ShiftType.NIGHT).count();
            long eveningCount = rajatAssigns.stream().filter(a -> a.shiftType() == ShiftType.EVENING).count();

            assertTrue(nightCount >= 1, "Rajat must receive >= 1 night shift");
            assertTrue(nightCount <= 2, "Rajat must receive <= 2 night shifts");
            assertEquals(0, eveningCount, "Rajat must receive 0 evening shifts");
        }
    }

    @Test
    @DisplayName("Regeneration Dynamic Consistency: Generate -> Delete -> Regenerate")
    void testRegenerationDynamicConsistency() {
        LocalDate startDate = LocalDate.of(2027, 4, 12); // Monday
        RosterCycleResponse res1 = rosterService.generateWeeklyRoster(startDate);
        assertNotNull(res1);
        Long cycleId = res1.id();

        // Delete cycle
        rosterService.deleteCycle(cycleId);
        assertFalse(cycleRepository.findById(cycleId).isPresent(), "Cycle must be deleted");

        // Regenerate cycle
        RosterCycleResponse res2 = rosterService.generateWeeklyRoster(startDate);
        assertNotNull(res2);
        assertEquals("VALID", res2.generationStatus());
        assertEquals(0, res2.criticalConflicts());
    }
}
