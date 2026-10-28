package com.weeklyroster.service;

import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.dto.response.WeeklyEmployeeWorkSummary;
import com.weeklyroster.dto.response.WeeklyWorkSummaryResponse;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "wrms.dev.credential-mirror.enabled=false",
        "spring.datasource.url=jdbc:sqlite:target/test-wrms-b116.db?busy_timeout=30000&journal_mode=WAL"
})
public class Batch116HardeningAndPreferenceTest {

    static {
        try {
            System.setProperty("spring.profiles.active", "test");
            System.setProperty("spring.datasource.url", "jdbc:sqlite:target/test-wrms-b116.db?busy_timeout=30000&journal_mode=WAL");
            File targetDir = new File("target");
            if (!targetDir.exists()) targetDir.mkdirs();
            File realDb = new File("data/wrms.db");
            File testDb = new File("target/test-wrms-b116.db");
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
    private RosterAssignmentRepository rosterAssignmentRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private RecurringShiftPreferenceRepository recurringShiftPreferenceRepository;

    @Autowired
    private DateShiftPreferenceRepository dateShiftPreferenceRepository;

    @Autowired
    private HolidayRepository holidayRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired
    private WorkDayReportService workDayReportService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "admin",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );
    }

    @Test
    @DisplayName("Verification: Zero Employee-Name Based Scheduling Logic in src/main/java")
    void testZeroEmployeeNameBasedSchedulingLogic() throws IOException {
        Path mainJavaDir = Path.of("src/main/java");
        assertTrue(Files.exists(mainJavaDir), "src/main/java directory must exist");

        Pattern forbiddenMethodPattern = Pattern.compile("\\b(isRajat|isPrachi|isSapna|isDivyansh|isShriram|isSambhav|isTushar|isJeevan|matchesName)\\s*\\(");

        List<String> violations = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(mainJavaDir)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            List<String> lines = Files.readAllLines(p);
                            for (int i = 0; i < lines.size(); i++) {
                                String line = lines.get(i);
                                String trimmed = line.trim();
                                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                                    continue;
                                }
                                if (forbiddenMethodPattern.matcher(line).find()) {
                                    violations.add(p.getFileName() + ":" + (i + 1) + " -> " + trimmed);
                                }
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }

