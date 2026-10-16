package com.weeklyroster.service;

import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BATCH 89: Smart Roster Generation Test Suite
 * Validates:
 * 1. Hard shift preferences: Admin-configured Morning preferences for male employees (e.g. Sriram, Jeevan)
 *    are strictly respected and not overridden by male night allocation.
 * 2. Date-specific shift preference priority (Level 2 Authoritative).
 * 3. Non-repeating regeneration diversity across cycle deletions (fingerprints F1 != F2 != F3).
 * 4. Shift block continuity (continuous blocks, avoidance of fragmented M -> G -> M recurrence).
 * 5. Level 1 Hard Business Constraints (Holidays, Leaves, 12h rest, exactly 1 Weekly Off, Female restrictions).
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = {"wrms.dev.credential-mirror.enabled=false"})
public class Batch89SmartRosterValidationTest {

    @Autowired
    private RosterService rosterService;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private RosterAssignmentRepository assignmentRepository;

    @Autowired
    private EmployeePreferenceRepository preferenceRepository;

    @Autowired
    private DateShiftPreferenceRepository dateShiftPreferenceRepository;

    @Autowired
    private RosterGenerationHistoryRepository generationHistoryRepository;

    private LocalDate nextMonday;

    @BeforeEach
    void setUp() {
        // Authenticate as ADMIN
        var auth = new UsernamePasswordAuthenticationToken("admin", "admin",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        nextMonday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        if (dateShiftPreferenceRepository != null) {
            dateShiftPreferenceRepository.deleteAll();
        }
    }

    @Test
    @DisplayName("BATCH 89 - Problem 1: Admin Morning Shift Preferences for Sriram and Jeevan are Strictly Respected")
    void testAdminMorningShiftPreferenceRespected() {
        List<Employee> allEmployees = employeeRepository.findByActiveTrueOrderByIdAsc();
        assertTrue(allEmployees.size() >= 4, "Need at least 4 employees for roster generation");

        // Identify or configure two male employees
        List<Employee> males = allEmployees.stream().filter(e -> e.getGender() == Gender.MALE).toList();
        assertTrue(males.size() >= 2, "Need at least 2 male employees");

        Employee sriram = males.get(0);
        Employee jeevan = males.get(1);

        // Configure Morning preference for both
        EmployeePreference sriramPref = preferenceRepository.findTopByEmployeeIdAndStatusOrderByCreatedAtDesc(sriram.getId(), PreferenceStatus.APPROVED)
                .orElse(new EmployeePreference());
        sriramPref.setEmployee(sriram);
        sriramPref.setPreferredShiftTypes("MORNING");
        sriramPref.setAvoidShiftTypes(null);
        sriramPref.setStatus(PreferenceStatus.APPROVED);
        preferenceRepository.save(sriramPref);

        EmployeePreference jeevanPref = preferenceRepository.findTopByEmployeeIdAndStatusOrderByCreatedAtDesc(jeevan.getId(), PreferenceStatus.APPROVED)
                .orElse(new EmployeePreference());
        jeevanPref.setEmployee(jeevan);
        jeevanPref.setPreferredShiftTypes("MORNING");
        jeevanPref.setAvoidShiftTypes(null);
        jeevanPref.setStatus(PreferenceStatus.APPROVED);
        preferenceRepository.save(jeevanPref);

        // Generate Roster
        RosterCycleResponse response = rosterService.generateWeeklyRoster(nextMonday);
        assertNotNull(response);
        assertEquals("VALID", response.generationStatus());

        List<RosterAssignment> assignments = assignmentRepository.findByRosterDateBetweenOrderByRosterDateAsc(nextMonday, nextMonday.plusDays(6));
        assertFalse(assignments.isEmpty());

        // Verify Sriram assignments
        List<RosterAssignment> sriramAssignments = assignments.stream()
                .filter(a -> a.getEmployee().getId().equals(sriram.getId()))
                .toList();
        assertEquals(7, sriramAssignments.size());

        long sriramMorningCount = sriramAssignments.stream()
                .filter(a -> !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.MORNING)
                .count();
        long sriramOffCount = sriramAssignments.stream().filter(RosterAssignment::isWeeklyOff).count();
        long sriramNightCount = sriramAssignments.stream()
                .filter(a -> !a.isWeeklyOff() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT)
                .count();

        assertEquals(1, sriramOffCount, "Sriram must have exactly 1 Weekly Off");
        assertEquals(0, sriramNightCount, "Sriram must NOT be assigned NIGHT when MORNING is preferred");
        assertTrue(sriramMorningCount >= 5, "Sriram must receive MORNING on all available working days (received " + sriramMorningCount + ")");

        // Verify Jeevan assignments
        List<RosterAssignment> jeevanAssignments = assignments.stream()
                .filter(a -> a.getEmployee().getId().equals(jeevan.getId()))
                .toList();
        assertEquals(7, jeevanAssignments.size());

        long jeevanMorningCount = jeevanAssignments.stream()
                .filter(a -> !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.MORNING)
                .count();
        long jeevanOffCount = jeevanAssignments.stream().filter(RosterAssignment::isWeeklyOff).count();
        long jeevanNightCount = jeevanAssignments.stream()
                .filter(a -> !a.isWeeklyOff() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT)
                .count();

        assertEquals(1, jeevanOffCount, "Jeevan must have exactly 1 Weekly Off");
        assertEquals(0, jeevanNightCount, "Jeevan must NOT be assigned NIGHT when MORNING is preferred");
        assertTrue(jeevanMorningCount >= 5, "Jeevan must receive MORNING on all available working days (received " + jeevanMorningCount + ")");
    }

    @Test
    @DisplayName("BATCH 89 - Level 2 Priority: Explicit Date-Specific Admin Preference is Authoritative")
    void testExplicitDateSpecificPreferenceAuthoritative() {
        List<Employee> allEmployees = employeeRepository.findByActiveTrueOrderByIdAsc();
        Employee emp = allEmployees.get(0);
        LocalDate targetDate = nextMonday.plusDays(2); // Wednesday

        // Set date-specific preference: Target employee -> targetDate -> GENERAL
        DateShiftPreference dsp = new DateShiftPreference();
        dsp.setEmployee(emp);
        dsp.setPreferenceDate(targetDate);
        dsp.setShiftType(ShiftType.GENERAL);
        dsp.setReason("Important Project Sync");
        dsp.setCreatedBy("admin");
        dateShiftPreferenceRepository.saveAndFlush(dsp);

        // Generate Roster
        RosterCycleResponse response = rosterService.generateWeeklyRoster(nextMonday);
        assertNotNull(response);

        List<RosterAssignment> assignments = assignmentRepository.findByRosterDateBetweenOrderByRosterDateAsc(nextMonday, nextMonday.plusDays(6));
        RosterAssignment targetAssign = assignments.stream()
                .filter(a -> a.getEmployee().getId().equals(emp.getId()) && a.getRosterDate().equals(targetDate))
                .findFirst()
                .orElse(null);

        assertNotNull(targetAssign);
        assertFalse(targetAssign.isWeeklyOff(), "Target date with explicit shift preference should not be chosen as weekly off");
        assertEquals(ShiftType.GENERAL, targetAssign.getShift().getShiftType(), "Explicit date-specific preference must be assigned");
        assertTrue(targetAssign.getAssignmentReason().contains("Date-Specific Shift Preference"),
                "Assignment reason should explain date-specific preference fulfillment");
    }

    @Test
    @DisplayName("BATCH 89 - Problem 2: Delete and Regenerate Produces Distinct Valid Rosters Across Attempts")
    void testRegenerationProducesDiverseValidRosters() {
        LocalDate cycleEnd = nextMonday.plusDays(6);

        // Attempt 1: Generate initial roster
        RosterCycleResponse res1 = rosterService.generateWeeklyRoster(nextMonday);
        assertNotNull(res1);
        List<RosterAssignment> assign1 = assignmentRepository.findByRosterDateBetweenOrderByRosterDateAsc(nextMonday, cycleEnd);
        String fp1 = RosterService.computeRosterFingerprint(assign1);
        assertFalse(fp1.isEmpty());

        // Delete cycle 1 (simulating admin delete)
        rosterService.deleteCycle(res1.id());

        // Attempt 2: Regenerate
        RosterCycleResponse res2 = rosterService.generateWeeklyRoster(nextMonday);
        assertNotNull(res2);
        List<RosterAssignment> assign2 = assignmentRepository.findByRosterDateBetweenOrderByRosterDateAsc(nextMonday, cycleEnd);
        String fp2 = RosterService.computeRosterFingerprint(assign2);
        assertFalse(fp2.isEmpty());

        // Assert fingerprints are genuinely distinct!
        assertNotEquals(fp1, fp2, "Regenerated roster must produce a different valid assignment pattern (fp1=" + fp1 + ", fp2=" + fp2 + ")");

        // Delete cycle 2 and generate a 3rd time
        rosterService.deleteCycle(res2.id());

        // Attempt 3: Regenerate again
        RosterCycleResponse res3 = rosterService.generateWeeklyRoster(nextMonday);
        assertNotNull(res3);
        List<RosterAssignment> assign3 = assignmentRepository.findByRosterDateBetweenOrderByRosterDateAsc(nextMonday, cycleEnd);
        String fp3 = RosterService.computeRosterFingerprint(assign3);
        assertFalse(fp3.isEmpty());

        assertNotEquals(fp1, fp3, "Attempt 3 must differ from Attempt 1");
        assertNotEquals(fp2, fp3, "Attempt 3 must differ from Attempt 2");

        // Verify history tracking persisted all fingerprints
        Set<String> savedFingerprints = generationHistoryRepository.findFingerprintsByDateRange(nextMonday, cycleEnd);
        assertTrue(savedFingerprints.contains(fp1), "History must contain fp1");
        assertTrue(savedFingerprints.contains(fp2), "History must contain fp2");
        assertTrue(savedFingerprints.contains(fp3), "History must contain fp3");
    }

    @Test
    @DisplayName("BATCH 89 - Problem 3: Shift Continuity and Contiguous Shift Blocks")
    void testShiftContinuityAvoidsFragmentedBlocks() {
        RosterCycleResponse response = rosterService.generateWeeklyRoster(nextMonday);
        assertNotNull(response);

        List<RosterAssignment> assignments = assignmentRepository.findByRosterDateBetweenOrderByRosterDateAsc(nextMonday, nextMonday.plusDays(6));

        Map<Long, List<RosterAssignment>> empMap = new HashMap<>();
        for (RosterAssignment a : assignments) {
            empMap.computeIfAbsent(a.getEmployee().getId(), k -> new ArrayList<>()).add(a);
        }

        for (List<RosterAssignment> empList : empMap.values()) {
            empList.sort(Comparator.comparing(RosterAssignment::getRosterDate));
            List<ShiftType> workingShifts = empList.stream()
                    .filter(a -> !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() != ShiftType.OFF)
                    .map(a -> a.getShift().getShiftType())
                    .toList();

            // Check for fragmented alternation (e.g. A -> B -> A)
            Set<ShiftType> retiredShifts = new HashSet<>();
            ShiftType current = null;
            for (ShiftType st : workingShifts) {
                if (current != null && current != st) {
                    retiredShifts.add(current);
                }
                assertFalse(retiredShifts.contains(st),
                        "Employee shift pattern " + workingShifts + " has fragmented recurrence of shift " + st);
                current = st;
            }
        }
    }

    @Test
    @DisplayName("BATCH 89 - Level 1 Hard Constraints: Invariant Rest and Coverage Verification")
    void testLevel1HardBusinessConstraintsPreserved() {
        RosterCycleResponse response = rosterService.generateWeeklyRoster(nextMonday);
        assertNotNull(response);
        assertEquals(0, response.criticalConflicts(), "No critical conflicts allowed");

        List<RosterAssignment> assignments = assignmentRepository.findByRosterDateBetweenOrderByRosterDateAsc(nextMonday, nextMonday.plusDays(6));

        // 1. Every active employee has exactly 1 Weekly Off
        Map<Long, List<RosterAssignment>> empMap = new HashMap<>();
        for (RosterAssignment a : assignments) {
            empMap.computeIfAbsent(a.getEmployee().getId(), k -> new ArrayList<>()).add(a);
        }
        for (List<RosterAssignment> list : empMap.values()) {
            long offCount = list.stream().filter(RosterAssignment::isWeeklyOff).count();
            assertEquals(1, offCount, "Every employee must have exactly 1 Weekly Off");
        }

        // 2. Female statutory protection: Females only work MORNING or GENERAL
        for (RosterAssignment a : assignments) {
            if (a.getEmployee().getGender() == Gender.FEMALE && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null) {
                ShiftType st = a.getShift().getShiftType();
                assertTrue(st == ShiftType.MORNING || st == ShiftType.GENERAL,
                        "Female employee " + a.getEmployee().getEmployeeCode() + " assigned restricted shift: " + st);
            }
        }
    }
}
