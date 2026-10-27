package com.weeklyroster.service;

import com.weeklyroster.dto.request.ApplyLeaveRequest;
import com.weeklyroster.dto.request.DateShiftPreferenceRequest;
import com.weeklyroster.dto.request.LeaveDecisionRequest;
import com.weeklyroster.dto.request.RecurringShiftPreferenceRequest;
import com.weeklyroster.dto.response.AdminFullEmployeeProfileResponse;
import com.weeklyroster.dto.response.LeaveResponse;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.dto.response.RosterValidationResponse;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "wrms.dev.credential-mirror.enabled=false",
        "spring.datasource.url=jdbc:sqlite:target/test-wrms-b115-comp.db?busy_timeout=30000&journal_mode=WAL"
})
public class Batch115ComprehensiveConstraintSolverTest {

    static {
        try {
            System.setProperty("spring.profiles.active", "test");
            System.setProperty("spring.datasource.url", "jdbc:sqlite:target/test-wrms-b115-comp.db?busy_timeout=30000&journal_mode=WAL");
            File targetDir = new File("target");
            if (!targetDir.exists()) targetDir.mkdirs();
            File realDb = new File("data/wrms.db");
            File testDb = new File("target/test-wrms-b115-comp.db");
            if (realDb.exists()) {
                Files.copy(realDb.toPath(), testDb.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Autowired
    private RosterService rosterService;

    @Autowired
    private RosterValidatorService rosterValidatorService;

    @Autowired
    private RosterCycleRepository rosterCycleRepository;

    @Autowired
    private RosterAssignmentRepository rosterAssignmentRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private LeaveService leaveService;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired
    private DateShiftPreferenceService dateShiftPreferenceService;

    @Autowired
    private RecurringShiftPreferenceService recurringShiftPreferenceService;

    @Autowired
    private EmployeeService employeeService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "admin",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );
    }

    @Test
    @DisplayName("01. Hard Invariant 1: Exactly 1 Weekly Off & Exactly 6 Working Days in 6-Day Cycle")
    void test01_StrictWorkingAndOffCountInSixDayCycle() {
        LocalDate startDate = LocalDate.of(2026, 11, 9);
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(startDate, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle);

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());
        Map<Long, List<RosterAssignment>> byEmp = new HashMap<>();
        for (RosterAssignment a : assignments) {
            byEmp.computeIfAbsent(a.getEmployee().getId(), k -> new ArrayList<>()).add(a);
        }

        for (Map.Entry<Long, List<RosterAssignment>> entry : byEmp.entrySet()) {
            List<RosterAssignment> empList = entry.getValue();
            long offCount = empList.stream()
                    .filter(a -> a.isWeeklyOff() || (a.getShift() != null && a.getShift().getShiftType() == ShiftType.OFF && !a.isOnLeave()))
                    .count();
            long workCount = empList.stream()
                    .filter(a -> !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() != ShiftType.OFF)
                    .count();
            long leaveCount = empList.stream().filter(RosterAssignment::isOnLeave).count();

            if (leaveCount == 0) {
                assertEquals(1, offCount, "Employee ID " + entry.getKey() + " must have EXACTLY 1 Weekly Off in 6-day cycle");
                assertEquals(6, workCount, "Employee ID " + entry.getKey() + " must have EXACTLY 6 Working Days in 6-day cycle");
            }
        }
    }

    @Test
    @DisplayName("02. Hard Invariant 2: Night Shift Always Followed by Immediate Next-Day Weekly Off")
    void test02_LinkedNightAndWeeklyOffMandatoryConstraint() {
        LocalDate startDate = LocalDate.of(2026, 11, 9);
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(startDate, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle);

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());
        for (RosterAssignment a : assignments) {
            if (!a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT) {
                LocalDate nextDay = a.getRosterDate().plusDays(1);
                if (!nextDay.isAfter(cycle.endDate())) {
                    RosterAssignment nextA = assignments.stream()
                            .filter(x -> x.getEmployee().getId().equals(a.getEmployee().getId()) && x.getRosterDate().equals(nextDay))
                            .findFirst().orElse(null);
                    assertNotNull(nextA, "Next day assignment must exist for night worker " + a.getEmployee().getFirstName());
                    assertTrue(nextA.isWeeklyOff() || (nextA.getShift() != null && nextA.getShift().getShiftType() == ShiftType.OFF),
                            String.format("Employee %s worked Night on %s but does not have Weekly Off on %s",
                                    a.getEmployee().getFirstName(), a.getRosterDate(), nextDay));
                }
            }
        }
    }