        assertTrue(violations.isEmpty(), "Found forbidden employee-name helper methods in src/main/java: " + violations);
    }

    @Test
    @Transactional
    @DisplayName("Requirement 8: Date-Specific Shift Preference Controls Automatic Roster Generation")
    void testDateSpecificPreferenceControlsSolver() {
        Employee sapna = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> "EMP004".equalsIgnoreCase(e.getEmployeeCode()) || (e.getFirstName() != null && e.getFirstName().toLowerCase().contains("sapna")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Sapna Pandey not found in active employees"));

        LocalDate weekStart = LocalDate.of(2026, 11, 9);
        LocalDate saturdayDate = LocalDate.of(2026, 11, 14);

        // Clear existing date preferences for this date
        dateShiftPreferenceRepository.findByPreferenceDateBetween(weekStart, weekStart.plusDays(6))
                .forEach(dateShiftPreferenceRepository::delete);

        // Add explicit Date-Specific preference: 14-Nov-2026 -> MORNING
        DateShiftPreference datePref = new DateShiftPreference();
        datePref.setEmployee(sapna);
        datePref.setPreferenceDate(saturdayDate);
        datePref.setShiftType(ShiftType.MORNING);
        datePref.setReason("Batch 116 Date-Specific Test");
        datePref.setCreatedBy("admin");
        dateShiftPreferenceRepository.save(datePref);

        // Generate roster
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(weekStart, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle, "Roster cycle must be generated");

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());

        // Verify Sapna's assignment on Saturday 14-Nov-2026 is strictly MORNING
        RosterAssignment sapnaSat = assignments.stream()
                .filter(a -> a.getEmployee().getId().equals(sapna.getId()) && a.getRosterDate().equals(saturdayDate))
                .findFirst()
                .orElse(null);

        assertNotNull(sapnaSat, "Sapna must have an assignment on 14-Nov-2026");
        assertFalse(sapnaSat.isWeeklyOff(), "Sapna must not have Weekly Off on 14-Nov-2026 because of target MORNING shift preference");
        assertNotNull(sapnaSat.getShift(), "Shift must be assigned");
        assertEquals(ShiftType.MORNING, sapnaSat.getShift().getShiftType(),
                "Sapna Pandey MUST be assigned MORNING on 14-Nov-2026 as per Date-Specific Preference");

        // Verify Batch 115 Weekly-Off invariant is preserved (every employee has exactly 1 weekly off in 6-day mode)
        Map<Long, Long> offsPerEmp = new HashMap<>();
        Map<Long, Long> worksPerEmp = new HashMap<>();
        for (RosterAssignment a : assignments) {
            if (a.isWeeklyOff()) offsPerEmp.merge(a.getEmployee().getId(), 1L, Long::sum);
            if (!a.isWeeklyOff() && !a.isOnLeave()) worksPerEmp.merge(a.getEmployee().getId(), 1L, Long::sum);
        }

        for (Map.Entry<Long, Long> entry : offsPerEmp.entrySet()) {
            assertEquals(1L, entry.getValue(), "Every employee must have exactly 1 Weekly Off in 6-day mode");
        }
        for (Map.Entry<Long, Long> entry : worksPerEmp.entrySet()) {
            assertEquals(6L, entry.getValue(), "Every employee must have exactly 6 working days in 6-day mode");
        }
    }

    @Test
    @Transactional
    @DisplayName("Requirement 9 & 10: Persistent Recurring Weekly Preference Controls Future Rosters")
    void testRecurringWeeklyPreferenceEnforcementAndUpdates() {
        Employee sapna = employeeRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(e -> "EMP004".equalsIgnoreCase(e.getEmployeeCode()) || (e.getFirstName() != null && e.getFirstName().toLowerCase().contains("sapna")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Sapna Pandey not found"));

        // Deactivate old recurring preferences for Sapna
        recurringShiftPreferenceRepository.findByEmployeeIdAndActiveTrue(sapna.getId())
                .forEach(rp -> {
                    rp.setActive(false);
                    recurringShiftPreferenceRepository.save(rp);
                });

        // Set persistent recurring preference: SATURDAY = MORNING
        RecurringShiftPreference recurringPref = new RecurringShiftPreference();
        recurringPref.setEmployee(sapna);
        recurringPref.setDayOfWeek(DayOfWeek.SATURDAY);
        recurringPref.setShiftType(ShiftType.MORNING);
        recurringPref.setActive(true);
        recurringPref.setStartDate(LocalDate.of(2026, 11, 1));
        recurringPref.setReason("Admin set Saturday Morning recurring preference");
        recurringPref.setCreatedBy("admin");
        recurringShiftPreferenceRepository.save(recurringPref);

        // Generate and verify across 3 consecutive weeks
        List<LocalDate> testWeeks = List.of(
                LocalDate.of(2026, 11, 9),
                LocalDate.of(2026, 11, 16),
                LocalDate.of(2026, 11, 23)
        );

        for (LocalDate weekStart : testWeeks) {
            RosterCycleResponse cycle = rosterService.generateWeeklyRoster(weekStart, GenerationMode.AUTOMATIC, 6);
            assertNotNull(cycle);

            List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());
            LocalDate sat = weekStart.plusDays(5);

            RosterAssignment satAssign = assignments.stream()
                    .filter(a -> a.getEmployee().getId().equals(sapna.getId()) && a.getRosterDate().equals(sat))
                    .findFirst()
                    .orElse(null);

            assertNotNull(satAssign, "Assignment must exist on Saturday for Sapna");
            assertNotNull(satAssign.getShift(), "Shift must not be null");
            assertEquals(ShiftType.MORNING, satAssign.getShift().getShiftType(),
                    "Sapna must be assigned MORNING on Saturday " + sat + " via persistent recurring preference");

            // Verify exactly 1 off per employee
            Map<Long, Long> offs = new HashMap<>();
            for (RosterAssignment a : assignments) {
                if (a.isWeeklyOff()) offs.merge(a.getEmployee().getId(), 1L, Long::sum);
            }
            offs.values().forEach(cnt -> assertEquals(1L, cnt, "Batch 115 Weekly Off invariant must hold for week " + weekStart));
        }

        // Now Admin updates recurring preference: SATURDAY = GENERAL
        recurringPref.setShiftType(ShiftType.GENERAL);
        recurringShiftPreferenceRepository.save(recurringPref);

        LocalDate week4Start = LocalDate.of(2026, 11, 30);
        RosterCycleResponse cycle4 = rosterService.generateWeeklyRoster(week4Start, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle4);

        List<RosterAssignment> a4 = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle4.id());
        LocalDate sat4 = week4Start.plusDays(5);

        RosterAssignment satAssign4 = a4.stream()
                .filter(a -> a.getEmployee().getId().equals(sapna.getId()) && a.getRosterDate().equals(sat4))
                .findFirst()
                .orElse(null);

        assertNotNull(satAssign4);
        assertNotNull(satAssign4.getShift());
        assertEquals(ShiftType.GENERAL, satAssign4.getShift().getShiftType(),
                "Sapna must now be assigned GENERAL on Saturday after recurring preference was updated");
    }

    @Test
    @Transactional
    @DisplayName("Requirement 21 & 22: Authoritative Statistics Accounting Across Week, Month, and Overall")
    void testAuthoritativeStatisticsService() {
        LocalDate weekStart = LocalDate.of(2026, 11, 9);
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(weekStart, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle);

        // 1. Weekly Work Summary
        WeeklyWorkSummaryResponse weeklySummary = workDayReportService.generateWeeklyWorkSummary(weekStart, null);
        assertNotNull(weeklySummary);
        assertEquals(7, weeklySummary.totalPeriodDays());

        for (WeeklyEmployeeWorkSummary empSummary : weeklySummary.employeeSummaries()) {
            int accountedDays = empSummary.workedDays() + empSummary.leaveDays() + empSummary.holidayDays()
                    + empSummary.weeklyOffDays() + empSummary.absentDays();
            assertEquals(7, accountedDays, "Every employee's total accounted days must equal 7 for employee: " + empSummary.employeeName());

            int shiftTotal = empSummary.morningCount() + empSummary.generalCount() + empSummary.eveningCount() + empSummary.nightCount();
            assertEquals(empSummary.workedDays(), shiftTotal, "Shift breakdown must exactly equal worked days for: " + empSummary.employeeName());
        }

        // 2. Monthly Work Summary (November 2026 has 30 days)
        WeeklyWorkSummaryResponse monthlySummary = workDayReportService.generateMonthlyWorkSummary(2026, 11, null);
        assertNotNull(monthlySummary);
        assertEquals(30, monthlySummary.totalPeriodDays());

        for (WeeklyEmployeeWorkSummary empSummary : monthlySummary.employeeSummaries()) {
            int accountedDays = empSummary.workedDays() + empSummary.leaveDays() + empSummary.holidayDays()
                    + empSummary.weeklyOffDays() + empSummary.absentDays();
            // Account for any ineligible days
            long ineligibleDays = empSummary.dailyRecords().stream().filter(r -> "INELIGIBLE".equalsIgnoreCase(r.status())).count();
            assertEquals(30, accountedDays + ineligibleDays, "Total days must equal 30 for employee: " + empSummary.employeeName());
        }

        // 3. Overall Work Summary
        LocalDate overallStart = LocalDate.of(2026, 11, 2);
        LocalDate overallEnd = LocalDate.of(2026, 11, 15);
        WeeklyWorkSummaryResponse overallSummary = workDayReportService.generateOverallWorkSummary(overallStart, overallEnd, null);
        assertNotNull(overallSummary);
        assertEquals(14, overallSummary.totalPeriodDays());
    }

    @Test
    @Transactional
    @DisplayName("Requirement 20: Live Leave & Holiday Update Rebalance Without Violating Weekly Off Invariants")
    void testLiveLeaveAndHolidayUpdateRebalance() {
        LocalDate weekStart = LocalDate.of(2026, 11, 9);
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(weekStart, GenerationMode.AUTOMATIC, 6);
        assertNotNull(cycle);

        // Pick an active employee working a shift mid-week (e.g. Wednesday 11-Nov-2026)
        LocalDate wedDate = weekStart.plusDays(2);
        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());

        RosterAssignment workingOnWed = assignments.stream()
                .filter(a -> a.getRosterDate().equals(wedDate) && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No employee working on Wednesday"));

        Employee emp = workingOnWed.getEmployee();

        // Simulate approved leave for that day in repository
        com.weeklyroster.entity.LeaveRequest leaveReq = new com.weeklyroster.entity.LeaveRequest();
        leaveReq.setEmployee(emp);
        leaveReq.setStartDate(wedDate);
        leaveReq.setEndDate(wedDate);
        leaveReq.setStatus(com.weeklyroster.entity.LeaveStatus.APPROVED);
        leaveReq.setRequestedAt(java.time.LocalDateTime.now());
        leaveReq.setReason("Batch 116 Mid-Week Approved Leave");
        leaveRequestRepository.save(leaveReq);

        // Rebalance
        rosterService.reoptimizeCycle(cycle.id(), "Rebalance after mid-week approved leave");

        // Verify that after rebalance:
        List<RosterAssignment> updatedAssignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.id());

        // 1. The leave day remains ON LEAVE
        RosterAssignment leaveAssign = updatedAssignments.stream()
                .filter(a -> a.getEmployee().getId().equals(emp.getId()) && a.getRosterDate().equals(wedDate))
                .findFirst().orElseThrow();
        assertTrue(leaveAssign.isOnLeave(), "Leave record must be preserved");

        // 2. Night -> Next Day Off rule is still satisfied everywhere
        for (RosterAssignment a : updatedAssignments) {
            if (!a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT) {
                if (!a.getRosterDate().equals(cycle.endDate())) {
                    LocalDate nextDay = a.getRosterDate().plusDays(1);
                    RosterAssignment nextA = updatedAssignments.stream()
                            .filter(x -> x.getEmployee().getId().equals(a.getEmployee().getId()) && x.getRosterDate().equals(nextDay))
                            .findFirst().orElse(null);
                    assertNotNull(nextA);
                    assertTrue(nextA.isWeeklyOff(), "Night(D) must lead to WeeklyOff(D+1) even after rebalance for " + a.getEmployee().getFirstName());
                }
            }
        }
    }
}
