package com.weeklyroster.service;

import com.weeklyroster.dto.request.DateShiftPreferenceRequest;
import com.weeklyroster.dto.request.RecurringShiftPreferenceRequest;
import com.weeklyroster.dto.response.EmailDeliveryLogResponse;
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
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "wrms.dev.credential-mirror.enabled=false",
        "spring.datasource.url=jdbc:sqlite:target/test-wrms-b113-comprehensive.db?busy_timeout=30000&journal_mode=WAL"
})
public class Batch113ComprehensiveRosterTest {

    static {
        try {
            System.setProperty("spring.profiles.active", "test");
            System.setProperty("spring.datasource.url", "jdbc:sqlite:target/test-wrms-b113-comprehensive.db?busy_timeout=30000&journal_mode=WAL");
            File targetDir = new File("target");
            if (!targetDir.exists()) targetDir.mkdirs();
            File realDb = new File("data/wrms.db");
            File testDb = new File("target/test-wrms-b113-comprehensive.db");
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
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired
    private DateShiftPreferenceService dateShiftPreferenceService;

    @Autowired
    private RecurringShiftPreferenceService recurringShiftPreferenceService;

    @Autowired
    private RosterEmailService rosterEmailService;

    @Autowired
    private EmailDeliveryLogRepository emailDeliveryLogRepository;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "admin",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );
    }

