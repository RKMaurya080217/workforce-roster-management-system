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
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BATCH 90: Admin Working-Day Control (5 vs 6 days) + Smart Consecutive Roster Integration Test Suite
 * Validates:
 * 1. 5 Working Days Generation: 5 worked shifts, 2 weekly offs per employee, clustered weekend/consecutive offs.
 * 2. 6 Working Days Generation: 6 worked shifts, 1 weekly off per employee.
 * 3. Working Days Persistence in RosterCycle and RosterGenerationHistory.
 * 4. Date-specific and Multi-shift preference adherence in 5-day mode.
 * 5. Re-optimization preserves configured workingDays (5 vs 6).
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = {"wrms.dev.credential-mirror.enabled=false"})
public class Batch90WorkingDayAndMobileIntegrationTest {

    @Autowired
    private RosterService rosterService;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private RosterAssignmentRepository assignmentRepository;

    @Autowired
    private DateShiftPreferenceRepository dateShiftPreferenceRepository;

    @Autowired
    private RosterGenerationHistoryRepository generationHistoryRepository;

    private LocalDate nextMonday;

    @BeforeEach
    void setUp() {
        var auth = new UsernamePasswordAuthenticationToken("admin", "admin",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        nextMonday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        if (dateShiftPreferenceRepository != null) {
            dateShiftPreferenceRepository.deleteAll();
        }
    }

    @Test
    @DisplayName("BATCH 90 - 5 Working Days Mode assigns exactly 5 worked shifts and 2 weekly offs clustered together")
    void testFiveWorkingDaysModeGeneration() {
        List<Employee> allEmployees = employeeRepository.findByActiveTrueOrderByIdAsc();
        assertTrue(allEmployees.size() >= 4, "Need at least 4 active employees for generation");

        LocalDate startDate = nextMonday.plusWeeks(10);
        RosterCycleResponse response = rosterService.generateWeeklyRoster(startDate, GenerationMode.MANUAL, 5);

        assertNotNull(response);
        assertEquals(5, response.workingDays(), "Response must reflect 5 working days");

        Optional<RosterCycle> cycleOpt = cycleRepository.findById(response.id());
        assertTrue(cycleOpt.isPresent());
        assertEquals(5, cycleOpt.get().getWorkingDays(), "Persisted cycle must have workingDays = 5");

        List<RosterAssignment> assignments = assignmentRepository.findByCycleIdOrderByRosterDateAsc(response.id());
        assertFalse(assignments.isEmpty());

        Map<Long, List<RosterAssignment>> byEmployee = new HashMap<>();
        for (RosterAssignment a : assignments) {
            byEmployee.computeIfAbsent(a.getEmployee().getId(), k -> new ArrayList<>()).add(a);
        }

        for (Map.Entry<Long, List<RosterAssignment>> entry : byEmployee.entrySet()) {
            List<RosterAssignment> empAssignments = entry.getValue();
            assertEquals(7, empAssignments.size(), "Employee must have 7 assignments for the 7-day week");

            long offCount = empAssignments.stream().filter(RosterAssignment::isWeeklyOff).count();
            long leaveCount = empAssignments.stream().filter(RosterAssignment::isOnLeave).count();
            long workedCount = empAssignments.stream().filter(a -> !a.isWeeklyOff() && !a.isOnLeave()).count();

            if (leaveCount == 0) {
                assertEquals(2, offCount, "In 5-day week without leave, employee must have exactly 2 weekly offs");
                assertEquals(5, workedCount, "In 5-day week without leave, employee must work exactly 5 shifts");

                // Check clustering: the 2 off days should be consecutive (or cyclic weekend wrap like Sun + Mon)
                List<LocalDate> offDates = empAssignments.stream()
                        .filter(RosterAssignment::isWeeklyOff)
                        .map(RosterAssignment::getRosterDate)
                        .sorted()
                        .toList();
                assertEquals(2, offDates.size());
                long diff = ChronoUnit.DAYS.between(offDates.get(0), offDates.get(1));
                // Clustered if consecutive (diff == 1) or cyclic wrap (diff == 6)
                assertTrue(diff == 1 || diff == 6, "The 2 weekly offs should be clustered consecutively; got " + offDates);
            }
        }
    }

    @Test
    @DisplayName("BATCH 90 - 6 Working Days Mode assigns exactly 6 worked shifts and 1 weekly off")
    void testSixWorkingDaysModeGeneration() {
        List<Employee> allEmployees = employeeRepository.findByActiveTrueOrderByIdAsc();
        assertTrue(allEmployees.size() >= 4, "Need at least 4 active employees for generation");

        LocalDate startDate = nextMonday.plusWeeks(12);
        RosterCycleResponse response = rosterService.generateWeeklyRoster(startDate, GenerationMode.MANUAL, 6);

        assertNotNull(response);
        assertEquals(6, response.workingDays(), "Response must reflect 6 working days");

        Optional<RosterCycle> cycleOpt = cycleRepository.findById(response.id());
        assertTrue(cycleOpt.isPresent());
        assertEquals(6, cycleOpt.get().getWorkingDays(), "Persisted cycle must have workingDays = 6");

        List<RosterAssignment> assignments = assignmentRepository.findByCycleIdOrderByRosterDateAsc(response.id());
        Map<Long, List<RosterAssignment>> byEmployee = new HashMap<>();
        for (RosterAssignment a : assignments) {
            byEmployee.computeIfAbsent(a.getEmployee().getId(), k -> new ArrayList<>()).add(a);
        }

        for (Map.Entry<Long, List<RosterAssignment>> entry : byEmployee.entrySet()) {
            List<RosterAssignment> empAssignments = entry.getValue();
            assertEquals(7, empAssignments.size());

            long offCount = empAssignments.stream().filter(RosterAssignment::isWeeklyOff).count();
            long leaveCount = empAssignments.stream().filter(RosterAssignment::isOnLeave).count();
            long workedCount = empAssignments.stream().filter(a -> !a.isWeeklyOff() && !a.isOnLeave()).count();

            if (leaveCount == 0) {
                assertEquals(1, offCount, "In 6-day week without leave, employee must have exactly 1 weekly off");
                assertEquals(6, workedCount, "In 6-day week without leave, employee must work exactly 6 shifts");
            }
        }
    }

    @Test
    @DisplayName("BATCH 90 - Reoptimizing cycle preserves configured workingDays (5 vs 6)")
    void testReoptimizePreservesWorkingDays() {
        LocalDate startDate = nextMonday.plusWeeks(14);
        RosterCycleResponse initialResponse = rosterService.generateWeeklyRoster(startDate, GenerationMode.MANUAL, 5);
        assertEquals(5, initialResponse.workingDays());

        // Reoptimize cycle
        RosterCycleResponse reoptimized = rosterService.reoptimizeCycle(initialResponse.id(), "Test preservation of working days");
        assertNotNull(reoptimized);
        assertEquals(5, reoptimized.workingDays(), "Re-optimized cycle must preserve 5 working days");

        Optional<RosterCycle> reoptimizedCycle = cycleRepository.findById(reoptimized.id());
        assertTrue(reoptimizedCycle.isPresent());
        assertEquals(5, reoptimizedCycle.get().getWorkingDays());
    }

    @Test
    @DisplayName("BATCH 90 - Preference authority is respected in 5-day mode")
    void testPreferencesRespectedInFiveDayMode() {
        List<Employee> allEmployees = employeeRepository.findByActiveTrueOrderByIdAsc();
        Employee emp = allEmployees.get(0);

        LocalDate startDate = nextMonday.plusWeeks(16);
        LocalDate wednesday = startDate.plusDays(2);

        // Set date-specific preference for Morning
        DateShiftPreference dsp = new DateShiftPreference();
        dsp.setEmployee(emp);
        dsp.setPreferenceDate(wednesday);
        dsp.setShiftType(ShiftType.MORNING);
        dsp.setReason("Doctor visit in evening");
        dsp.setCreatedBy("admin");
        dateShiftPreferenceRepository.save(dsp);

        RosterCycleResponse response = rosterService.generateWeeklyRoster(startDate, GenerationMode.MANUAL, 5);
        assertEquals(5, response.workingDays());

        List<RosterAssignment> empAssignments = assignmentRepository.findByCycleIdOrderByRosterDateAsc(response.id())
                .stream()
                .filter(a -> a.getEmployee().getId().equals(emp.getId()))
                .toList();

        Optional<RosterAssignment> wednesdayAssignment = empAssignments.stream()
                .filter(a -> a.getRosterDate().equals(wednesday))
                .findFirst();

        assertTrue(wednesdayAssignment.isPresent());
        RosterAssignment assign = wednesdayAssignment.get();
        assertNotNull(assign.getShift());
        assertEquals(ShiftType.MORNING, assign.getShift().getShiftType(),
                "Authoritative date-specific preference for MORNING must be honored");
    }

    @Test
    @DisplayName("BATCH 90 - Generation history tracks workingDays")
    void testGenerationHistoryTracksWorkingDays() {
        LocalDate startDate = nextMonday.plusWeeks(18);
        RosterCycleResponse response = rosterService.generateWeeklyRoster(startDate, GenerationMode.MANUAL, 5);

        List<RosterGenerationHistory> history = generationHistoryRepository.findByStartDateAndEndDateOrderByCreatedAtDesc(startDate, startDate.plusDays(6));
        assertFalse(history.isEmpty(), "Generation history must be recorded");
        assertEquals(5, history.get(0).getWorkingDays(), "History record must store workingDays = 5");
    }
}
