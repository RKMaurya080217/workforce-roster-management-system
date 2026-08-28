package com.weeklyroster.service;

import com.weeklyroster.dto.response.EmployeeWorkloadMetric;
import com.weeklyroster.dto.response.RosterAssignmentResponse;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.EmployeePreferenceRepository;
import com.weeklyroster.repository.EmployeeRepository;
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
public class Batch35IntelligentBalancedRosterTest {

    @Autowired
    private RosterService rosterService;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private EmployeePreferenceRepository preferenceRepository;

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
    @DisplayName("Requirement 1 & 2: Male Night Allocation (1-2 Night per male, exactly 1 Night per day)")
    void testMaleNightAllocationAndDailyCoverage() {
        LocalDate startDate = LocalDate.of(2027, 10, 4); // Monday
        LocalDate endDate = LocalDate.of(2027, 10, 10);

        RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);
        assertNotNull(res);
        assertEquals("VALID", res.generationStatus());
        assertEquals(0, res.criticalConflicts());

        List<RosterAssignmentResponse> assignments = res.assignments();
        List<Employee> activeMales = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.MALE)
                .toList();

        Map<Long, List<RosterAssignmentResponse>> byEmp = assignments.stream()
                .collect(Collectors.groupingBy(RosterAssignmentResponse::employeeId));

        // Every eligible male receives >= 1 and <= 2 NIGHT
        for (Employee male : activeMales) {
            List<RosterAssignmentResponse> assigns = byEmp.getOrDefault(male.getId(), Collections.emptyList());
            long nightCount = assigns.stream()
                    .filter(a -> !a.weeklyOff() && !a.onLeave() && a.shiftType() == ShiftType.NIGHT)
                    .count();
            assertTrue(nightCount >= 1, "Male " + male.getEmployeeCode() + " must have >= 1 night. Got: " + nightCount);
            assertTrue(nightCount <= 2, "Male " + male.getEmployeeCode() + " must have <= 2 night. Got: " + nightCount);
        }

        // Exactly 1 NIGHT per day
        Map<LocalDate, List<RosterAssignmentResponse>> byDate = assignments.stream()
                .collect(Collectors.groupingBy(RosterAssignmentResponse::rosterDate));

        for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            List<RosterAssignmentResponse> dayAssigns = byDate.getOrDefault(d, Collections.emptyList());
            long nightCountOnDay = dayAssigns.stream()
                    .filter(a -> !a.weeklyOff() && !a.onLeave() && a.shiftType() == ShiftType.NIGHT)
                    .count();
            assertEquals(1, nightCountOnDay, "Exactly 1 NIGHT shift must be assigned on " + d);
        }
    }

    @Test
    @DisplayName("Requirement 3: Female Shift Balance (0 Night, 0 Evening, balanced Morning and General)")
    void testFemaleShiftBalance() {
        LocalDate startDate = LocalDate.of(2027, 10, 11); // Monday

        RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);
        assertNotNull(res);
        assertEquals("VALID", res.generationStatus());

        List<Employee> females = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.FEMALE)
                .toList();

        Map<Long, List<RosterAssignmentResponse>> byEmp = res.assignments().stream()
                .collect(Collectors.groupingBy(RosterAssignmentResponse::employeeId));

        for (Employee female : females) {
            List<RosterAssignmentResponse> assigns = byEmp.getOrDefault(female.getId(), Collections.emptyList());
            int morningCount = 0;
            int generalCount = 0;

            for (RosterAssignmentResponse a : assigns) {
                if (!a.weeklyOff() && !a.onLeave() && a.shiftType() != null) {
                    assertNotEquals(ShiftType.NIGHT, a.shiftType(), "Female employee must not be assigned NIGHT");
                    assertNotEquals(ShiftType.EVENING, a.shiftType(), "Female employee must not be assigned EVENING");

                    if (a.shiftType() == ShiftType.MORNING) morningCount++;
                    if (a.shiftType() == ShiftType.GENERAL) generalCount++;
                }
            }

            // Verify both Morning and General shifts are represented when working full week without restrictive preference
            assertTrue(morningCount > 0, "Female " + female.getEmployeeCode() + " should receive Morning shifts");
            assertTrue(generalCount > 0, "Female " + female.getEmployeeCode() + " should receive General shifts");
            assertTrue(Math.abs(morningCount - generalCount) <= 4, "Female shifts should be reasonably balanced");
        }
    }

    @Test
    @DisplayName("Requirement 4 & 5: Male Shift Balance (Not overloaded with Evening/Night)")
    void testMaleShiftBalance() {
        LocalDate startDate = LocalDate.of(2027, 10, 18); // Monday

        RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);
        assertNotNull(res);

        List<Employee> males = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.MALE)
                .toList();

        Map<Long, List<RosterAssignmentResponse>> byEmp = res.assignments().stream()
                .collect(Collectors.groupingBy(RosterAssignmentResponse::employeeId));

        for (RosterAssignmentResponse a : res.assignments()) {
            System.out.println("ASSIGN: " + a.rosterDate() + " | " + a.employeeCode() + " | " + a.shiftType() + " | " + (a.weeklyOff() ? "OFF" : "") + " | " + (a.onLeave() ? "LEAVE" : ""));
        }

        for (Employee male : males) {
            List<RosterAssignmentResponse> assigns = byEmp.getOrDefault(male.getId(), Collections.emptyList());
            long eveningCount = assigns.stream().filter(a -> a.shiftType() == ShiftType.EVENING).count();
            long nightCount = assigns.stream().filter(a -> a.shiftType() == ShiftType.NIGHT).count();
            long dayCount = assigns.stream().filter(a -> a.shiftType() == ShiftType.MORNING || a.shiftType() == ShiftType.GENERAL).count();

            System.out.println("TEST_MALE_BALANCE: " + male.getEmployeeCode() + " -> Eve: " + eveningCount + ", Night: " + nightCount + ", Day: " + dayCount);
            // Total working duties should have healthy day-shift participation
            assertTrue(nightCount <= 2, "Male must have <= 2 nights");
            assertTrue(eveningCount <= 3, "Male should not be overloaded with evenings (<= 3)");
            assertTrue(dayCount >= 1, "Male should receive day duties (Morning or General)");
        }
    }

    @Test
    @DisplayName("Requirement 6-10: Shift Block Continuity (Multi-day same-shift blocks preferred over daily switching)")
    void testShiftBlockContinuity() {
        LocalDate startDate = LocalDate.of(2027, 10, 25); // Monday

        RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);
        assertNotNull(res);

        assertNotNull(res.shiftContinuityScore(), "Shift continuity score should be present");
        assertTrue(res.shiftContinuityScore() >= 65.0, "Shift continuity score should be >= 65%, got: " + res.shiftContinuityScore());

        Map<Long, List<RosterAssignmentResponse>> byEmp = res.assignments().stream()
                .collect(Collectors.groupingBy(RosterAssignmentResponse::employeeId));

        int totalConsecutiveSameShifts = 0;
        int totalWorkingTransitions = 0;

        for (List<RosterAssignmentResponse> list : byEmp.values()) {
            List<RosterAssignmentResponse> working = list.stream()
                    .filter(a -> !a.weeklyOff() && !a.onLeave() && a.shiftType() != null && a.shiftType() != ShiftType.OFF)
                    .sorted(Comparator.comparing(RosterAssignmentResponse::rosterDate))
                    .toList();

            for (int i = 0; i < working.size() - 1; i++) {
                totalWorkingTransitions++;
                if (working.get(i).shiftType() == working.get(i + 1).shiftType()) {
                    totalConsecutiveSameShifts++;
                }
            }
        }

        assertTrue(totalConsecutiveSameShifts > 0, "Roster must exhibit multi-day same-shift blocks");
    }

    @Test
    @DisplayName("Requirement 11-14: Preference Integration (Avoid Evening, Preferred Shift, Preferred Sunday OFF)")
    void testPreferenceIntegration() {
        LocalDate startDate = LocalDate.of(2027, 11, 1); // Monday

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

        if (rajat != null) {
            List<RosterAssignmentResponse> rajatAssigns = res.assignments().stream()
                    .filter(a -> a.employeeId().equals(rajat.getId()))
                    .toList();

            long eveningCount = rajatAssigns.stream().filter(a -> a.shiftType() == ShiftType.EVENING).count();
            assertEquals(0, eveningCount, "Approved Avoid Shift EVENING must strictly have 0 assignments");

            long nightCount = rajatAssigns.stream().filter(a -> a.shiftType() == ShiftType.NIGHT).count();
            assertTrue(nightCount >= 1 && nightCount <= 2, "Rajat must have 1-2 night shifts");

            RosterAssignmentResponse sundayAssign = rajatAssigns.stream()
                    .filter(a -> a.rosterDate().getDayOfWeek() == DayOfWeek.SUNDAY)
                    .findFirst()
                    .orElse(null);
            assertNotNull(sundayAssign);
            assertTrue(sundayAssign.weeklyOff() || sundayAssign.onLeave() || sundayAssign.shiftType() == ShiftType.OFF,
                    "Preferred Sunday OFF must be fulfilled");
        }
    }

    @Test
    @DisplayName("Requirement 15-18: Workload Balancing and Roster Quality Dimensions")
    void testWorkloadBalancingAndQualityReport() {
        LocalDate startDate = LocalDate.of(2027, 11, 8); // Monday

        RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);
        assertNotNull(res);

        assertNotNull(res.workloadMetrics());
        assertFalse(res.workloadMetrics().isEmpty());
        assertNotNull(res.shiftContinuityScore());
        assertNotNull(res.workloadBalanceScore());
        assertTrue(res.healthScore() >= 80.0, "Health score must be >= 80%, got: " + res.healthScore());

        for (EmployeeWorkloadMetric m : res.workloadMetrics()) {
            assertEquals(1, m.offDays(), "Every employee must have exactly 1 Weekly OFF");
            assertEquals(6, m.workingDays(), "Every active employee works exactly 6 days");
        }
    }

    @Test
    @DisplayName("Requirement 24: Dynamic Regeneration Consistency (Non-deterministic, fresh calculation)")
    void testDynamicRegeneration() {
        LocalDate startDate = LocalDate.of(2027, 11, 15); // Monday

        RosterCycleResponse run1 = rosterService.generateWeeklyRoster(startDate);
        assertNotNull(run1);
        Long id1 = run1.id();

        // Delete cycle
        rosterService.deleteCycle(id1);
        assertFalse(cycleRepository.findById(id1).isPresent());

        // Regenerate cycle
        RosterCycleResponse run2 = rosterService.generateWeeklyRoster(startDate);
        assertNotNull(run2);
        assertEquals("VALID", run2.generationStatus());
        assertEquals(0, run2.criticalConflicts());
    }
}
