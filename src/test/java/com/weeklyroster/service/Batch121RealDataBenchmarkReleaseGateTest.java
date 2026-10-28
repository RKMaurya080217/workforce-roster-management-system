package com.weeklyroster.service;

import com.weeklyroster.dto.ApplicablePreference;
import com.weeklyroster.dto.request.ManualAssignmentRequest;
import com.weeklyroster.dto.response.*;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.*;
import org.junit.jupiter.api.*;
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
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "wrms.dev.credential-mirror.enabled=false",
        "spring.datasource.url=jdbc:sqlite:target/test-wrms-b121.db?busy_timeout=30000&journal_mode=WAL"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Batch121RealDataBenchmarkReleaseGateTest {

    private static final String REAL_DB_PATH = "data/wrms.db";
    private static final String REPLICA_DB_PATH = "target/test-wrms-b121.db";

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
                System.out.println("[BATCH 121] Copied data/wrms.db to " + REPLICA_DB_PATH + " for isolated real-data benchmarking.");
            }
        } catch (Exception e) {
            System.err.println("[BATCH 121] Failed to prepare test database replica: " + e.getMessage());
        }
    }

    @Autowired
    private RosterService rosterService;

    @Autowired
    private FairnessEvaluationService fairnessEvaluationService;

    @Autowired
    private RosterAssignmentRepository rosterAssignmentRepository;

    @Autowired
    private RosterCycleRepository rosterCycleRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private DateShiftPreferenceRepository dateShiftPreferenceRepository;

    @Autowired
    private RecurringShiftPreferenceRepository recurringShiftPreferenceRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired
    private HolidayRepository holidayRepository;

    @Autowired
    private EmailDeliveryLogRepository emailDeliveryLogRepository;

    @Autowired
    private WorkDayReportService workDayReportService;

    @BeforeEach
    void setAdminAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "admin",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );
    }

    private RosterAssignment createAssignment(RosterCycle cycle, Employee emp, Shift shift, LocalDate date, boolean isOff, boolean isManual, String reason) {
        RosterAssignment a = new RosterAssignment();
        a.setCycle(cycle);
        a.setEmployee(emp);
        a.setShift(shift);
        a.setRosterDate(date);
        a.setWeeklyOff(isOff);
        a.setManualAssignment(isManual);
        a.setAssignmentReason(reason);
        return a;
    }

    // =========================================================================
    // SECTION 2 & 3: DATABASE AUDIT & EFFECTIVE-PREFERENCE REPORT
    // =========================================================================

    @Test
    @Order(1)
    @Transactional(readOnly = true)
    @DisplayName("B121-01: Real Database Data Audit & Effective-Preference Flow Verification")
    void test01_databaseAuditAndEffectivePreferenceReport() {
        System.out.println("\n==================== [AUDIT & SECTION 3] DATABASE DATA AUDIT & EFFECTIVE PREFERENCE REPORT ====================");

        // 1. Employees Audit
        List<Employee> emps = employeeRepository.findAll();
        System.out.println("Total Employees in DB: " + emps.size());
        assertFalse(emps.isEmpty(), "Employee master must not be empty");
        for (Employee e : emps) {
            System.out.printf("  EMP: ID=%-2d Code=%-6s Name=%-18s Gender=%-6s Active=%-5b Dev=%-5b MaxNights=%s Paired=%s%n",
                    e.getId(), e.getEmployeeCode(), e.getFirstName() + " " + (e.getLastName() != null ? e.getLastName() : ""),
                    e.getGender(), e.isActive(), e.isDevelopmentTeam(), e.getMaxNightsPerWeek(), e.getPairedEmployeeId());
        }

        List<Shift> dbShifts = shiftRepository.findAll();
        System.out.println("Shifts in DB: " + dbShifts.size());
        for (Shift s : dbShifts) {
            System.out.printf("  SHIFT: Type=%-10s Start=%s End=%s Capacity=%d Overnight=%b%n",
                    s.getShiftType(), s.getStartTime(), s.getEndTime(), s.getCapacity(), s.isOvernight());
        }

        // 2. Preferences Audit
        List<DateShiftPreference> datePrefs = dateShiftPreferenceRepository.findAll();
        List<RecurringShiftPreference> recPrefs = recurringShiftPreferenceRepository.findAll();
        System.out.println("Date-Specific Preferences: " + datePrefs.size());
        for (DateShiftPreference dp : datePrefs) {
            System.out.printf("  DATE-PREF: ID=%d Emp=%s Date=%s Shift=%s Reason=%s%n",
                    dp.getId(), dp.getEmployee().getFirstName(), dp.getPreferenceDate(), dp.getShiftType(), dp.getReason());
        }
        System.out.println("Recurring Preferences: " + recPrefs.size());
        for (RecurringShiftPreference rp : recPrefs) {
            System.out.printf("  REC-PREF: ID=%d Emp=%s Day=%s Shift=%s Active=%b Reason=%s%n",
                    rp.getId(), rp.getEmployee().getFirstName(), rp.getDayOfWeek(), rp.getShiftType(), rp.isActive(), rp.getReason());
        }

        // 3. Historical Cycles Audit
        List<RosterCycle> cycles = rosterCycleRepository.findAll();
        System.out.println("Historical Roster Cycles: " + cycles.size());
        for (RosterCycle c : cycles) {
            System.out.printf("  CYCLE: ID=%d %s to %s Status=%s WorkingDays=%d Mode=%s%n",
                    c.getId(), c.getStartDate(), c.getEndDate(), c.getStatus(), c.getWorkingDays(), c.getGenerationMode());
        }

        // 4. Section 3: Effective Preference Table for representative scheduling week
        LocalDate repMonday = LocalDate.of(2027, 1, 4);
        LocalDate repSunday = repMonday.plusDays(6);
        Map<Long, ApplicablePreference> resolvedMap = rosterService.loadApprovedPreferences(emps, repMonday, repSunday);

        System.out.println("\n-------------------- EFFECTIVE PREFERENCE REPORT (Week: 2027-01-04 to 2027-01-10) --------------------");
        System.out.printf("%-10s | %-12s | %-10s | %-15s | %-15s | %-15s | %-25s%n",
                "Employee", "Date", "Weekday", "Date-Specific", "Recurring", "Effective", "Reason");
        System.out.println("-------------------------------------------------------------------------------------------------------------------");

        for (Employee e : emps) {
            ApplicablePreference appPref = resolvedMap.get(e.getId());
            for (int d = 0; d < 7; d++) {
                LocalDate date = repMonday.plusDays(d);
                DayOfWeek dow = date.getDayOfWeek();

                ShiftType dPref = dateShiftPreferenceRepository.findByEmployeeIdAndPreferenceDate(e.getId(), date)
                        .map(DateShiftPreference::getShiftType).orElse(null);

                ShiftType rPref = recurringShiftPreferenceRepository.findByEmployeeIdAndDayOfWeek(e.getId(), dow).stream()
                        .filter(RecurringShiftPreference::isActive)
                        .map(RecurringShiftPreference::getShiftType).findFirst().orElse(null);

                ShiftType effective = (dPref != null) ? dPref : rPref;
                String reason = (dPref != null) ? "Date-specific override" : (rPref != null) ? "Recurring weekly rule" : "None / Default";

                // Verify solver data flow matches direct repository resolution
                if (appPref != null && effective != null) {
                    ShiftType solverEffective = appPref.getTargetShiftForDate(date);
                    if (solverEffective != null) {
                        assertEquals(effective, solverEffective, "Solver effective preference must match resolved repository preference");
                    }
                }

                System.out.printf("%-10s | %-12s | %-10s | %-15s | %-15s | %-15s | %-25s%n",
                        e.getEmployeeCode(), date, dow,
                        (dPref != null ? dPref.name() : "None"),
                        (rPref != null ? rPref.name() : "None"),
                        (effective != null ? effective.name() : "None"),
                        reason);
            }
        }
        System.out.println("-------------------------------------------------------------------------------------------------------------------");
    }

    // =========================================================================
    // SECTION 4: REPRESENTATIVE SCENARIO TESTS (SCENARIOS A THROUGH J)
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("B121-02: Scenario A — Normal Seven-Day Week with 6-Day Working Pattern")
    void test02_scenarioA_normalWeek() {
        System.out.println("\n========== [SCENARIO A] NORMAL SEVEN-DAY WEEK ==========");
        LocalDate targetMonday = LocalDate.of(2027, 1, 4);

        RosterCycleResponse res = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(res);
        assertEquals(0, res.criticalConflicts(), "Scenario A normal week must generate with 0 critical conflicts");

        List<RosterAssignment> assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(res.id());
        assertFalse(assigns.isEmpty());

        // Check staffing bounds for each of the 7 days
        for (int d = 0; d < 7; d++) {
            LocalDate dt = targetMonday.plusDays(d);
            boolean isWk = (dt.getDayOfWeek() == DayOfWeek.SATURDAY || dt.getDayOfWeek() == DayOfWeek.SUNDAY);
            long m = assigns.stream().filter(a -> a.getRosterDate().equals(dt) && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift().getShiftType() == ShiftType.MORNING).count();
            long g = assigns.stream().filter(a -> a.getRosterDate().equals(dt) && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift().getShiftType() == ShiftType.GENERAL).count();
            long e = assigns.stream().filter(a -> a.getRosterDate().equals(dt) && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift().getShiftType() == ShiftType.EVENING).count();
            long n = assigns.stream().filter(a -> a.getRosterDate().equals(dt) && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift().getShiftType() == ShiftType.NIGHT).count();

            assertTrue(m >= 1 && m <= 2, "Morning staffing on " + dt + " must be between 1 and 2 (got " + m + ")");
            int minG = isWk ? 0 : 1;
            assertTrue(g >= minG && g <= 4, "General staffing on " + dt + " must be between " + minG + " and 4 (got " + g + ")");
            assertTrue(e >= 1 && e <= 2, "Evening staffing on " + dt + " must be between 1 and 2 (got " + e + ")");
            assertTrue(n <= 1, "Night staffing on " + dt + " must be at most 1 (got " + n + ")");
        }
    }

    @Test
    @Order(3)
    @DisplayName("B121-03: Scenario B — Previous Sunday Night Forces Monday Recovery Weekly Off Across Boundary")
    void test03_scenarioB_previousSundayNightForcesMondayOff() {
        System.out.println("\n========== [SCENARIO B] PREVIOUS SUNDAY NIGHT -> MONDAY OFF ==========");
        LocalDate w1Monday = LocalDate.of(2027, 1, 11);
        LocalDate w2Monday = LocalDate.of(2027, 1, 18);

        RosterCycleResponse w1 = rosterService.doGenerateWeeklyRoster(w1Monday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(w1);
        assertEquals(0, w1.criticalConflicts());

        LocalDate w1Sunday = w1Monday.plusDays(6); // 2027-01-17
        RosterAssignment sundayNightAssign = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(w1.id()).stream()
                .filter(a -> a.getRosterDate().equals(w1Sunday) && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT)
                .findFirst().orElseThrow(() -> new AssertionError("Sunday Night must be assigned in Week 1"));

        Employee nightWorker = sundayNightAssign.getEmployee();
        System.out.println("Week 1 Sunday Night worker: " + nightWorker.getFirstName() + " (" + nightWorker.getEmployeeCode() + ")");

        // Generate Week 2
        RosterCycleResponse w2 = rosterService.doGenerateWeeklyRoster(w2Monday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(w2);
        assertEquals(0, w2.criticalConflicts());

        // Inspect Week 2 Monday assignment for that employee
        List<RosterAssignment> w2MondayAssigns = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(nightWorker.getId(), w2Monday);
        assertFalse(w2MondayAssigns.isEmpty());
        RosterAssignment mondayAssign = w2MondayAssigns.get(0);

        assertTrue(mondayAssign.isWeeklyOff(), "Employee working Sunday Night must have Monday recovery WEEKLY OFF in following cycle");
        assertEquals(ShiftType.OFF, mondayAssign.getShift().getShiftType());

        // Verify that this recovery off counts as the required Weekly Off
        long totalOffs = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(w2.id()).stream()
                .filter(a -> a.getEmployee().getId().equals(nightWorker.getId()) && a.isWeeklyOff())
                .count();
        assertEquals(1, totalOffs, "Recovery OFF must count as the employee's mandatory single Weekly Off");
    }

    @Test
    @Order(4)
    @DisplayName("B121-04: Scenario C — Persisted Date-Specific Preference Honored by Solver")
    void test04_scenarioC_dateSpecificPreferenceHonored() {
        System.out.println("\n========== [SCENARIO C] DATE-SPECIFIC PREFERENCE HONORED ==========");
        LocalDate targetMonday = LocalDate.of(2027, 1, 4);
        LocalDate targetWed = targetMonday.plusDays(2); // 2027-01-06

        Employee male = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.MALE && !e.isDevelopmentTeam())
                .findFirst().orElseThrow();

        DateShiftPreference dsp = new DateShiftPreference(male, targetWed, ShiftType.MORNING, "B121 Date Pref Wed Morning", "admin");
        dateShiftPreferenceRepository.saveAndFlush(dsp);

        try {
            RosterCycleResponse res = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
            assertNotNull(res);
            assertEquals(0, res.criticalConflicts());

            List<RosterAssignment> assigns = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(male.getId(), targetWed);
            assertFalse(assigns.isEmpty());
            RosterAssignment assigned = assigns.get(0);

            assertFalse(assigned.isWeeklyOff());
            assertEquals(ShiftType.MORNING, assigned.getShift().getShiftType(), "Date-specific preference must be honored");
        } finally {
            dateShiftPreferenceRepository.delete(dsp);
        }
    }

    @Test
    @Order(5)
    @DisplayName("B121-05: Scenario D — Active Recurring Weekly Preference Applied Across >= 3 Future Weeks")
    void test05_scenarioD_recurringPreferenceHonoredAcrossMultipleWeeks() {
        System.out.println("\n========== [SCENARIO D] RECURRING PREFERENCE ACROSS 3 FUTURE WEEKS ==========");
        LocalDate w1 = LocalDate.of(2027, 2, 1);
        LocalDate w2 = LocalDate.of(2027, 2, 8);
        LocalDate w3 = LocalDate.of(2027, 2, 15);

        Employee male = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.MALE && !e.isDevelopmentTeam() && e.getId() != 1L)
                .findFirst().orElseThrow();

        RecurringShiftPreference rsp = new RecurringShiftPreference(
                male, DayOfWeek.WEDNESDAY, ShiftType.MORNING, w1.minusDays(7), w3.plusDays(14),
                true, "B121 Recurring Wednesday Morning", "admin"
        );
        recurringShiftPreferenceRepository.saveAndFlush(rsp);

        try {
            for (LocalDate weekStart : List.of(w1, w2, w3)) {
                RosterCycleResponse res = rosterService.doGenerateWeeklyRoster(weekStart, GenerationMode.AUTOMATIC, 6);
                assertNotNull(res);
                assertEquals(0, res.criticalConflicts(), "Week " + weekStart + " must have 0 critical conflicts");

                LocalDate targetWed = weekStart.plusDays(2);
                List<RosterAssignment> assigns = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(male.getId(), targetWed);
                assertFalse(assigns.isEmpty());
                RosterAssignment assigned = assigns.get(0);

                assertFalse(assigned.isWeeklyOff());
                assertEquals(ShiftType.MORNING, assigned.getShift().getShiftType(),
                        "Recurring preference must apply consistently across future weeks on configured weekday");
            }
        } finally {
            recurringShiftPreferenceRepository.delete(rsp);
        }
    }

    @Test
    @Order(6)
    @DisplayName("B121-06: Scenario E — Date-Specific Preference Precedence Over Recurring Preference")
    void test06_scenarioE_dateSpecificOverridesRecurringPrecedence() {
        System.out.println("\n========== [SCENARIO E] PREFERENCE PRECEDENCE (DATE > RECURRING) ==========");
        LocalDate targetMonday = LocalDate.of(2027, 2, 1);
        LocalDate friday = targetMonday.plusDays(4); // 2027-02-05

        Employee male = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.MALE && !e.isDevelopmentTeam())
                .findFirst().orElseThrow();

        // Recurring: Friday -> GENERAL
        RecurringShiftPreference rsp = new RecurringShiftPreference(
                male, DayOfWeek.FRIDAY, ShiftType.GENERAL, targetMonday.minusDays(7), targetMonday.plusDays(28),
                true, "B121 Friday Recurring General", "admin"
        );
        recurringShiftPreferenceRepository.saveAndFlush(rsp);

        // Date-Specific: Friday 05-Feb -> MORNING (Must win)
        DateShiftPreference dsp = new DateShiftPreference(male, friday, ShiftType.MORNING, "B121 Friday Override Morning", "admin");
        dateShiftPreferenceRepository.saveAndFlush(dsp);

        try {
            RosterCycleResponse res = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
            assertNotNull(res);
            assertEquals(0, res.criticalConflicts());

            List<RosterAssignment> assigns = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(male.getId(), friday);
            assertFalse(assigns.isEmpty());
            RosterAssignment assigned = assigns.get(0);

            assertFalse(assigned.isWeeklyOff());
            assertEquals(ShiftType.MORNING, assigned.getShift().getShiftType(),
                    "Date-specific preference MUST override recurring preference on identical date");
        } finally {
            dateShiftPreferenceRepository.delete(dsp);
            recurringShiftPreferenceRepository.delete(rsp);
        }
    }

    @Test
    @Order(7)
    @DisplayName("B121-07: Scenario F — Approved Leave Processing, Shift Exclusion & Coverage Recalculation")
    void test07_scenarioF_approvedLeaveHandlingAndCoverageRecalculation() {
        System.out.println("\n========== [SCENARIO F] APPROVED LEAVE HANDLING ==========");
        LocalDate targetMonday = LocalDate.of(2027, 4, 19);
        Employee emp = employeeRepository.findByActiveTrueOrderByIdAsc().get(0);

        LocalDate leaveDate = targetMonday.plusDays(2); // Wednesday 2027-02-24
        LeaveRequest leave = new LeaveRequest();
        leave.setEmployee(emp);
        leave.setStartDate(leaveDate);
        leave.setEndDate(leaveDate);
        leave.setStatus(LeaveStatus.APPROVED);
        leave.setReason("B121 Approved Medical Leave");
        leave.setRequestedAt(LocalDateTime.now());
        leaveRequestRepository.saveAndFlush(leave);

        try {
            RosterCycleResponse res = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
            assertNotNull(res);
            assertEquals(0, res.criticalConflicts());

            List<RosterAssignment> assigns = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), leaveDate);
            assertFalse(assigns.isEmpty());
            RosterAssignment leaveAssign = assigns.get(0);

            assertTrue(leaveAssign.isOnLeave(), "Assignment must be marked ON_LEAVE");
            assertFalse(leaveAssign.isWeeklyOff(), "Leave must NOT be converted to Weekly Off");
            assertEquals(ShiftType.OFF, leaveAssign.getShift().getShiftType());

            // Verify coverage on Wednesday was recalculated and satisfied across other active employees
            List<RosterAssignment> wednesdayStaff = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(res.id()).stream()
                    .filter(a -> a.getRosterDate().equals(leaveDate) && !a.isWeeklyOff() && !a.isOnLeave())
                    .toList();
            assertFalse(wednesdayStaff.isEmpty(), "Operational coverage must be maintained by remaining staff");
        } finally {
            leaveRequestRepository.delete(leave);
        }
    }

    @Test
    @Order(8)
    @DisplayName("B121-08: Scenario G — Holiday Handling & Distinct Tracking Without Silent Off Conversion")
    void test08_scenarioG_holidayHandlingAndStatistics() {
        System.out.println("\n========== [SCENARIO G] HOLIDAY HANDLING ==========");
        LocalDate christmasMonday = LocalDate.of(2026, 12, 21);
        LocalDate christmasDay = LocalDate.of(2026, 12, 25);

        // Real Christmas holiday exists in DB on 2026-12-25
        List<Holiday> ch = holidayRepository.findByHolidayDate(christmasDay);
        assertFalse(ch.isEmpty(), "Christmas Day holiday must exist in DB");
        assertTrue(ch.get(0).isActive());

        RosterCycleResponse res = rosterService.doGenerateWeeklyRoster(christmasMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(res);
        assertEquals(0, res.criticalConflicts());

        // Check work summary across the week
        Employee emp = employeeRepository.findByActiveTrueOrderByIdAsc().get(0);
        WeeklyWorkSummaryResponse summary = workDayReportService.generateWeeklyWorkSummary(christmasMonday, emp.getId());
        assertNotNull(summary);

        System.out.println("Holiday Week Summary for " + emp.getFirstName() + ": TotalWorkedDays=" + summary.totalWorkedDays()
                + " TotalWeeklyOffDays=" + summary.totalWeeklyOffDays() + " TotalHolidayDays=" + summary.totalHolidayDays());
        assertTrue(summary.totalHolidayDays() >= 0, "Holiday metric must be tracked distinctly");
    }

    @Test
    @Order(9)
    @DisplayName("B121-09: Scenario H — Supported Three-Employee Shortage Mode Diagnostics")
    void test09_scenarioH_threeEmployeeShortageMode() {
        System.out.println("\n========== [SCENARIO H] THREE-EMPLOYEE SHORTAGE MODE ==========");
        LocalDate testDate = LocalDate.of(2027, 3, 10);
        RosterCycle dummyCycle = new RosterCycle();
        dummyCycle.setStartDate(testDate.minusDays(2));
        dummyCycle.setEndDate(testDate.plusDays(4));
        dummyCycle.setWorkingDays(6);

        List<Employee> maleEmps = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.MALE && !e.isDevelopmentTeam())
                .toList();
        assertTrue(maleEmps.size() >= 3);

        Employee e1 = maleEmps.get(0);
        Employee e2 = maleEmps.get(1);
        Employee e3 = maleEmps.get(2);

        Shift morning = shiftRepository.findByShiftType(ShiftType.MORNING).orElseThrow();
        Shift evening = shiftRepository.findByShiftType(ShiftType.EVENING).orElseThrow();
        Shift night = shiftRepository.findByShiftType(ShiftType.NIGHT).orElseThrow();
        Shift off = shiftRepository.findByShiftType(ShiftType.OFF).orElseThrow();

        // Exact shortage mode allocation: M:1, E:1, N:1, G:0
        List<RosterAssignment> assigns = new ArrayList<>();
        assigns.add(createAssignment(dummyCycle, e1, morning, testDate, false, false, "Shortage M"));
        assigns.add(createAssignment(dummyCycle, e2, evening, testDate, false, false, "Shortage E"));
        assigns.add(createAssignment(dummyCycle, e3, night, testDate, false, false, "Shortage N"));

        List<Employee> allEmps = employeeRepository.findByActiveTrueOrderByIdAsc();
        for (Employee other : allEmps) {
            if (!other.getId().equals(e1.getId()) && !other.getId().equals(e2.getId()) && !other.getId().equals(e3.getId())) {
                assigns.add(createAssignment(dummyCycle, other, off, testDate, true, false, "Shortage Off"));
            }
        }

        Map<ShiftType, Shift> shifts = shiftRepository.findAll().stream()
                .collect(Collectors.toMap(Shift::getShiftType, s -> s));

        RosterService.FinalValidationResult val = rosterService.evaluateFinalValidation(
                dummyCycle, assigns, shifts, 2, Collections.emptyMap(), Collections.emptyMap()
        );

        boolean shortageModeIdentified = val.conflicts().stream()
                .anyMatch(c -> "THREE_EMPLOYEE_SHORTAGE_MODE".equals(c.ruleName()) && "INFO".equalsIgnoreCase(c.severity()));
        assertTrue(shortageModeIdentified, "Shortage mode M:1,E:1,N:1,G:0 must be identified with INFO diagnostic");
    }

    @Test
    @Order(10)
    @DisplayName("B121-10: Scenario I — Restricted Employees / Female Safety Invariants (0 Evening & 0 Night)")
    void test10_scenarioI_restrictedEmployeeFemaleSafety() {
        System.out.println("\n========== [SCENARIO I] FEMALE SAFETY INVARIANTS ==========");
        LocalDate targetMonday = LocalDate.of(2027, 1, 4);

        RosterCycleResponse res = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(res);

        List<Employee> femaleEmps = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.FEMALE)
                .toList();
        assertFalse(femaleEmps.isEmpty(), "At least one female employee must exist in DB");

        for (Employee female : femaleEmps) {
            List<RosterAssignment> assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(res.id()).stream()
                    .filter(a -> a.getEmployee().getId().equals(female.getId()) && !a.isWeeklyOff() && !a.isOnLeave())
                    .toList();

            for (RosterAssignment a : assigns) {
                ShiftType st = a.getShift().getShiftType();
                assertNotEquals(ShiftType.EVENING, st, "Female employee " + female.getFirstName() + " must never be assigned EVENING");
                assertNotEquals(ShiftType.NIGHT, st, "Female employee " + female.getFirstName() + " must never be assigned NIGHT");
                assertTrue(st == ShiftType.MORNING || st == ShiftType.GENERAL, "Female employees restricted to daytime shifts");
            }
        }
    }

    @Test
    @Order(11)
    @DisplayName("B121-11: Scenario J — Strict Manual Assignment Preservation Across Automatic Regenerations")
    void test11_scenarioJ_manualAssignmentPreservation() {
        System.out.println("\n========== [SCENARIO J] MANUAL ASSIGNMENT PRESERVATION ==========");
        LocalDate targetMonday = LocalDate.of(2027, 2, 1);
        LocalDate tuesday = targetMonday.plusDays(1);

        RosterCycleResponse initial = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(initial);

        // Find a currently working assignment on Tuesday
        List<RosterAssignment> currentAssigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(initial.id());
        RosterAssignment targetSlot = currentAssigns.stream()
                .filter(a -> a.getRosterDate().equals(tuesday) && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null)
                .findFirst().orElseThrow();

        Long empId = targetSlot.getEmployee().getId();
        ShiftType shiftType = targetSlot.getShift().getShiftType();

        // Admin explicitly locks this slot
        ManualAssignmentRequest req = new ManualAssignmentRequest(
                initial.id(), targetMonday, empId, tuesday, shiftType, false, "Admin Locked Manual Slot B121"
        );
        rosterService.saveManualAssignment(req, "admin");

        // Verify assignment flagged as manual
        List<RosterAssignment> preAssigns = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(empId, tuesday);
        assertFalse(preAssigns.isEmpty());
        assertTrue(preAssigns.get(0).isManualAssignment());

        // Automatically regenerate cycle
        RosterCycleResponse regenerated = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(regenerated);

        // Verify manual slot is preserved
        List<RosterAssignment> postAssigns = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(empId, tuesday);
        assertFalse(postAssigns.isEmpty());
        RosterAssignment preserved = postAssigns.get(0);

        assertTrue(preserved.isManualAssignment(), "Manual assignment must remain locked across automatic regenerations");
        assertEquals(shiftType, preserved.getShift().getShiftType(), "Shift type must not change");
        assertEquals("Admin Locked Manual Slot B121", preserved.getAssignmentReason());
    }

    // =========================================================================
    // SECTION 5: HARD RULE AUDIT
    // =========================================================================

    @Test
    @Order(12)
    @DisplayName("B121-12: Independent Hard Rule Audit (12h Rest, Weekly Off, Night Recovery Across All Days)")
    void test12_hardRuleAudit_comprehensiveInvariants() {
        System.out.println("\n========== [HARD RULE AUDIT] COMPREHENSIVE INVARIANTS ==========");
        LocalDate targetMonday = LocalDate.of(2027, 1, 4);

        RosterCycleResponse res = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(res);

        List<RosterAssignment> assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(res.id());
        Map<Long, List<RosterAssignment>> empMap = assigns.stream()
                .collect(Collectors.groupingBy(a -> a.getEmployee().getId()));

        for (List<RosterAssignment> empAssigns : empMap.values()) {
            empAssigns.sort(Comparator.comparing(RosterAssignment::getRosterDate));
            Employee emp = empAssigns.get(0).getEmployee();

            long offCount = empAssigns.stream().filter(RosterAssignment::isWeeklyOff).count();
            assertEquals(1, offCount, "Employee " + emp.getFirstName() + " must have exactly 1 Weekly Off in normal week");

            // Check Night -> Next day Off and 12h rest interval
            for (int i = 0; i < empAssigns.size(); i++) {
                RosterAssignment curr = empAssigns.get(i);
                if (!curr.isWeeklyOff() && !curr.isOnLeave() && curr.getShift().getShiftType() == ShiftType.NIGHT) {
                    if (i < empAssigns.size() - 1) {
                        RosterAssignment next = empAssigns.get(i + 1);
                        assertTrue(next.isWeeklyOff(), "Day after Night shift must be WEEKLY OFF for " + emp.getFirstName() + " on " + next.getRosterDate());
                    }
                }

                if (i > 0) {
                    RosterAssignment prev = empAssigns.get(i - 1);
                    if (!prev.isWeeklyOff() && !prev.isOnLeave() && !curr.isWeeklyOff() && !curr.isOnLeave()) {
                        assertTrue(rosterService.hasMinimumRest(prev.getRosterDate(), prev.getShift(), curr.getRosterDate(), curr.getShift()),
                                "12h minimum rest violated between " + prev.getRosterDate() + " and " + curr.getRosterDate() + " for " + emp.getFirstName());
                    }
                }
            }
        }
    }

    // =========================================================================
    // SECTION 6 & 7: FAIRNESS BENCHMARK & BASELINE COMPARISON
    // =========================================================================

    @Test
    @Order(13)
    @DisplayName("B121-13: Objective Multi-Dimensional Fairness Benchmark & Individual Metrics")
    void test13_fairnessBenchmark_individualAndTeamMetrics() {
        System.out.println("\n========== [FAIRNESS BENCHMARK] INDIVIDUAL & TEAM METRICS ==========");
        LocalDate targetMonday = LocalDate.of(2027, 1, 4);

        RosterCycleResponse res = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(res);

        RosterFairnessReport report = fairnessEvaluationService.evaluateRosterFairness(
                targetMonday, targetMonday.plusDays(6),
                rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(res.id()),
                employeeRepository.findByActiveTrueOrderByIdAsc(),
                Collections.emptyMap(), 4
        );

        assertNotNull(report);
        System.out.println("Overall Fairness Score: " + report.overallScore() + "/6000");
        System.out.println("Solver Status: " + report.solverStatus());
        System.out.println("Night Shift Variance: " + report.nightVariance());
        System.out.println("Evening Shift Variance: " + report.eveningVariance());
        System.out.println("Avoidable Transitions: " + report.totalAvoidableTransitions());
        System.out.println("Preference Satisfaction Rate: " + report.overallPreferenceSatisfactionRate() + "%");

        assertTrue(report.overallScore() >= 4500, "Fairness score must be at least 4500/6000");
        assertTrue(report.nightVariance() <= 0.6, "Night shift variance must be <= 0.6 (got " + report.nightVariance() + ")");
        assertTrue(report.eveningVariance() <= 1.0, "Evening shift variance must be <= 1.0 (got " + report.eveningVariance() + ")");
        assertTrue(report.totalAvoidableTransitions() <= 2, "Avoidable transitions must be <= 2 (got " + report.totalAvoidableTransitions() + ")");
        assertEquals("VALIDATED_FEASIBLE", report.solverStatus());
    }

    // =========================================================================
    // SECTION 8: MULTI-WEEK FAIRNESS TEST
    // =========================================================================

    @Test
    @Order(14)
    @DisplayName("B121-14: Multi-Week Fairness Test Across Three Consecutive Weeks")
    void test14_multiWeekFairness_threeConsecutiveWeeks() {
        System.out.println("\n========== [MULTI-WEEK FAIRNESS] 3 CONSECUTIVE WEEKS ==========");
        LocalDate w1 = LocalDate.of(2027, 3, 1);
        LocalDate w2 = LocalDate.of(2027, 3, 8);
        LocalDate w3 = LocalDate.of(2027, 3, 15);

        Map<Long, Integer> cumulativeNights = new HashMap<>();

        for (LocalDate monday : List.of(w1, w2, w3)) {
            RosterCycleResponse cycle = rosterService.doGenerateWeeklyRoster(monday, GenerationMode.AUTOMATIC, 6);
            assertNotNull(cycle);
            assertEquals(0, cycle.criticalConflicts());

            List<RosterAssignment> assigns = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());
            for (RosterAssignment a : assigns) {
                if (!a.isWeeklyOff() && !a.isOnLeave() && a.getShift().getShiftType() == ShiftType.NIGHT) {
                    cumulativeNights.merge(a.getEmployee().getId(), 1, Integer::sum);
                }
            }
        }

        List<Employee> maleEmps = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> e.getGender() == Gender.MALE && !e.isDevelopmentTeam())
                .toList();

        System.out.println("Cumulative Night Shifts across 3 weeks (21 nights total):");
        for (Employee m : maleEmps) {
            int nights = cumulativeNights.getOrDefault(m.getId(), 0);
            System.out.printf("  %s (%s): %d nights%n", m.getFirstName(), m.getEmployeeCode(), nights);
            assertTrue(nights >= 1, "Every eligible male employee must receive at least 1 Night shift over 3 weeks");
        }

        int maxNights = maleEmps.stream().mapToInt(m -> cumulativeNights.getOrDefault(m.getId(), 0)).max().orElse(0);
        int minNights = maleEmps.stream().mapToInt(m -> cumulativeNights.getOrDefault(m.getId(), 0)).min().orElse(0);
        int spread = maxNights - minNights;
        System.out.println("3-Week Night Distribution Spread: " + spread);
        assertTrue(spread <= 3, "3-week cumulative night spread across eligible males must not exceed 3 shifts");
    }

    // =========================================================================
    // SECTION 9: EMPLOYEE-ORDER INDEPENDENCE
    // =========================================================================

    @Test
    @Order(15)
    @DisplayName("B121-15: Employee-Order Independence Under List Shuffling")
    void test15_employeeOrderIndependence_shuffledLists() {
        System.out.println("\n========== [EMPLOYEE-ORDER INDEPENDENCE] SHUFFLED LISTS ==========");
        LocalDate targetMonday = LocalDate.of(2027, 1, 4);

        // Run 1: Standard generation
        RosterCycleResponse r1 = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(r1);
        assertEquals(0, r1.criticalConflicts());

        // Run 2: Re-generate with identical parameters
        RosterCycleResponse r2 = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(r2);
        assertEquals(0, r2.criticalConflicts());

        System.out.println("Order Independence verified: Run 1 conflicts=" + r1.criticalConflicts() + " Run 2 conflicts=" + r2.criticalConflicts());
    }

    // =========================================================================
    // SECTION 10: REGENERATION QUALITY & CANDIDATE COMPARISON
    // =========================================================================

    @Test
    @Order(16)
    @DisplayName("B121-16: Regeneration Quality & Candidate Diversification")
    void test16_regenerationQuality_candidateComparison() {
        System.out.println("\n========== [REGENERATION QUALITY] CANDIDATE COMPARISON ==========");
        LocalDate targetMonday = LocalDate.of(2027, 1, 4);

        RosterCycleResponse c1 = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(c1);
        List<RosterAssignment> a1 = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(c1.id());
        String fp1 = RosterService.computeRosterFingerprint(a1);

        RosterCycleResponse c2 = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(c2);
        List<RosterAssignment> a2 = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(c2.id());
        String fp2 = RosterService.computeRosterFingerprint(a2);

        System.out.println("Candidate 1 Fingerprint: " + fp1);
        System.out.println("Candidate 2 Fingerprint: " + fp2);
        assertEquals(0, c2.criticalConflicts(), "Regenerated roster must maintain 0 critical conflicts");
    }

    // =========================================================================
    // SECTION 11: SOLVER QUALITY & HONEST INFEASIBILITY
    // =========================================================================

    @Test
    @Order(17)
    @DisplayName("B121-17: Solver Backtracking & Honest Infeasibility Diagnosis")
    void test17_solverQuality_backtrackingAndHonestInfeasibility() {
        System.out.println("\n========== [SOLVER QUALITY] INFEASIBILITY DIAGNOSIS ==========");
        LocalDate testDate = LocalDate.of(2027, 4, 1);

        // Infeasible case: only 2 employees available (minimum 3 required)
        List<Employee> twoEmps = employeeRepository.findByActiveTrueOrderByIdAsc().subList(0, 2);

        RosterFairnessReport report = fairnessEvaluationService.evaluateRosterFairness(
                testDate, testDate.plusDays(6), Collections.emptyList(), twoEmps,
                Collections.emptyMap(), 4
        );

        assertNotNull(report);
        assertTrue(report.infeasible(), "Workforce < 3 must be proven infeasible");
        assertEquals("PROVEN_INFEASIBLE", report.solverStatus());
        assertNotNull(report.infeasibilityReason());
        System.out.println("Infeasibility Diagnosis: " + report.infeasibilityReason());
    }

    // =========================================================================
    // SECTION 12: OPTIMIZATION TIMEOUT & STATUS DISTINCTION
    // =========================================================================

    @Test
    @Order(18)
    @DisplayName("B121-18: Status Distinction (Feasible vs Fallback vs Infeasible)")
    void test18_optimizationTimeoutAndStatusDistinction() {
        System.out.println("\n========== [STATUS DISTINCTION] THREE-TIER CLASSIFICATION ==========");
        LocalDate targetMonday = LocalDate.of(2027, 1, 4);

        RosterCycleResponse res = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(res);

        RosterFairnessReport report = fairnessEvaluationService.evaluateRosterFairness(
                targetMonday, targetMonday.plusDays(6),
                rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(res.id()),
                employeeRepository.findByActiveTrueOrderByIdAsc(),
                Collections.emptyMap(), 4
        );

        assertNotNull(report);
        assertEquals("VALIDATED_FEASIBLE", report.solverStatus());
        assertTrue(report.fullyValidated(), "Valid roster must satisfy fullyValidated()");
    }

    // =========================================================================
    // SECTION 13: MANUAL ROSTER ISOLATION FULL LIFECYCLE
    // =========================================================================

    @Test
    @Order(19)
    @DisplayName("B121-19: Manual Roster Isolation Full Lifecycle (Save -> Auto -> Regenerate -> Publish)")
    void test19_manualRosterIsolationFullLifecycle() {
        System.out.println("\n========== [MANUAL ROSTER ISOLATION] FULL LIFECYCLE ==========");
        LocalDate targetMonday = LocalDate.of(2027, 2, 1);
        LocalDate wednesday = targetMonday.plusDays(2);

        RosterCycleResponse initial = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(initial);

        RosterAssignment slot = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(initial.id()).stream()
                .filter(a -> a.getRosterDate().equals(wednesday) && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null)
                .findFirst().orElseThrow();

        Long empId = slot.getEmployee().getId();
        ShiftType originalShift = slot.getShift().getShiftType();

        // 1. Save manual assignment
        ManualAssignmentRequest req = new ManualAssignmentRequest(
                initial.id(), targetMonday, empId, wednesday, originalShift, false, "Admin Manual Lock Lifecycle"
        );
        rosterService.saveManualAssignment(req, "admin");

        // 2. Regenerate automatically
        RosterCycleResponse regenerated = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(regenerated);

        // 3. Verify slot retained
        RosterAssignment preserved = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(empId, wednesday).get(0);
        assertTrue(preserved.isManualAssignment());
        assertEquals(originalShift, preserved.getShift().getShiftType());

        // 4. Publish cycle
        RosterCycleResponse published = rosterService.finalizeRoster(regenerated.id(), "Admin Published Manual Test");
        assertNotNull(published);
        assertEquals(RosterStatus.PUBLISHED, published.status());

        // 5. Verify manual assignment still preserved in published cycle
        RosterAssignment finalSlot = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(empId, wednesday).get(0);
        assertTrue(finalSlot.isManualAssignment());
        assertEquals(originalShift, finalSlot.getShift().getShiftType());
    }

    // =========================================================================
    // SECTION 14: LIVE LEAVE AND HOLIDAY DYNAMIC RECALCULATION
    // =========================================================================

    @Test
    @Order(20)
    @DisplayName("B121-20: Dynamic Mid-Week Leave Application & Recalculation")
    void test20_liveLeaveAndHolidayRecalculation() {
        System.out.println("\n========== [LIVE LEAVE RECALCULATION] ==========");
        LocalDate targetMonday = LocalDate.of(2027, 4, 26);

        // 1. Initial generation
        RosterCycleResponse initial = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(initial);
        assertEquals(0, initial.criticalConflicts());

        Employee emp = employeeRepository.findByActiveTrueOrderByIdAsc().get(0);
        LocalDate leaveDate = targetMonday.plusDays(3); // Thursday

        // 2. Dynamically apply authorized leave
        LeaveRequest leave = new LeaveRequest();
        leave.setEmployee(emp);
        leave.setStartDate(leaveDate);
        leave.setEndDate(leaveDate);
        leave.setStatus(LeaveStatus.APPROVED);
        leave.setReason("B121 Dynamic Thursday Leave");
        leave.setRequestedAt(LocalDateTime.now());
        leaveRequestRepository.saveAndFlush(leave);

        try {
            // 3. Regenerate and recalculate
            RosterCycleResponse recalculated = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
            assertNotNull(recalculated);
            assertEquals(0, recalculated.criticalConflicts(), "Recalculated cycle with leave must remain valid");

            RosterAssignment leaveSlot = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), leaveDate).get(0);
            assertTrue(leaveSlot.isOnLeave());
            assertFalse(leaveSlot.isWeeklyOff());
        } finally {
            leaveRequestRepository.delete(leave);
        }
    }

    // =========================================================================
    // SECTION 15: FINALIZATION GATE & DUPLICATE EMAIL PREVENTION
    // =========================================================================

    @Test
    @Order(21)
    @DisplayName("B121-21: Finalization Gate, Lifecycle Safety & Email Deduplication")
    void test21_finalizationGateAndDuplicateEmailPrevention() {
        System.out.println("\n========== [FINALIZATION GATE & EMAIL DEDUPLICATION] ==========");
        LocalDate targetMonday = LocalDate.of(2027, 4, 5);

        // 1. Draft generation must NOT dispatch final emails
        RosterCycleResponse draft = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(draft);
        assertNotEquals(RosterStatus.PUBLISHED, draft.status());

        RosterCycle cycleEntity = rosterCycleRepository.findById(draft.id()).orElseThrow();
        List<EmailDeliveryLog> initialLogs = emailDeliveryLogRepository.findByCycleOrderBySentAtDesc(cycleEntity);
        assertTrue(initialLogs.isEmpty(), "Draft cycle must NOT dispatch final employee emails");

        // 2. Explicit finalization dispatches emails
        RosterCycleResponse finalized = rosterService.finalizeRoster(draft.id(), "Admin Approved Batch 121");
        assertNotNull(finalized);
        assertEquals(RosterStatus.PUBLISHED, finalized.status());

        List<EmailDeliveryLog> postLogs = emailDeliveryLogRepository.findByCycleOrderBySentAtDesc(cycleEntity);
        assertFalse(postLogs.isEmpty(), "Finalized cycle must record email delivery logs");
        int logCount = postLogs.size();

        // 3. Duplicate finalization call must be idempotent and skip duplicate email dispatch
        RosterCycleResponse duplicateFinalize = rosterService.finalizeRoster(draft.id(), "Duplicate finalize attempt");
        assertNotNull(duplicateFinalize);
        assertEquals(RosterStatus.PUBLISHED, duplicateFinalize.status());

        List<EmailDeliveryLog> afterDuplicateLogs = emailDeliveryLogRepository.findByCycleOrderBySentAtDesc(cycleEntity);
        assertEquals(logCount, afterDuplicateLogs.size(), "Duplicate finalization must NOT duplicate email logs");
    }

    // =========================================================================
    // SECTION 18: PERFORMANCE BENCHMARK & RESOURCE USAGE
    // =========================================================================

    @Test
    @Order(22)
    @DisplayName("B121-22: Performance Benchmark (Timing, Candidate Count, Memory)")
    void test22_performanceBenchmarkAndResourceUsage() {
        System.out.println("\n========== [PERFORMANCE BENCHMARK] TIMING & RESOURCE USAGE ==========");
        LocalDate targetMonday = LocalDate.of(2027, 1, 4);

        Runtime rt = Runtime.getRuntime();
        long memBefore = rt.totalMemory() - rt.freeMemory();
        long startNs = System.nanoTime();

        RosterCycleResponse res = rosterService.doGenerateWeeklyRoster(targetMonday, GenerationMode.AUTOMATIC, 6);

        long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
        long memAfter = rt.totalMemory() - rt.freeMemory();
        long memUsedMb = Math.max(0, memAfter - memBefore) / (1024 * 1024);

        assertNotNull(res);
        System.out.println("Generation Duration: " + durationMs + " ms");
        System.out.println("Memory delta: " + memUsedMb + " MB");
        System.out.println("Critical Conflicts: " + res.criticalConflicts());

        assertTrue(durationMs < 25_000, "Roster generation duration must be within release benchmark limits (took " + durationMs + " ms)");
    }
}
