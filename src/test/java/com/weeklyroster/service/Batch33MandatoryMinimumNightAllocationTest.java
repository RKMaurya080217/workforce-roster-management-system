package com.weeklyroster.service;

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
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class Batch33MandatoryMinimumNightAllocationTest {

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
    @DisplayName("Test 1: Multiple Eligible Males - Every eligible male gets >= 1 NIGHT, <= 2 NIGHT, 1 NIGHT per day")
    void testMultipleEligibleMalesMinOneNight() {
        LocalDate startDate = LocalDate.of(2026, 11, 2);
        LocalDate endDate = LocalDate.of(2026, 11, 8);

RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);
        assertNotNull(res);

        List<RosterAssignmentResponse> assignments = res.assignments();
        List<Employee> activeMales = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.MALE)
                .toList();

        assertFalse(activeMales.isEmpty(), "Active male employees must exist");

        // Group by employee
        Map<Long, List<RosterAssignmentResponse>> byEmp = assignments.stream()
                .collect(Collectors.groupingBy(RosterAssignmentResponse::employeeId));

        for (Employee male : activeMales) {
            List<RosterAssignmentResponse> maleAssigns = byEmp.getOrDefault(male.getId(), Collections.emptyList());
            long nightCount = maleAssigns.stream()
                    .filter(a -> !a.weeklyOff() && !a.onLeave() && a.shiftType() == ShiftType.NIGHT)
                    .count();

            assertTrue(nightCount >= 1,
                    "Male employee " + male.getEmployeeCode() + " (" + male.getFirstName() + ") must receive at least 1 NIGHT shift. Got: " + nightCount);
            assertTrue(nightCount <= 2,
                    "Male employee " + male.getEmployeeCode() + " must receive at most 2 NIGHT shifts. Got: " + nightCount);
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
    @DisplayName("Test 2: Female Employees Never Receive NIGHT or EVENING, Males Receive Mandatory NIGHT")
    void testFemaleEmployeesNeverAssignedNight() {
        LocalDate startDate = LocalDate.of(2026, 11, 16);
        LocalDate endDate = LocalDate.of(2026, 11, 22);

RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);
        assertNotNull(res);

        List<RosterAssignmentResponse> assignments = res.assignments();
        List<Employee> activeFemales = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.FEMALE)
                .toList();
        List<Employee> activeMales = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.MALE)
                .toList();

        assertFalse(activeFemales.isEmpty(), "Active female employees must exist");
        assertFalse(activeMales.isEmpty(), "Active male employees must exist");

        // Validate females never get NIGHT or EVENING
        for (Employee female : activeFemales) {
            List<RosterAssignmentResponse> fAssigns = assignments.stream()
                    .filter(a -> a.employeeId().equals(female.getId()))
                    .toList();

            for (RosterAssignmentResponse a : fAssigns) {
                if (!a.weeklyOff() && !a.onLeave()) {
                    assertNotEquals(ShiftType.NIGHT, a.shiftType(),
                            "Female employee " + female.getEmployeeCode() + " must never receive NIGHT shift");
                    assertNotEquals(ShiftType.EVENING, a.shiftType(),
                            "Female employee " + female.getEmployeeCode() + " must never receive EVENING shift");
                    assertTrue(a.shiftType() == ShiftType.MORNING || a.shiftType() == ShiftType.GENERAL || a.shiftType() == ShiftType.OFF,
                            "Female employee " + female.getEmployeeCode() + " can only receive Day shifts");
                }
            }
        }

        // Validate all eligible males receive >= 1 NIGHT
        Map<Long, List<RosterAssignmentResponse>> byEmp = assignments.stream()
                .collect(Collectors.groupingBy(RosterAssignmentResponse::employeeId));

        for (Employee male : activeMales) {
            List<RosterAssignmentResponse> mAssigns = byEmp.getOrDefault(male.getId(), Collections.emptyList());
            long nightCount = mAssigns.stream()
                    .filter(a -> !a.weeklyOff() && !a.onLeave() && a.shiftType() == ShiftType.NIGHT)
                    .count();
            assertTrue(nightCount >= 1,
                    "Male employee " + male.getEmployeeCode() + " must receive at least 1 NIGHT shift. Got: " + nightCount);
        }
    }

    @Test
    @DisplayName("Test 3: Limited Eligible Males - Fair Distribution within Capacity")
    void testLimitedEligibleMalesCoverage() {
        LocalDate startDate = LocalDate.of(2026, 11, 30);
        LocalDate endDate = LocalDate.of(2026, 12, 6);

RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);
        assertNotNull(res);

        List<RosterAssignmentResponse> assignments = res.assignments();
        List<Employee> activeMales = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.MALE)
                .toList();

        Map<Long, List<RosterAssignmentResponse>> byEmp = assignments.stream()
                .collect(Collectors.groupingBy(RosterAssignmentResponse::employeeId));

        int totalNights = 0;
        for (Employee male : activeMales) {
            List<RosterAssignmentResponse> mAssigns = byEmp.getOrDefault(male.getId(), Collections.emptyList());
            long nightCount = mAssigns.stream()
                    .filter(a -> !a.weeklyOff() && !a.onLeave() && a.shiftType() == ShiftType.NIGHT)
                    .count();

            assertTrue(nightCount >= 1, "Eligible male " + male.getEmployeeCode() + " must have >= 1 night");
            totalNights += nightCount;
        }

        assertEquals(7, totalNights, "Total NIGHT shifts across 7 days must be exactly 7");
    }

    @Test
    @DisplayName("Test 4: Male Unavailable Full Cycle - Handled Gracefully without Violations")
    void testMaleUnavailableFullCycleHandled() {
        LocalDate startDate = LocalDate.of(2027, 2, 1);
        LocalDate endDate = LocalDate.of(2027, 2, 7);

        // Find a male employee and place him on approved leave for all 7 days
        List<Employee> males = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.MALE)
                .toList();
        assertTrue(males.size() >= 2, "Need at least 2 male employees for test");

        Employee leaveMale = males.get(males.size() - 1);
        LeaveRequest fullWeekLeave = new LeaveRequest();
        fullWeekLeave.setEmployee(leaveMale);
        fullWeekLeave.setStartDate(startDate);
        fullWeekLeave.setEndDate(endDate);
        fullWeekLeave.setStatus(LeaveStatus.APPROVED);
        fullWeekLeave.setReason("Full Week Annual Vacation");
        fullWeekLeave.setRequestedAt(LocalDateTime.now().minusDays(2));
        leaveRepository.save(fullWeekLeave);

RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);
        assertNotNull(res);

        List<RosterAssignmentResponse> assignments = res.assignments();

        // The leave male must be ON LEAVE for all 7 days with 0 working shifts
        List<RosterAssignmentResponse> leaveMaleAssigns = assignments.stream()
                .filter(a -> a.employeeId().equals(leaveMale.getId()))
                .toList();

        assertEquals(7, leaveMaleAssigns.size());
        for (RosterAssignmentResponse a : leaveMaleAssigns) {
            assertTrue(a.onLeave(), "Employee on approved leave must be marked onLeave on " + a.rosterDate());
            assertNotEquals(ShiftType.NIGHT, a.shiftType(), "Employee on leave must not be assigned NIGHT");
        }

        // All OTHER eligible males must receive >= 1 NIGHT shift
        List<Employee> remainingMales = males.stream()
                .filter(m -> !m.getId().equals(leaveMale.getId()))
                .toList();

        Map<Long, List<RosterAssignmentResponse>> byEmp = assignments.stream()
                .collect(Collectors.groupingBy(RosterAssignmentResponse::employeeId));

        for (Employee male : remainingMales) {
            List<RosterAssignmentResponse> mAssigns = byEmp.getOrDefault(male.getId(), Collections.emptyList());
            long nightCount = mAssigns.stream()
                    .filter(a -> !a.weeklyOff() && !a.onLeave() && a.shiftType() == ShiftType.NIGHT)
                    .count();

            assertTrue(nightCount >= 1,
                    "Remaining eligible male " + male.getEmployeeCode() + " must receive >= 1 NIGHT shift. Got: " + nightCount);
        }
    }

    @Test
    @DisplayName("Test 5: Minimum 12-Hour Rest Constraint Maintained with Night Allocations")
    void testRestConstraintMaintainedWithNightAllocation() {
        LocalDate startDate = LocalDate.of(2026, 12, 28);
        LocalDate endDate = LocalDate.of(2027, 1, 3);

RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);
        assertNotNull(res);

        List<RosterAssignmentResponse> assignments = res.assignments();
        Map<Long, List<RosterAssignmentResponse>> byEmp = assignments.stream()
                .collect(Collectors.groupingBy(RosterAssignmentResponse::employeeId));

        for (Map.Entry<Long, List<RosterAssignmentResponse>> entry : byEmp.entrySet()) {
            List<RosterAssignmentResponse> empList = new ArrayList<>(entry.getValue());
            empList.sort(Comparator.comparing(RosterAssignmentResponse::rosterDate));

            for (int i = 0; i < empList.size() - 1; i++) {
                RosterAssignmentResponse cur = empList.get(i);
                RosterAssignmentResponse next = empList.get(i + 1);

                if (!cur.weeklyOff() && !cur.onLeave() && !next.weeklyOff() && !next.onLeave()) {
                    // Check rest interval between cur and next
                    LocalTime curStart = defaultStartTime(cur.shiftType());
                    LocalTime curEnd = defaultEndTime(cur.shiftType());
                    LocalTime nextStart = defaultStartTime(next.shiftType());

                    LocalDateTime curEndDT = cur.rosterDate().atTime(curEnd);
                    if (curEnd.isBefore(curStart)) {
                        curEndDT = curEndDT.plusDays(1);
                    }
                    LocalDateTime nextStartDT = next.rosterDate().atTime(nextStart);

                    Duration rest = Duration.between(curEndDT, nextStartDT);
                    assertTrue(rest.toMinutes() >= 720,
                            "Rest between " + cur.rosterDate() + " " + cur.shiftType()
                                    + " and " + next.rosterDate() + " " + next.shiftType()
                                    + " must be at least 12h (720m). Got: " + rest.toMinutes() + "m");
                }
            }
        }
    }

    @Test
    @DisplayName("Test 6: Preference Interaction - EMP001 gets >= 1 NIGHT, 0 EVENING, Sunday OFF")
        void testPreferenceInteractionWithNightAllocation() {

        Employee emp001 = employeeRepository.findByEmployeeCodeIgnoreCase("EMP001")
                .orElseThrow(() -> new IllegalStateException("EMP001 not found"));

        LocalDate startDate = LocalDate.of(2027, 1, 11);
        LocalDate endDate = LocalDate.of(2027, 1, 17);

        // Clean up previous preferences for EMP001
        preferenceRepository.findByEmployeeIdOrderByCreatedAtDesc(emp001.getId())
                .forEach(p -> preferenceRepository.delete(p));

        // Submit and approve preference for EMP001: Preferred MORNING, GENERAL, NIGHT; Avoid EVENING; Preferred OFF: SUNDAY
        EmployeePreference pref = new EmployeePreference();
        pref.setEmployee(emp001);
        pref.setPreferredShiftTypes("MORNING, GENERAL, NIGHT");
        pref.setAvoidShiftTypes("EVENING");
        pref.setPreferredOffDays("SUNDAY");
        pref.setPreferredWorkingDays("MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY");
        pref.setStatus(PreferenceStatus.APPROVED);
        pref.setEffectiveFrom(startDate.minusDays(10));
        pref.setEffectiveTo(endDate.plusDays(10));
        pref.setReviewedBy("admin");
        pref.setReviewedAt(LocalDateTime.now());
        preferenceRepository.save(pref);

RosterCycleResponse res = rosterService.generateWeeklyRoster(startDate);
        assertNotNull(res);

        List<RosterAssignmentResponse> emp001Assigns = res.assignments().stream()
                .filter(a -> a.employeeId().equals(emp001.getId()))
                .toList();

        assertEquals(7, emp001Assigns.size());

        long nightCount = 0;
        long eveningCount = 0;
        boolean sundayOff = false;

        for (RosterAssignmentResponse a : emp001Assigns) {
            if (a.rosterDate().getDayOfWeek() == DayOfWeek.SUNDAY) {
                if (a.weeklyOff()) {
                    sundayOff = true;
                }
            }
            if (!a.weeklyOff() && !a.onLeave()) {
                if (a.shiftType() == ShiftType.NIGHT) nightCount++;
                if (a.shiftType() == ShiftType.EVENING) eveningCount++;
            }
        }

                assertTrue(nightCount >= 1, "EMP001 must receive at least 1 NIGHT shift (Batch 33). Got: " + nightCount);

        assertEquals(0, eveningCount, "EMP001 must receive 0 EVENING shifts (Avoid Shift constraint)");
        assertTrue(sundayOff, "EMP001 must receive SUNDAY as Weekly OFF (Preferred OFF Day)");
    }

    @Test
    @DisplayName("Test 7: Roster Regeneration - Minimum NIGHT Satisfied and Dynamic")
    void testRosterRegenerationPreservesMinNight() {
        LocalDate startDate = LocalDate.of(2027, 1, 25);
        LocalDate endDate = LocalDate.of(2027, 1, 31);

        // Run 1
        RosterCycleResponse run1 = rosterService.generateWeeklyRoster(startDate);
        assertNotNull(run1);

        List<Employee> activeMales = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.MALE)
                .toList();

        Map<Long, List<RosterAssignmentResponse>> byEmp1 = run1.assignments().stream()
                .collect(Collectors.groupingBy(RosterAssignmentResponse::employeeId));

        for (Employee male : activeMales) {
            List<RosterAssignmentResponse> mAssigns = byEmp1.getOrDefault(male.getId(), Collections.emptyList());
            long nightCount = mAssigns.stream()
                    .filter(a -> !a.weeklyOff() && !a.onLeave() && a.shiftType() == ShiftType.NIGHT)
                    .count();
            assertTrue(nightCount >= 1, "Run 1: Male " + male.getEmployeeCode() + " must have >= 1 night");
        }

        // Delete cycle
        rosterService.deleteCycle(run1.id());

        // Run 2 (Regenerate)
        RosterCycleResponse run2 = rosterService.generateWeeklyRoster(startDate);
        assertNotNull(run2);

        Map<Long, List<RosterAssignmentResponse>> byEmp2 = run2.assignments().stream()
                .collect(Collectors.groupingBy(RosterAssignmentResponse::employeeId));

        for (Employee male : activeMales) {
            List<RosterAssignmentResponse> mAssigns = byEmp2.getOrDefault(male.getId(), Collections.emptyList());
            long nightCount = mAssigns.stream()
                    .filter(a -> !a.weeklyOff() && !a.onLeave() && a.shiftType() == ShiftType.NIGHT)
                    .count();
            assertTrue(nightCount >= 1, "Run 2: Male " + male.getEmployeeCode() + " must have >= 1 night");
        }
    }

    private LocalTime defaultStartTime(ShiftType type) {
        if (type == null) return LocalTime.of(9, 0);
        return switch (type) {
            case MORNING -> LocalTime.of(6, 0);
            case GENERAL -> LocalTime.of(9, 0);
            case EVENING -> LocalTime.of(14, 0);
            case NIGHT -> LocalTime.of(22, 0);
            case OFF -> LocalTime.of(0, 0);
        };
    }

    private LocalTime defaultEndTime(ShiftType type) {
        if (type == null) return LocalTime.of(17, 0);
        return switch (type) {
            case MORNING -> LocalTime.of(14, 0);
            case GENERAL -> LocalTime.of(17, 0);
            case EVENING -> LocalTime.of(22, 0);
            case NIGHT -> LocalTime.of(7, 0);
            case OFF -> LocalTime.of(23, 59);
        };
    }
}
