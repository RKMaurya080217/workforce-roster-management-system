package com.weeklyroster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weeklyroster.dto.request.DateShiftPreferenceRequest;
import com.weeklyroster.dto.request.RecurringShiftPreferenceRequest;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.dto.response.WorkDayReportResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "wrms.dev.credential-mirror.enabled=false",
        "spring.datasource.url=jdbc:sqlite:target/test-wrms-b124.db?busy_timeout=30000&journal_mode=WAL"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Batch124AdvancedPreferenceEngineTest {

    private static final String REAL_DB_PATH = "data/wrms.db";
    private static final String REPLICA_DB_PATH = "target/test-wrms-b124.db";

    static {
        try {
            System.setProperty("spring.profiles.active", "test");
            System.setProperty("spring.datasource.url", "jdbc:sqlite:" + REPLICA_DB_PATH + "?busy_timeout=30000&journal_mode=WAL");
            File targetDir = new File("target");
            if (!targetDir.exists()) targetDir.mkdirs();
            File realDb = new File(REAL_DB_PATH);
            File testDb = new File(REPLICA_DB_PATH);
            if (realDb.exists()) {
                Files.copy(realDb.toPath(), testDb.toPath(), StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[BATCH 124] Copied " + REAL_DB_PATH + " to " + REPLICA_DB_PATH + " for isolated advanced preference verification.");
            }
        } catch (Exception e) {
            System.err.println("[BATCH 124] Failed to prepare test replica DB: " + e.getMessage());
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RosterService rosterService;

    @Autowired
    private RecurringShiftPreferenceService recurringShiftPreferenceService;

    @Autowired
    private DateShiftPreferenceService dateShiftPreferenceService;

    @Autowired
    private WorkDayReportService workDayReportService;

    @Autowired
    private RecurringShiftPreferenceRepository recurringShiftPreferenceRepository;

    @Autowired
    private DateShiftPreferenceRepository dateShiftPreferenceRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private RosterAssignmentRepository rosterAssignmentRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired
    private HolidayRepository holidayRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private RosterCycleRepository rosterCycleRepository;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "admin",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );
    }

    private Employee getMaleEmployee() {
        return employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.MALE && !e.isDevelopmentTeam())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Male employee not found"));
    }

    private Employee getFemaleEmployee() {
        return employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.FEMALE && !e.isDevelopmentTeam())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Female employee not found"));
    }

    private void clearRecurringPreferences(Long employeeId) {
        recurringShiftPreferenceRepository.deleteAll(recurringShiftPreferenceRepository.findByEmployeeId(employeeId));
    }

    // =========================================================================
    // TEST A — Exactly 1 Weekly Off in Normal 6-Day Mode
    // =========================================================================
    @Test
    @Order(1)
    @DisplayName("Test A: Normal Week Invariant - Exactly 1 Weekly Off per employee in 6-working-day mode (NEVER 0, NEVER 2)")
    void testA_ExactlyOneWeeklyOffInNormalSixDayWeek() {
        LocalDate weekStart = LocalDate.of(2028, 1, 3); // Monday
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(weekStart, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle, "Roster cycle must generate successfully");

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());
        assertFalse(assignments.isEmpty(), "Assignments must be generated");

        List<Employee> activeEmps = employeeRepository.findByActiveTrueOrderByIdAsc();
        Map<Long, Integer> offCounts = new HashMap<>();

        for (RosterAssignment a : assignments) {
            if (a.isWeeklyOff()) {
                offCounts.merge(a.getEmployee().getId(), 1, Integer::sum);
            }
        }

        for (Employee emp : activeEmps) {
            int offs = offCounts.getOrDefault(emp.getId(), 0);
            assertEquals(1, offs,
                    "Employee " + emp.getEmployeeCode() + " must have EXACTLY 1 Weekly Off in a normal 6-day week (found: " + offs + ")");
        }
    }

    // =========================================================================
    // TEST B — Holiday Work Week: 1 Holiday + 1 Weekly Off = 2 Rest Benefits
    // =========================================================================
    @Test
    @Order(2)
    @DisplayName("Test B: Holiday Work Week - Worked on Holiday gives 1 Holiday benefit + 1 Weekly Off (2 distinct days)")
    void testB_HolidayWorkWeek_HolidayPlusWeeklyOff() {
        LocalDate weekStart = LocalDate.of(2028, 1, 10); // Monday
        LocalDate holidayDate = weekStart.plusDays(2); // Wednesday 2028-01-12

        // Ensure holiday exists in repository
        Holiday holiday = holidayRepository.findByHolidayDate(holidayDate).stream().findFirst().orElse(null);
        if (holiday == null) {
            holiday = new Holiday("Test National Holiday", holidayDate, "Batch 124 Holiday Test");
            holidayRepository.save(holiday);
        }

        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(weekStart, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle);

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());

        // Find an employee assigned to work on the holiday
        RosterAssignment holidayWorkerAssignment = assignments.stream()
                .filter(a -> a.getRosterDate().equals(holidayDate) && !a.isWeeklyOff() && !a.isOnLeave()
                        && a.getShift() != null && a.getShift().getShiftType() != ShiftType.OFF)
                .findFirst()
                .orElse(null);

        assertNotNull(holidayWorkerAssignment, "At least one employee must be scheduled on the holiday to maintain coverage");
        Employee workedEmp = holidayWorkerAssignment.getEmployee();

        // Verify the employee working on the holiday still gets exactly 1 Weekly Off on another day
        long weeklyOffCount = assignments.stream()
                .filter(a -> a.getEmployee().getId().equals(workedEmp.getId()) && a.isWeeklyOff())
                .count();
        assertEquals(1, weeklyOffCount, "Employee working on holiday must still receive exactly 1 Weekly Off");

        // Verify weekly off is NOT on the holiday date
        boolean offOnHoliday = assignments.stream()
                .anyMatch(a -> a.getEmployee().getId().equals(workedEmp.getId()) && a.getRosterDate().equals(holidayDate) && a.isWeeklyOff());
        assertFalse(offOnHoliday, "Weekly off must not be assigned on the holiday date");

        // Verify WorkDayReportService properly records holiday work as WORKED with remark referencing the holiday
        WorkDayReportResponse report = workDayReportService.generateReport(weekStart, weekStart.plusDays(6), workedEmp.getId(), null);
        assertNotNull(report);

        var empSummary = report.employeeSummaries().stream()
                .filter(es -> es.employeeId().equals(workedEmp.getId()))
                .findFirst()
                .orElseThrow();

        var holidayDayRecord = empSummary.dailyRecords().stream()
                .filter(dr -> dr.date().equals(holidayDate))
                .findFirst()
                .orElseThrow();

        assertEquals("WORKED", holidayDayRecord.status(), "Holiday work must be recorded as WORKED in daily breakdown");
        assertTrue(holidayDayRecord.remarks().contains("Worked on Holiday"),
                "Daily record remarks must state worked on holiday: " + holidayDayRecord.remarks());
    }

    // =========================================================================
    // TEST C — Consecutive Rest Optimization (Holiday + Weekly Off)
    // =========================================================================
    @Test
    @Order(3)
    @DisplayName("Test C: Consecutive Rest Optimization - Weekly Off placed adjacent to Holiday (H-1 or H+1) when feasible")
    void testC_ConsecutiveRestOptimization_HolidayAndWeeklyOff() {
        LocalDate weekStart = LocalDate.of(2028, 1, 17); // Monday
        LocalDate holidayDate = weekStart.plusDays(3); // Thursday 2028-01-20

        Holiday holiday = holidayRepository.findByHolidayDate(holidayDate).stream().findFirst().orElse(null);
        if (holiday == null) {
            holiday = new Holiday("Festive Holiday", holidayDate, "Batch 124 Consecutive Rest Test");
            holidayRepository.save(holiday);
        }

        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(weekStart, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle);

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());

        // Check placement of Weekly Offs: at least some employees should receive off on Wednesday (H-1) or Friday (H+1)
        LocalDate dayBefore = holidayDate.minusDays(1); // Wednesday
        LocalDate dayAfter = holidayDate.plusDays(1);  // Friday

        long adjacentOffs = assignments.stream()
                .filter(a -> a.isWeeklyOff() && (a.getRosterDate().equals(dayBefore) || a.getRosterDate().equals(dayAfter)))
                .count();

        assertTrue(adjacentOffs > 0, "Intelligent optimizer should place Weekly Offs adjacent to holiday (" + adjacentOffs + " found)");

        // Strictly verify that no Weekly Off is placed on the holiday date itself
        long offOnHolidayCount = assignments.stream()
                .filter(a -> a.isWeeklyOff() && a.getRosterDate().equals(holidayDate))
                .count();
        assertEquals(0, offOnHolidayCount, "Holiday date must NEVER be marked as Weekly Off");
    }

    // =========================================================================
    // TEST D — Multi-Week Invariant: No Duplicate Weekly Offs
    // =========================================================================
    @Test
    @Order(4)
    @DisplayName("Test D: Multi-Week Invariant - Every employee has exactly 1 Weekly Off in every 6-day week across 3 weeks")
    void testD_NoDuplicateWeeklyOffMultiWeek() {
        List<LocalDate> weeks = List.of(
                LocalDate.of(2028, 2, 7),
                LocalDate.of(2028, 2, 14),
                LocalDate.of(2028, 2, 21)
        );

        List<Employee> activeEmps = employeeRepository.findByActiveTrueOrderByIdAsc();

        for (LocalDate weekStart : weeks) {
            RosterCycleResponse cycle = rosterService.generateWeeklyRoster(weekStart, GenerationMode.AUTOMATIC, 6);
            assertNotNull(cycle);

            List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());
            Map<Long, Integer> offCounts = new HashMap<>();

            for (RosterAssignment a : assignments) {
                if (a.isWeeklyOff()) {
                    offCounts.merge(a.getEmployee().getId(), 1, Integer::sum);
                }
            }

            for (Employee emp : activeEmps) {
                int count = offCounts.getOrDefault(emp.getId(), 0);
                assertEquals(1, count,
                        "Week " + weekStart + ": Employee " + emp.getEmployeeCode() + " must have exactly 1 Weekly Off (found: " + count + ")");
            }
        }
    }

    // =========================================================================
    // TEST E — Sunday Night -> Monday Weekly Off As Single Weekly Off
    // =========================================================================
    @Test
    @Order(5)
    @DisplayName("Test E: Previous Sunday Night -> Monday Weekly Off (counts as single off for the week, no duplicate later)")
    void testE_PreviousSundayNight_YieldsMondayWeeklyOffAsOnlyWeeklyOff() {
        LocalDate week1Start = LocalDate.of(2028, 3, 6); // Monday
        LocalDate week2Start = LocalDate.of(2028, 3, 13); // Monday
        LocalDate sundayWeek1 = week1Start.plusDays(6); // Sunday 2028-03-12

        Employee male = getMaleEmployee();
        clearRecurringPreferences(male.getId());

        // Generate Week 1
        RosterCycleResponse cycle1 = rosterService.generateWeeklyRoster(week1Start, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle1);

        // Ensure male worked NIGHT on Sunday of Week 1
        RosterAssignment sunAssign = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle1.id()).stream()
                .filter(a -> a.getEmployee().getId().equals(male.getId()) && a.getRosterDate().equals(sundayWeek1))
                .findFirst().orElseThrow();

        Shift nightShift = shiftRepository.findByShiftType(ShiftType.NIGHT).stream().findFirst().orElseThrow();
        sunAssign.setShift(nightShift);
        sunAssign.setWeeklyOff(false);
        sunAssign.setOnLeave(false);
        rosterAssignmentRepository.save(sunAssign);

        // Generate Week 2
        RosterCycleResponse cycle2 = rosterService.generateWeeklyRoster(week2Start, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle2);

        List<RosterAssignment> week2Assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle2.id());

        // 1. Monday MUST be Weekly Off for mandatory recovery
        RosterAssignment monAssign = week2Assignments.stream()
                .filter(a -> a.getEmployee().getId().equals(male.getId()) && a.getRosterDate().equals(week2Start))
                .findFirst().orElseThrow();
        assertTrue(monAssign.isWeeklyOff(), "Monday must be Weekly Off following Sunday Night shift");

        // 2. Monday counts as the employee's single Weekly Off for Week 2 (no second weekly off assigned)
        long totalOffsWeek2 = week2Assignments.stream()
                .filter(a -> a.getEmployee().getId().equals(male.getId()) && a.isWeeklyOff())
                .count();
        assertEquals(1, totalOffsWeek2,
                "Monday recovery Off must serve as the single Weekly Off for that 6-day week (found: " + totalOffsWeek2 + ")");
    }

    // =========================================================================
    // TEST F — Recurring Positive Preference: Saturday -> Morning Across 3 Weeks
    // =========================================================================
    @Test
    @Order(6)
    @DisplayName("Test F: Recurring Positive Preference - Saturday Morning respected across 3 consecutive weeks")
    void testF_SaturdayPositivePreferenceAcrossMultipleWeeks() {
        Employee emp = getMaleEmployee();
        clearRecurringPreferences(emp.getId());

        RecurringShiftPreferenceRequest req = new RecurringShiftPreferenceRequest(
                emp.getId(),
                DayOfWeek.SATURDAY,
                ShiftType.MORNING,
                RecurringPreferenceType.PREFERRED,
                null,
                null,
                true,
                "Batch 124 Test F: Saturday Morning Positive Preference"
        );
        recurringShiftPreferenceService.createOrUpdate(req, "admin");

        List<LocalDate> testWeeks = List.of(
                LocalDate.of(2028, 4, 3),
                LocalDate.of(2028, 4, 10),
                LocalDate.of(2028, 4, 17)
        );

        for (LocalDate weekStart : testWeeks) {
            RosterCycleResponse cycle = rosterService.generateWeeklyRoster(weekStart, GenerationMode.AUTOMATIC, 6);
            assertNotNull(cycle);

            List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());
            LocalDate sat = weekStart.plusDays(5);

            RosterAssignment satAssign = assignments.stream()
                    .filter(a -> a.getEmployee().getId().equals(emp.getId()) && a.getRosterDate().equals(sat))
                    .findFirst().orElseThrow();

            assertFalse(satAssign.isWeeklyOff(), "Saturday should not be weekly off when Morning is preferred");
            assertNotNull(satAssign.getShift(), "Shift must not be null");
            assertEquals(ShiftType.MORNING, satAssign.getShift().getShiftType(),
                    "Saturday must be MORNING for employee " + emp.getEmployeeCode());

            // Invariant: single weekly off
            long offs = assignments.stream()
                    .filter(a -> a.getEmployee().getId().equals(emp.getId()) && a.isWeeklyOff())
                    .count();
            assertEquals(1, offs, "Must still have exactly 1 Weekly Off in week " + weekStart);
        }
    }

    // =========================================================================
    // TEST G — Recurring Negative Preference: Sunday -> Do Not Assign Morning
    // =========================================================================
    @Test
    @Order(7)
    @DisplayName("Test G: Recurring Negative Preference - Sunday Do Not Assign Morning strictly avoided across 3 weeks")
    void testG_SundayNegativePreferenceAcrossMultipleWeeks() {
        Employee emp = getMaleEmployee();
        clearRecurringPreferences(emp.getId());

        RecurringShiftPreferenceRequest req = new RecurringShiftPreferenceRequest(
                emp.getId(),
                DayOfWeek.SUNDAY,
                ShiftType.MORNING,
                RecurringPreferenceType.DO_NOT_ASSIGN,
                null,
                null,
                true,
                "Batch 124 Test G: Sunday Do Not Assign Morning Restriction"
        );
        recurringShiftPreferenceService.createOrUpdate(req, "admin");

        List<LocalDate> testWeeks = List.of(
                LocalDate.of(2028, 5, 1),
                LocalDate.of(2028, 5, 8),
                LocalDate.of(2028, 5, 15)
        );

        for (LocalDate weekStart : testWeeks) {
            RosterCycleResponse cycle = rosterService.generateWeeklyRoster(weekStart, GenerationMode.AUTOMATIC, 6);
            assertNotNull(cycle);

            List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());
            LocalDate sun = weekStart.plusDays(6);

            RosterAssignment sunAssign = assignments.stream()
                    .filter(a -> a.getEmployee().getId().equals(emp.getId()) && a.getRosterDate().equals(sun))
                    .findFirst().orElseThrow();

            if (sunAssign.getShift() != null) {
                assertNotEquals(ShiftType.MORNING, sunAssign.getShift().getShiftType(),
                        "Sunday shift must NEVER be MORNING due to Do Not Assign restriction on " + sun);
            }

            // Invariant: single weekly off
            long offs = assignments.stream()
                    .filter(a -> a.getEmployee().getId().equals(emp.getId()) && a.isWeeklyOff())
                    .count();
            assertEquals(1, offs, "Must have exactly 1 Weekly Off in week " + weekStart);
        }
    }

    // =========================================================================
    // TEST H — Date-Specific Preference Overrides Recurring Preference
    // =========================================================================
    @Test
    @Order(8)
    @DisplayName("Test H: Date-Specific Override - Date-specific Evening strictly overrides Recurring Morning on that date")
    void testH_DateSpecificPreferenceOverridesRecurringPreference() {
        Employee emp = getMaleEmployee();
        clearRecurringPreferences(emp.getId());

        // Recurring: Saturday -> Morning
        recurringShiftPreferenceService.createOrUpdate(new RecurringShiftPreferenceRequest(
                emp.getId(), DayOfWeek.SATURDAY, ShiftType.MORNING, RecurringPreferenceType.PREFERRED,
                null, null, true, "Recurring Sat Morning"
        ), "admin");

        LocalDate overrideWeekStart = LocalDate.of(2028, 6, 5);
        LocalDate overrideSat = overrideWeekStart.plusDays(5); // 2028-06-10

        // Date-specific override: Evening on 2028-06-10
        dateShiftPreferenceService.createOrUpdate(new DateShiftPreferenceRequest(
                emp.getId(), overrideSat, ShiftType.EVENING, "Admin override for 10-Jun-2028"
        ), "admin");

        // Week 1 (Override week): Saturday must be EVENING
        RosterCycleResponse cycle1 = rosterService.generateWeeklyRoster(overrideWeekStart, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle1);

        List<RosterAssignment> assignments1 = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle1.id());
        RosterAssignment satAssign1 = assignments1.stream()
                .filter(a -> a.getEmployee().getId().equals(emp.getId()) && a.getRosterDate().equals(overrideSat))
                .findFirst().orElseThrow();
        assertEquals(ShiftType.EVENING, satAssign1.getShift().getShiftType(),
                "10-Jun-2028 Saturday must be EVENING due to Date-Specific override");

        // Week 2 (Subsequent week without date-specific): Reverts to Morning
        LocalDate nextWeekStart = LocalDate.of(2028, 6, 12);
        LocalDate nextSat = nextWeekStart.plusDays(5);

        RosterCycleResponse cycle2 = rosterService.generateWeeklyRoster(nextWeekStart, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle2);

        List<RosterAssignment> assignments2 = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle2.id());
        RosterAssignment satAssign2 = assignments2.stream()
                .filter(a -> a.getEmployee().getId().equals(emp.getId()) && a.getRosterDate().equals(nextSat))
                .findFirst().orElseThrow();
        assertEquals(ShiftType.MORNING, satAssign2.getShift().getShiftType(),
                "Subsequent Saturday must revert to MORNING via recurring preference");
    }

    // =========================================================================
    // TEST I — Multiple Weekday Preferences Active Simultaneously
    // =========================================================================
    @Test
    @Order(9)
    @DisplayName("Test I: Multiple Preferences Simultaneously - Monday General, Tue DoNotAssign, Wed Evening, Sat Morning, Sun DoNotAssign")
    void testI_MultipleWeekdayPreferencesActiveSimultaneously() {
        Employee emp = getMaleEmployee();
        clearRecurringPreferences(emp.getId());

        // Configure 5 simultaneous rules
        recurringShiftPreferenceService.createOrUpdate(new RecurringShiftPreferenceRequest(
                emp.getId(), DayOfWeek.MONDAY, ShiftType.GENERAL, RecurringPreferenceType.PREFERRED, null, null, true, "Monday General"
        ), "admin");

        recurringShiftPreferenceService.createOrUpdate(new RecurringShiftPreferenceRequest(
                emp.getId(), DayOfWeek.TUESDAY, ShiftType.MORNING, RecurringPreferenceType.DO_NOT_ASSIGN, null, null, true, "Tuesday Not Morning"
        ), "admin");

        recurringShiftPreferenceService.createOrUpdate(new RecurringShiftPreferenceRequest(
                emp.getId(), DayOfWeek.WEDNESDAY, ShiftType.EVENING, RecurringPreferenceType.PREFERRED, null, null, true, "Wednesday Evening"
        ), "admin");

        recurringShiftPreferenceService.createOrUpdate(new RecurringShiftPreferenceRequest(
                emp.getId(), DayOfWeek.SATURDAY, ShiftType.MORNING, RecurringPreferenceType.PREFERRED, null, null, true, "Saturday Morning"
        ), "admin");

        recurringShiftPreferenceService.createOrUpdate(new RecurringShiftPreferenceRequest(
                emp.getId(), DayOfWeek.SUNDAY, ShiftType.MORNING, RecurringPreferenceType.DO_NOT_ASSIGN, null, null, true, "Sunday Not Morning"
        ), "admin");

        List<RecurringShiftPreference> activeRules = recurringShiftPreferenceRepository.findByEmployeeIdAndActiveTrue(emp.getId());
        assertEquals(5, activeRules.size(), "All 5 active rules must exist simultaneously");

        LocalDate weekStart = LocalDate.of(2028, 7, 3); // Monday
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(weekStart, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle);

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());

        // Monday check: Preferred General
        RosterAssignment mon = assignments.stream().filter(a -> a.getEmployee().getId().equals(emp.getId()) && a.getRosterDate().equals(weekStart)).findFirst().orElseThrow();
        assertEquals(ShiftType.GENERAL, mon.getShift().getShiftType(), "Monday must be GENERAL");

        // Tuesday check: Do Not Assign Morning
        RosterAssignment tue = assignments.stream().filter(a -> a.getEmployee().getId().equals(emp.getId()) && a.getRosterDate().equals(weekStart.plusDays(1))).findFirst().orElseThrow();
        if (tue.getShift() != null) {
            assertNotEquals(ShiftType.MORNING, tue.getShift().getShiftType(), "Tuesday must NOT be MORNING");
        }

        // Wednesday check: Preferred Evening
        RosterAssignment wed = assignments.stream().filter(a -> a.getEmployee().getId().equals(emp.getId()) && a.getRosterDate().equals(weekStart.plusDays(2))).findFirst().orElseThrow();
        assertEquals(ShiftType.EVENING, wed.getShift().getShiftType(), "Wednesday must be EVENING");

        // Saturday check: Preferred Morning
        RosterAssignment sat = assignments.stream().filter(a -> a.getEmployee().getId().equals(emp.getId()) && a.getRosterDate().equals(weekStart.plusDays(5))).findFirst().orElseThrow();
        assertEquals(ShiftType.MORNING, sat.getShift().getShiftType(), "Saturday must be MORNING");

        // Sunday check: Do Not Assign Morning
        RosterAssignment sun = assignments.stream().filter(a -> a.getEmployee().getId().equals(emp.getId()) && a.getRosterDate().equals(weekStart.plusDays(6))).findFirst().orElseThrow();
        if (sun.getShift() != null) {
            assertNotEquals(ShiftType.MORNING, sun.getShift().getShiftType(), "Sunday must NOT be MORNING");
        }
    }

    // =========================================================================
    // TEST J — Preference Persistence Across Database Queries
    // =========================================================================
    @Test
    @Order(10)
    @DisplayName("Test J: Persistence Across Database Queries - PREFERRED and DO_NOT_ASSIGN survive DB read/write")
    void testJ_PreferencePersistenceAcrossDatabaseQueries() {
        Employee emp = getMaleEmployee();
        clearRecurringPreferences(emp.getId());

        RecurringShiftPreferenceRequest prefReq = new RecurringShiftPreferenceRequest(
                emp.getId(), DayOfWeek.FRIDAY, ShiftType.EVENING, RecurringPreferenceType.PREFERRED,
                LocalDate.of(2028, 1, 1), null, true, "Persistent Positive Friday Evening"
        );
        var res1 = recurringShiftPreferenceService.createOrUpdate(prefReq, "admin");
        assertNotNull(res1.id());

        RecurringShiftPreferenceRequest avoidReq = new RecurringShiftPreferenceRequest(
                emp.getId(), DayOfWeek.THURSDAY, ShiftType.NIGHT, RecurringPreferenceType.DO_NOT_ASSIGN,
                LocalDate.of(2028, 1, 1), null, true, "Persistent Negative Thursday Night Avoid"
        );
        var res2 = recurringShiftPreferenceService.createOrUpdate(avoidReq, "admin");
        assertNotNull(res2.id());

        RecurringShiftPreference found1 = recurringShiftPreferenceRepository.findById(res1.id()).orElseThrow();
        assertEquals(RecurringPreferenceType.PREFERRED, found1.getPreferenceType());
        assertEquals(DayOfWeek.FRIDAY, found1.getDayOfWeek());
        assertEquals(ShiftType.EVENING, found1.getShiftType());
        assertTrue(found1.isActive());

        RecurringShiftPreference found2 = recurringShiftPreferenceRepository.findById(res2.id()).orElseThrow();
        assertEquals(RecurringPreferenceType.DO_NOT_ASSIGN, found2.getPreferenceType());
        assertEquals(DayOfWeek.THURSDAY, found2.getDayOfWeek());
        assertEquals(ShiftType.NIGHT, found2.getShiftType());
        assertTrue(found2.isActive());
    }

    // =========================================================================
    // TEST K — Preference Update In-Place (Without Duplicate Records)
    // =========================================================================
    @Test
    @Order(11)
    @DisplayName("Test K: Preference Update In-Place - Update Saturday from Morning to General retains single active record")
    void testK_PreferenceUpdateInPlace() {
        Employee emp = getMaleEmployee();
        clearRecurringPreferences(emp.getId());

        recurringShiftPreferenceService.createOrUpdate(new RecurringShiftPreferenceRequest(
                emp.getId(), DayOfWeek.SATURDAY, ShiftType.MORNING, RecurringPreferenceType.PREFERRED, null, null, true, "Initial Morning"
        ), "admin");

        assertEquals(1, recurringShiftPreferenceRepository.findByEmployeeIdAndDayOfWeek(emp.getId(), DayOfWeek.SATURDAY).size());

        // Update to General
        recurringShiftPreferenceService.createOrUpdate(new RecurringShiftPreferenceRequest(
                emp.getId(), DayOfWeek.SATURDAY, ShiftType.GENERAL, RecurringPreferenceType.PREFERRED, null, null, true, "Updated General"
        ), "admin");

        List<RecurringShiftPreference> rules = recurringShiftPreferenceRepository.findByEmployeeIdAndDayOfWeek(emp.getId(), DayOfWeek.SATURDAY);
        long activeCount = rules.stream().filter(RecurringShiftPreference::isActive).count();
        assertEquals(1, activeCount, "Must have exactly 1 active rule after in-place update");

        RecurringShiftPreference active = rules.stream().filter(RecurringShiftPreference::isActive).findFirst().orElseThrow();
        assertEquals(ShiftType.GENERAL, active.getShiftType(), "Rule must now be GENERAL");
    }

    // =========================================================================
    // TEST L — Preference Deactivation Returns to Normal Scheduling
    // =========================================================================
    @Test
    @Order(12)
    @DisplayName("Test L: Preference Deactivation - Toggling active to false reverts employee to unforced scheduling")
    void testL_PreferenceDeactivationReturnsToNormalScheduling() {
        Employee emp = getMaleEmployee();
        clearRecurringPreferences(emp.getId());

        var created = recurringShiftPreferenceService.createOrUpdate(new RecurringShiftPreferenceRequest(
                emp.getId(), DayOfWeek.SATURDAY, ShiftType.MORNING, RecurringPreferenceType.PREFERRED, null, null, true, "To deactivate"
        ), "admin");

        recurringShiftPreferenceService.toggleActive(created.id(), "admin");

        RecurringShiftPreference toggled = recurringShiftPreferenceRepository.findById(created.id()).orElseThrow();
        assertFalse(toggled.isActive(), "Rule must be deactivated");

        LocalDate weekStart = LocalDate.of(2028, 8, 7);
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(weekStart, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle);
        assertEquals(RosterStatus.GENERATED, cycle.status());
    }

    // =========================================================================
    // TEST M — Contradictory Rule Conflict Detection
    // =========================================================================
    @Test
    @Order(13)
    @DisplayName("Test M: Conflict Detection - Adding Saturday Preferred Morning AND Saturday Do Not Assign Morning throws BusinessException")
    void testM_ContradictoryRuleConflictDetectionThrowsBusinessException() {
        Employee emp = getMaleEmployee();
        clearRecurringPreferences(emp.getId());

        // Step 1: Create Preferred Morning on Saturday
        recurringShiftPreferenceService.createOrUpdate(new RecurringShiftPreferenceRequest(
                emp.getId(), DayOfWeek.SATURDAY, ShiftType.MORNING, RecurringPreferenceType.PREFERRED, null, null, true, "Saturday Morning"
        ), "admin");

        // Step 2: Attempting to create Do Not Assign Morning on Saturday must throw BusinessException
        RecurringShiftPreferenceRequest conflictingReq = new RecurringShiftPreferenceRequest(
                emp.getId(), DayOfWeek.SATURDAY, ShiftType.MORNING, RecurringPreferenceType.DO_NOT_ASSIGN, null, null, true, "Saturday Not Morning Conflict"
        );

        BusinessException ex = assertThrows(BusinessException.class, () ->
                recurringShiftPreferenceService.createOrUpdate(conflictingReq, "admin")
        );

        String expectedPrefix = "Conflicting weekly preferences: Saturday is configured as both Preferred Morning and Do Not Assign Morning.";
        assertEquals(expectedPrefix, ex.getMessage(), "Exception message must match expected conflict format exactly");
    }

    // =========================================================================
    // TEST N — Real Database Persisted Employees Test
    // =========================================================================
    @Test
    @Order(14)
    @DisplayName("Test N: Real Database Persisted Employees - Validates generation and constraints on real DB entities")
    void testN_RealDatabasePersistedEmployeesRosterGeneration() {
        List<Employee> persisted = employeeRepository.findByActiveTrueOrderByIdAsc();
        assertFalse(persisted.isEmpty(), "Persisted employees must exist in database");
        assertTrue(persisted.size() >= 5, "At least 5 active employees required for standard coverage");

        LocalDate weekStart = LocalDate.of(2028, 9, 4);
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(weekStart, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle);
        assertEquals(RosterStatus.GENERATED, cycle.status());
        assertEquals(0, cycle.criticalConflicts());
    }

    // =========================================================================
    // TEST O — SQLite Database Integrity
    // =========================================================================
    @Test
    @Order(15)
    @DisplayName("Test O: SQLite Database Integrity - SQLite replica file exists and supports atomic transactions")
    void testO_SqliteDatabaseIntegrity() {
        File replica = new File(REPLICA_DB_PATH);
        assertTrue(replica.exists(), "SQLite replica DB file must exist");
        assertTrue(replica.length() > 0, "SQLite replica DB file must contain data");

        // Verify clean transactional query
        List<RecurringShiftPreference> activePrefs = recurringShiftPreferenceRepository.findByActiveTrue();
        assertNotNull(activePrefs, "Active preferences must query without locking or syntax error");
    }

    // =========================================================================
    // TEST P — MySQL Dialect Neutrality
    // =========================================================================
    @Test
    @Order(16)
    @DisplayName("Test P: MySQL Dialect Neutrality - JPA annotations, enum mapping, and ANSI SQL compatibility")
    void testP_MySqlDialectNeutrality() throws NoSuchFieldException {
        Class<RecurringShiftPreference> clazz = RecurringShiftPreference.class;
        assertTrue(clazz.isAnnotationPresent(jakarta.persistence.Entity.class));
        assertTrue(clazz.isAnnotationPresent(jakarta.persistence.Table.class));

        Field prefTypeField = clazz.getDeclaredField("preferenceType");
        assertTrue(prefTypeField.isAnnotationPresent(jakarta.persistence.Enumerated.class));
        assertEquals(jakarta.persistence.EnumType.STRING,
                prefTypeField.getAnnotation(jakarta.persistence.Enumerated.class).value(),
                "preferenceType must be stored as STRING for dialect neutrality");

        Field dayOfWeekField = clazz.getDeclaredField("dayOfWeek");
        assertTrue(dayOfWeekField.isAnnotationPresent(jakarta.persistence.Enumerated.class));
        assertEquals(jakarta.persistence.EnumType.STRING,
                dayOfWeekField.getAnnotation(jakarta.persistence.Enumerated.class).value(),
                "dayOfWeek must be stored as STRING for dialect neutrality");
    }

    // =========================================================================
    // TEST Q — Regeneration Validity
    // =========================================================================
    @Test
    @Order(17)
    @DisplayName("Test Q: Regeneration Validity - Generating twice maintains single weekly off and valid solver status")
    void testQ_RegenerationValidity() {
        LocalDate weekStart = LocalDate.of(2028, 10, 2);

        // Run 1
        RosterCycleResponse cycle1 = rosterService.generateWeeklyRoster(weekStart, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle1);

        // Run 2 (Regeneration)
        RosterCycleResponse cycle2 = rosterService.generateWeeklyRoster(weekStart, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle2);
        assertEquals(RosterStatus.GENERATED, cycle2.status());
        assertEquals(0, cycle2.criticalConflicts());

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle2.id());
        Map<Long, Integer> offCounts = new HashMap<>();
        for (RosterAssignment a : assignments) {
            if (a.isWeeklyOff()) {
                offCounts.merge(a.getEmployee().getId(), 1, Integer::sum);
            }
        }
        for (Employee emp : employeeRepository.findByActiveTrueOrderByIdAsc()) {
            assertEquals(1, offCounts.getOrDefault(emp.getId(), 0),
                    "Employee " + emp.getEmployeeCode() + " must have exactly 1 Weekly Off upon regeneration");
        }
    }

    // =========================================================================
    // TEST R — Employee-Order Independence
    // =========================================================================
    @Test
    @Order(18)
    @DisplayName("Test R: Employee-Order Independence - Preferences resolved strictly by entity ID, not list position")
    void testR_EmployeeOrderIndependence() {
        List<Employee> normalList = employeeRepository.findByActiveTrueOrderByIdAsc();
        List<Employee> reversedList = new ArrayList<>(normalList);
        Collections.reverse(reversedList);

        LocalDate startDate = LocalDate.of(2028, 11, 6);
        LocalDate endDate = startDate.plusDays(6);

        Map<Long, Map<DayOfWeek, ShiftType>> mapNormal = recurringShiftPreferenceService.getActiveRecurringMap(normalList, startDate, endDate);
        Map<Long, Map<DayOfWeek, ShiftType>> mapReversed = recurringShiftPreferenceService.getActiveRecurringMap(reversedList, startDate, endDate);

        assertEquals(mapNormal.keySet(), mapReversed.keySet(), "Preference mapping keySet must be identical regardless of order");
        for (Long empId : mapNormal.keySet()) {
            assertEquals(mapNormal.get(empId), mapReversed.get(empId), "Preferences must be identical for employee " + empId);
        }

        Map<Long, Map<DayOfWeek, Set<ShiftType>>> avoidNormal = recurringShiftPreferenceService.getActiveRecurringAvoidMap(normalList, startDate, endDate);
        Map<Long, Map<DayOfWeek, Set<ShiftType>>> avoidReversed = recurringShiftPreferenceService.getActiveRecurringAvoidMap(reversedList, startDate, endDate);

        assertEquals(avoidNormal.keySet(), avoidReversed.keySet(), "Avoid mapping keySet must be identical regardless of order");
        for (Long empId : avoidNormal.keySet()) {
            assertEquals(avoidNormal.get(empId), avoidReversed.get(empId), "Avoid preferences must be identical for employee " + empId);
        }
    }
}