    @Test
    @DisplayName("01. Night Shift Followed by Mandatory Next-Day Weekly Off")
    void test01_NightShiftFollowedByMandatoryNextDayWeeklyOff() {
        LocalDate startDate = LocalDate.of(2026, 11, 2);
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
                    assertNotNull(nextA, "Next day assignment must exist");
                    assertTrue(nextA.isWeeklyOff() || (nextA.getShift() != null && nextA.getShift().getShiftType() == ShiftType.OFF),
                            String.format("Employee %s worked Night on %s but does not have Weekly Off on %s",
                                    a.getEmployee().getFirstName(), a.getRosterDate(), nextDay));
                }
            }
        }
    }

    @Test
    @DisplayName("02. Sunday Night Shift Requires Monday Weekly Off Across Cycle Boundary")
    void test02_SundayNightShiftRequiresMondayWeeklyOffAcrossCycleBoundary() {
        // Week 1: 02-Nov to 08-Nov
        LocalDate week1Start = LocalDate.of(2026, 11, 2);
        RosterCycleResponse cycle1 = rosterService.generateWeeklyRoster(week1Start, GenerationMode.AUTOMATIC, 6);
        rosterService.publishRoster(cycle1.id());

        // Identify who worked Sunday Night (08-Nov)
        List<RosterAssignment> w1Assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle1.id());
        RosterAssignment sunNightAssign = w1Assignments.stream()
                .filter(a -> a.getRosterDate().equals(LocalDate.of(2026, 11, 8))
                        && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT)
                .findFirst().orElse(null);
        assertNotNull(sunNightAssign, "Sunday must have exactly 1 Night worker");
        Long sunNightEmpId = sunNightAssign.getEmployee().getId();

        // Week 2: 09-Nov to 15-Nov
        LocalDate week2Start = LocalDate.of(2026, 11, 9);
        RosterCycleResponse cycle2 = rosterService.generateWeeklyRoster(week2Start, GenerationMode.AUTOMATIC, 6);

        List<RosterAssignment> w2Assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle2.id());
        RosterAssignment monAssign = w2Assignments.stream()
                .filter(a -> a.getEmployee().getId().equals(sunNightEmpId) && a.getRosterDate().equals(week2Start))
                .findFirst().orElse(null);
        assertNotNull(monAssign, "Monday assignment must exist for Sunday night worker");
        assertTrue(monAssign.isWeeklyOff() || (monAssign.getShift() != null && monAssign.getShift().getShiftType() == ShiftType.OFF),
                "Sunday night worker must be assigned Weekly Off on Monday across cycle boundary");
    }

    @Test
    @DisplayName("03. Consecutive Night Shifts Strictly Forbidden")
    void test03_ConsecutiveNightShiftsStrictlyForbidden() {
        LocalDate startDate = LocalDate.of(2026, 11, 2);
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(startDate, GenerationMode.AUTOMATIC, 6);

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());
        Map<Long, List<RosterAssignment>> byEmp = new HashMap<>();
        for (RosterAssignment a : assignments) {
            byEmp.computeIfAbsent(a.getEmployee().getId(), k -> new ArrayList<>()).add(a);
        }

        for (List<RosterAssignment> empList : byEmp.values()) {
            empList.sort(Comparator.comparing(RosterAssignment::getRosterDate));
            for (int i = 0; i < empList.size() - 1; i++) {
                RosterAssignment cur = empList.get(i);
                RosterAssignment next = empList.get(i + 1);
                boolean curNight = !cur.isWeeklyOff() && !cur.isOnLeave() && cur.getShift() != null && cur.getShift().getShiftType() == ShiftType.NIGHT;
                boolean nextNight = !next.isWeeklyOff() && !next.isOnLeave() && next.getShift() != null && next.getShift().getShiftType() == ShiftType.NIGHT;
                assertFalse(curNight && nextNight, "Consecutive night shifts are strictly forbidden");
            }
        }
    }

    @Test
    @DisplayName("04. Max Night Shifts Per Employee Per Week Not Exceeded (<= 2)")
    void test04_MaxNightShiftsPerEmployeePerWeekNotExceeded() {
        LocalDate startDate = LocalDate.of(2026, 11, 2);
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(startDate, GenerationMode.AUTOMATIC, 6);

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());
        Map<Long, Long> nightCounts = new HashMap<>();
        for (RosterAssignment a : assignments) {
            if (!a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT) {
                nightCounts.merge(a.getEmployee().getId(), 1L, Long::sum);
            }
        }

        for (Map.Entry<Long, Long> entry : nightCounts.entrySet()) {
            assertTrue(entry.getValue() <= 2, "Employee ID " + entry.getKey() + " has " + entry.getValue() + " nights (max allowed is 2)");
        }
    }

    @Test
    @DisplayName("05. Female Employees Never Assigned Evening or Night Shifts")
    void test05_FemaleEmployeesNeverAssignedEveningOrNight() {
        LocalDate startDate = LocalDate.of(2026, 11, 2);
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(startDate, GenerationMode.AUTOMATIC, 6);

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());
        for (RosterAssignment a : assignments) {
            if (a.getEmployee().getGender() == Gender.FEMALE) {
                if (!a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null) {
                    ShiftType st = a.getShift().getShiftType();
                    assertNotEquals(ShiftType.EVENING, st, "Female employee assigned Evening shift: " + a.getEmployee().getFirstName());
                    assertNotEquals(ShiftType.NIGHT, st, "Female employee assigned Night shift: " + a.getEmployee().getFirstName());
                }
            }
        }
    }

    @Test
    @DisplayName("06. Minimum 12-Hour Rest Between Consecutive Working Shifts")
    void test06_Minimum12HourRestBetweenConsecutiveWorkingShifts() {
        LocalDate startDate = LocalDate.of(2026, 11, 2);
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(startDate, GenerationMode.AUTOMATIC, 6);

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());
        Map<Long, List<RosterAssignment>> byEmp = new HashMap<>();
        for (RosterAssignment a : assignments) {
            byEmp.computeIfAbsent(a.getEmployee().getId(), k -> new ArrayList<>()).add(a);
        }

        for (List<RosterAssignment> empList : byEmp.values()) {
            empList.sort(Comparator.comparing(RosterAssignment::getRosterDate));
            for (int i = 0; i < empList.size() - 1; i++) {
                RosterAssignment cur = empList.get(i);
                RosterAssignment next = empList.get(i + 1);
                if (!cur.isWeeklyOff() && !cur.isOnLeave() && cur.getShift() != null &&
                        !next.isWeeklyOff() && !next.isOnLeave() && next.getShift() != null) {
                    Shift s1 = cur.getShift();
                    Shift s2 = next.getShift();
                    LocalDateTime end1 = cur.getRosterDate().atTime(s1.getEndTime());
                    if (s1.isOvernight() || s1.getEndTime().isBefore(s1.getStartTime())) {
                        end1 = end1.plusDays(1);
                    }
                    LocalDateTime start2 = next.getRosterDate().atTime(s2.getStartTime());
                    long restMinutes = Duration.between(end1, start2).toMinutes();
                    assertTrue(restMinutes >= 720,
                            String.format("Less than 12h rest (%d mins) between %s (%s) and %s (%s) for %s",
                                    restMinutes, cur.getRosterDate(), s1.getShiftType(), next.getRosterDate(), s2.getShiftType(), cur.getEmployee().getFirstName()));
                }
            }
        }
    }

    @Test
    @DisplayName("07. Morning Shift Staffing Bounds (1 to 2; never 3)")
    void test07_MorningShiftStaffingBounds() {
        LocalDate startDate = LocalDate.of(2026, 11, 2);
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(startDate, GenerationMode.AUTOMATIC, 6);

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());
        Map<LocalDate, Long> morningCounts = new TreeMap<>();
        for (RosterAssignment a : assignments) {
            if (!a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.MORNING) {
                morningCounts.merge(a.getRosterDate(), 1L, Long::sum);
            }
        }

        for (LocalDate d = startDate; !d.isAfter(cycle.endDate()); d = d.plusDays(1)) {
            long count = morningCounts.getOrDefault(d, 0L);
            assertTrue(count >= 1 && count <= 2, "Morning count on " + d + " is " + count + " (must be 1-2)");
        }
    }

    @Test
    @DisplayName("08. General Shift Staffing Bounds (2 to 4 on weekdays)")
    void test08_GeneralShiftStaffingBounds() {
        LocalDate startDate = LocalDate.of(2026, 11, 2);
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(startDate, GenerationMode.AUTOMATIC, 6);

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());
        Map<LocalDate, Long> generalCounts = new TreeMap<>();
        for (RosterAssignment a : assignments) {
            if (!a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.GENERAL) {
                generalCounts.merge(a.getRosterDate(), 1L, Long::sum);
            }
        }

        for (LocalDate d = startDate; !d.isAfter(cycle.endDate()); d = d.plusDays(1)) {
            long count = generalCounts.getOrDefault(d, 0L);
            boolean isWeekend = (d.getDayOfWeek() == java.time.DayOfWeek.SATURDAY || d.getDayOfWeek() == java.time.DayOfWeek.SUNDAY);
            if (!isWeekend) {
                assertTrue(count >= 2 && count <= 4, "Weekday General count on " + d + " is " + count + " (must be 2-4)");
            } else {
                assertTrue(count <= 4, "Weekend General count on " + d + " is " + count + " (max 4)");
            }
        }
    }

    @Test
    @DisplayName("09. Evening Shift Staffing Bounds (1 to 2)")
    void test09_EveningShiftStaffingBounds() {
        LocalDate startDate = LocalDate.of(2026, 11, 2);
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(startDate, GenerationMode.AUTOMATIC, 6);

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());
        Map<LocalDate, Long> eveningCounts = new TreeMap<>();
        for (RosterAssignment a : assignments) {
            if (!a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.EVENING) {
                eveningCounts.merge(a.getRosterDate(), 1L, Long::sum);
            }
        }

        for (LocalDate d = startDate; !d.isAfter(cycle.endDate()); d = d.plusDays(1)) {
            long count = eveningCounts.getOrDefault(d, 0L);
            assertTrue(count >= 1 && count <= 2, "Evening count on " + d + " is " + count + " (must be 1-2)");
        }
    }

    @Test
    @DisplayName("10. Night Shift Staffing Exactly 1 Per Day")
    void test10_NightShiftExactlyOnePerDay() {
        LocalDate startDate = LocalDate.of(2026, 11, 2);
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(startDate, GenerationMode.AUTOMATIC, 6);

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());
        Map<LocalDate, Long> nightCounts = new TreeMap<>();
        for (RosterAssignment a : assignments) {
            if (!a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT) {
                nightCounts.merge(a.getRosterDate(), 1L, Long::sum);
            }
        }

        for (LocalDate d = startDate; !d.isAfter(cycle.endDate()); d = d.plusDays(1)) {
            long count = nightCounts.getOrDefault(d, 0L);
            assertEquals(1L, count, "Night count on " + d + " must be exactly 1");
        }
    }

    @Test
    @DisplayName("11. Date-Specific Admin Preference Strictly Enforced")
    void test11_DateSpecificAdminPreferencePriority() {
        LocalDate targetDate = LocalDate.of(2026, 11, 17); // Tuesday
        LocalDate cycleStart = LocalDate.of(2026, 11, 16);

        Employee rajat = employeeRepository.findById(1L).orElseThrow();
        dateShiftPreferenceService.createOrUpdate(
                new DateShiftPreferenceRequest(rajat.getId(), targetDate, ShiftType.MORNING, "Priority 3 Date Pref Test"),
                "admin"
        );

        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(cycleStart, GenerationMode.AUTOMATIC, 6);
        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());

        RosterAssignment assign = assignments.stream()
                .filter(a -> a.getEmployee().getId().equals(rajat.getId()) && a.getRosterDate().equals(targetDate))
                .findFirst().orElseThrow();

        assertFalse(assign.isWeeklyOff(), "Date preference was for MORNING, not weekly off");
        assertEquals(ShiftType.MORNING, assign.getShift().getShiftType(),
                "Date-specific preference for Rajat on " + targetDate + " must be strictly MORNING");
    }

    @Test
    @DisplayName("12. Recurring Weekly Shift Preference Priority Respected")
    void test12_RecurringWeeklyShiftPreferencePriority() {
        LocalDate cycleStart = LocalDate.of(2026, 11, 23); // Monday

        Employee sambhav = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getFirstName().equalsIgnoreCase("Sambhav")).findFirst().orElseThrow();

        // Configure Recurring Preference: Wednesday = MORNING
        recurringShiftPreferenceService.create(
                new RecurringShiftPreferenceRequest(sambhav.getId(), java.time.DayOfWeek.WEDNESDAY, ShiftType.MORNING,
                        cycleStart, cycleStart.plusDays(28), true, "Batch 113 Recurring Test"),
                "admin"
        );

        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(cycleStart, GenerationMode.AUTOMATIC, 6);
        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());

        LocalDate wednesday = cycleStart.plusDays(2);
        RosterAssignment wedAssign = assignments.stream()
                .filter(a -> a.getEmployee().getId().equals(sambhav.getId()) && a.getRosterDate().equals(wednesday))
                .findFirst().orElseThrow();

        assertEquals(ShiftType.MORNING, wedAssign.getShift().getShiftType(),
                "Recurring preference for Wednesday MORNING must be respected");
    }

    @Test
    @DisplayName("13. Explicit Manual Admin Assignment Preserved During Regeneration")
    void test13_ExplicitManualAdminAssignmentPreserved() {
        LocalDate cycleStart = LocalDate.of(2026, 11, 30);
        RosterCycleResponse initialCycle = rosterService.generateWeeklyRoster(cycleStart, GenerationMode.AUTOMATIC, 6);

        // Manually set Rajat to GENERAL on Tuesday
        LocalDate tuesday = cycleStart.plusDays(1);
        Employee rajat = employeeRepository.findById(1L).orElseThrow();
        Shift generalShift = shiftRepository.findByShiftType(ShiftType.GENERAL).orElseThrow();

        List<RosterAssignment> assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(initialCycle.id());
        RosterAssignment rAssign = assigns.stream()
                .filter(a -> a.getEmployee().getId().equals(rajat.getId()) && a.getRosterDate().equals(tuesday))
                .findFirst().orElseThrow();
        rAssign.setShift(generalShift);
        rAssign.setWeeklyOff(false);
        rAssign.setManualAssignment(true);
        rAssign.setOverridden(true);
        rAssign.setAssignmentSource("MANUAL");
        rosterAssignmentRepository.save(rAssign);

        // Regenerate roster for same week
        RosterCycleResponse regenerated = rosterService.generateWeeklyRoster(cycleStart, GenerationMode.AUTOMATIC, 6);
        List<RosterAssignment> regenAssigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(regenerated.id());

        RosterAssignment preserved = regenAssigns.stream()
                .filter(a -> a.getEmployee().getId().equals(rajat.getId()) && a.getRosterDate().equals(tuesday))
                .findFirst().orElseThrow();

        assertTrue(preserved.isManualAssignment(), "Manual assignment flag must be preserved");
        assertEquals(ShiftType.GENERAL, preserved.getShift().getShiftType(), "Manual shift type GENERAL must be preserved");
    }

    @Test
    @DisplayName("14. Approved Leave Respected (ON_LEAVE status, no working shift)")
    void test14_ApprovedLeaveRespected() {
        LocalDate cycleStart = LocalDate.of(2026, 12, 7);
        LocalDate leaveDate = cycleStart.plusDays(3); // Thursday

        Employee shriram = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getFirstName().equalsIgnoreCase("Shriram")).findFirst().orElseThrow();

        LeaveRequest lr = new LeaveRequest();
        lr.setEmployee(shriram);
        lr.setStartDate(leaveDate);
        lr.setEndDate(leaveDate);
        lr.setStatus(LeaveStatus.APPROVED);
        lr.setRequestedAt(LocalDateTime.now());
        lr.setReason("Batch 113 Test Leave");
        leaveRequestRepository.save(lr);

        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(cycleStart, GenerationMode.AUTOMATIC, 6);
        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());

        RosterAssignment leaveAssign = assignments.stream()
                .filter(a -> a.getEmployee().getId().equals(shriram.getId()) && a.getRosterDate().equals(leaveDate))
                .findFirst().orElseThrow();

        assertTrue(leaveAssign.isOnLeave(), "Assignment must have isOnLeave = true");
        assertEquals(ShiftType.OFF, leaveAssign.getShift().getShiftType(), "Leave assignment shift must be OFF");
    }

    @Test
    @DisplayName("15. Draft Generation Never Sends Employee Emails")
    void test15_DraftGenerationNeverSendsEmployeeEmails() {
        LocalDate cycleStart = LocalDate.of(2026, 12, 14);
        long logsBefore = emailDeliveryLogRepository.count();

        RosterCycleResponse draftCycle = rosterService.generateWeeklyRoster(cycleStart, GenerationMode.MANUAL, 6);
        assertNotNull(draftCycle);

        long logsAfter = emailDeliveryLogRepository.count();
        assertEquals(logsBefore, logsAfter, "Draft generation must not send any emails to employees");
    }

    @Test
    @DisplayName("16. Publish Roster Sends Final Emails Idempotently (No Duplicates)")
    void test16_PublishRosterSendsFinalEmailsIdempotently() {
        LocalDate cycleStart = LocalDate.of(2026, 12, 21);
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(cycleStart, GenerationMode.AUTOMATIC, 6);

        // First publish
        rosterService.publishRoster(cycle.id());
        long sentCountFirst = emailDeliveryLogRepository.count();

        // Second publish attempt on same cycle (idempotency check)
        rosterService.publishRoster(cycle.id());
        long sentCountSecond = emailDeliveryLogRepository.count();

        assertEquals(sentCountFirst, sentCountSecond, "Repeated publish must be strictly idempotent with 0 duplicate emails");
    }

    @Test
    @DisplayName("17. Repeated Generation Does Not Create Duplicate Cycles or Corrupt Data")
    void test17_RegenerationDoesNotCreateDuplicateCyclesOrCorruptAssignments() {
        LocalDate cycleStart = LocalDate.of(2026, 12, 28);

        RosterCycleResponse gen1 = rosterService.generateWeeklyRoster(cycleStart, GenerationMode.AUTOMATIC, 6);
        RosterCycleResponse gen2 = rosterService.generateWeeklyRoster(cycleStart, GenerationMode.AUTOMATIC, 6);
        RosterCycleResponse gen3 = rosterService.generateWeeklyRoster(cycleStart, GenerationMode.AUTOMATIC, 6);

        assertEquals(gen1.id(), gen2.id(), "Cycle ID should be reused for draft regeneration");
        assertEquals(gen2.id(), gen3.id());

        List<RosterAssignment> assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(gen3.id());
        assertEquals(49, assigns.size(), "Total assignments must remain exactly 49 (7 employees x 7 days)");
    }

    @Test
    @DisplayName("18. Target Week 02-Nov to 08-Nov Valid Production Ready Roster (0 Validation Errors)")
    void test18_TargetWeekNov02ToNov08ValidProductionReadyRoster() {
        LocalDate targetWeek = LocalDate.of(2026, 11, 2);
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(targetWeek, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle);

        RosterValidationResponse validation = rosterValidatorService.validateRoster(cycle.id());
        assertNotNull(validation);
        assertEquals(0, validation.errorCount(), "Target week 02-Nov-2026 must have 0 validation errors");
        assertNotEquals(ValidationSeverity.ERROR, validation.overallStatus(), "Overall validation status must not be ERROR");

        // Verify publish succeeds cleanly
        RosterCycleResponse published = rosterService.publishRoster(cycle.id());
        assertTrue(published.status() == RosterStatus.PUBLISHED || published.status() == RosterStatus.FINAL,
                "Published roster status must be PUBLISHED or FINAL");
    }
}