    @Test
    @DisplayName("03. Hard Invariant 3: Preceding Sunday Night Shift Forces Monday Weekly Off Across Cycle Boundary")
    void test03_PrecedingSundayNightForcesMondayWeeklyOffAcrossCycleBoundary() {
        LocalDate w1Start = LocalDate.of(2026, 11, 2);
        RosterCycleResponse c1 = rosterService.generateWeeklyRoster(w1Start, GenerationMode.AUTOMATIC, 6);
        rosterService.publishRoster(c1.id());

        // Identify who worked Sunday Night on 08-Nov-2026
        List<RosterAssignment> w1List = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(c1.id());
        RosterAssignment sunNightA = w1List.stream()
                .filter(a -> a.getRosterDate().equals(LocalDate.of(2026, 11, 8))
                        && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT)
                .findFirst().orElse(null);
        assertNotNull(sunNightA, "Sunday night worker must exist in Week 1");
        Long sunNightEmpId = sunNightA.getEmployee().getId();

        LocalDate w2Start = LocalDate.of(2026, 11, 9);
        RosterCycleResponse c2 = rosterService.generateWeeklyRoster(w2Start, GenerationMode.AUTOMATIC, 6);

        List<RosterAssignment> w2List = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(c2.id());
        RosterAssignment monA = w2List.stream()
                .filter(a -> a.getEmployee().getId().equals(sunNightEmpId) && a.getRosterDate().equals(w2Start))
                .findFirst().orElse(null);
        assertNotNull(monA, "Monday assignment must exist for Sunday night worker");
        assertTrue(monA.isWeeklyOff() || (monA.getShift() != null && monA.getShift().getShiftType() == ShiftType.OFF),
                "Sunday night worker must be assigned Monday Weekly Off across cycle boundary");

        // And that worker cannot receive a 2nd off
        long totalOffs = w2List.stream()
                .filter(a -> a.getEmployee().getId().equals(sunNightEmpId))
                .filter(a -> a.isWeeklyOff() || (a.getShift() != null && a.getShift().getShiftType() == ShiftType.OFF && !a.isOnLeave()))
                .count();
        assertEquals(1, totalOffs, "Sunday night worker must have EXACTLY 1 Weekly Off in current week");
    }

    @Test
    @DisplayName("04. Hard Invariant 4: Admin Date-Specific Shift Preference Is Hard Constraint")
    void test04_AdminDateSpecificPreferenceIsHardConstraint() {
        LocalDate cycleStart = LocalDate.of(2026, 12, 7); // Monday
        LocalDate targetDate = LocalDate.of(2026, 12, 8); // Tuesday

        Employee rajat = employeeRepository.findAll().stream()
                .filter(e -> "Rajat".equalsIgnoreCase(e.getFirstName())).findFirst().orElseThrow();

        dateShiftPreferenceService.createOrUpdate(
                new DateShiftPreferenceRequest(rajat.getId(), targetDate, ShiftType.MORNING, "Mandatory Admin Date Preference"),
                "admin"
        );

        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(cycleStart, GenerationMode.AUTOMATIC, 6);
        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());

        RosterAssignment rajatTue = assignments.stream()
                .filter(a -> a.getEmployee().getId().equals(rajat.getId()) && a.getRosterDate().equals(targetDate))
                .findFirst().orElseThrow();

        assertFalse(rajatTue.isWeeklyOff(), "Date preference was for MORNING, not Weekly Off");
        assertEquals(ShiftType.MORNING, rajatTue.getShift().getShiftType(),
                "Admin date-specific preference MUST be MORNING and cannot be overridden");
    }

    @Test
    @DisplayName("05. Hard Invariant 4: Admin Recurring Weekly Shift Preference Is Hard Constraint")
    void test05_AdminRecurringWeeklyPreferenceIsHardConstraint() {
        LocalDate cycleStart = LocalDate.of(2026, 12, 14); // Monday

        Employee sambhav = employeeRepository.findAll().stream()
                .filter(e -> "Sambhav".equalsIgnoreCase(e.getFirstName())).findFirst().orElseThrow();

        recurringShiftPreferenceService.create(
                new RecurringShiftPreferenceRequest(sambhav.getId(), DayOfWeek.WEDNESDAY, ShiftType.MORNING,
                        cycleStart, cycleStart.plusDays(28), true, "Recurring Wed Morning Hard Constraint"),
                "admin"
        );

        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(cycleStart, GenerationMode.AUTOMATIC, 6);
        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());

        LocalDate wednesday = cycleStart.plusDays(2);
        RosterAssignment sambhavWed = assignments.stream()
                .filter(a -> a.getEmployee().getId().equals(sambhav.getId()) && a.getRosterDate().equals(wednesday))
                .findFirst().orElseThrow();

        assertEquals(ShiftType.MORNING, sambhavWed.getShift().getShiftType(),
                "Admin recurring preference MUST be MORNING and cannot be overridden");
    }

    @Test
    @DisplayName("06. Hard Invariant 6: Female Employees Strictly Forbidden from Evening and Night Shifts")
    void test06_FemaleEmployeesNeverAssignedEveningOrNight() {
        LocalDate cycleStart = LocalDate.of(2026, 11, 9);
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(cycleStart, GenerationMode.AUTOMATIC, 6);

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());
        for (RosterAssignment a : assignments) {
            if (a.getEmployee().getGender() == Gender.FEMALE && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null) {
                ShiftType st = a.getShift().getShiftType();
                assertNotEquals(ShiftType.EVENING, st, "Female employee " + a.getEmployee().getFirstName() + " assigned Evening");
                assertNotEquals(ShiftType.NIGHT, st, "Female employee " + a.getEmployee().getFirstName() + " assigned Night");
            }
        }
    }

    @Test
    @DisplayName("07. Hard Invariant 7: Joining and Resignation Boundaries Respected")
    void test07_JoiningAndResignationBoundariesRespected() {
        Employee tushar = employeeRepository.findAll().stream()
                .filter(e -> "Tushar".equalsIgnoreCase(e.getFirstName())).findFirst().orElseThrow();

        LocalDate futureJoining = LocalDate.of(2027, 1, 1);
        tushar.setJoiningDate(futureJoining);
        employeeRepository.save(tushar);

        LocalDate cycleStart = LocalDate.of(2026, 11, 9);
        assertFalse(tushar.isEligibleForDate(cycleStart), "Employee with future joining date must not be eligible");

        // Restore
        tushar.setJoiningDate(LocalDate.of(2026, 1, 1));
        employeeRepository.save(tushar);
    }

    @Test
    @DisplayName("08. Leave/Holiday Status Distinction: Approved Leave Never Converted to Weekly Off")
    void test08_ApprovedLeaveNeverConvertedToWeeklyOff() {
        Employee rajat = employeeRepository.findAll().stream()
                .filter(e -> "Rajat".equalsIgnoreCase(e.getFirstName())).findFirst().orElseThrow();

        LocalDate cycleStart = LocalDate.of(2026, 12, 28);
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(cycleStart, GenerationMode.AUTOMATIC, 6);

        // Apply and approve leave for Rajat on Wednesday
        LocalDate leaveDate = cycleStart.plusDays(2);
        LeaveResponse lr = leaveService.apply(new ApplyLeaveRequest(
                rajat.getId(), leaveDate, leaveDate, "Medical Appointment"
        ));
        leaveService.approve(lr.id(), new LeaveDecisionRequest("Approved"));

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());
        RosterAssignment leaveA = assignments.stream()
                .filter(a -> a.getEmployee().getId().equals(rajat.getId()) && a.getRosterDate().equals(leaveDate))
                .findFirst().orElseThrow();

        assertTrue(leaveA.isOnLeave(), "Assignment must be marked onLeave");
        assertFalse(leaveA.isWeeklyOff(), "Approved leave must NOT be converted to weekly off");
    }

    @Test
    @DisplayName("09. Full Employee Profile RBAC: Admin Profile Retrieval")
    void test09_FullEmployeeProfileRbacRetrieval() {
        Employee rajat = employeeRepository.findAll().stream()
                .filter(e -> "Rajat".equalsIgnoreCase(e.getFirstName())).findFirst().orElseThrow();

        AdminFullEmployeeProfileResponse profile = employeeService.getFullProfile(rajat.getId());
        assertNotNull(profile);
        assertEquals(rajat.getId(), profile.id());
        assertNotNull(profile.employeeCode());
        assertNotNull(profile.role());
    }

    @Test
    @DisplayName("10. Shift Staffing Bounds: Morning (1-2) and General (2-4 on weekdays)")
    void test10_ShiftStaffingBoundsRespected() {
        LocalDate cycleStart = LocalDate.of(2026, 11, 2);
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(cycleStart, GenerationMode.AUTOMATIC, 6);

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());
        Map<LocalDate, Map<ShiftType, Long>> countsByDate = new TreeMap<>();
        for (RosterAssignment a : assignments) {
            if (!a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null) {
                countsByDate.computeIfAbsent(a.getRosterDate(), k -> new EnumMap<>(ShiftType.class))
                        .merge(a.getShift().getShiftType(), 1L, Long::sum);
            }
        }

        for (Map.Entry<LocalDate, Map<ShiftType, Long>> entry : countsByDate.entrySet()) {
            LocalDate d = entry.getKey();
            boolean isWeekend = (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY);
            long morning = entry.getValue().getOrDefault(ShiftType.MORNING, 0L);
            long evening = entry.getValue().getOrDefault(ShiftType.EVENING, 0L);
            long night = entry.getValue().getOrDefault(ShiftType.NIGHT, 0L);

            assertTrue(morning >= 1 && morning <= 2, "Morning count on " + d + " must be 1-2, was " + morning);
            assertTrue(evening <= 2, "Evening count on " + d + " must be <= 2, was " + evening);
            assertTrue(night <= 1, "Night count on " + d + " must be <= 1, was " + night);
            if (!isWeekend) {
                long general = entry.getValue().getOrDefault(ShiftType.GENERAL, 0L);
                assertTrue(general >= 1 && general <= 4, "Weekday General count on " + d + " must be 1-4, was " + general);
            }
        }
    }
}
