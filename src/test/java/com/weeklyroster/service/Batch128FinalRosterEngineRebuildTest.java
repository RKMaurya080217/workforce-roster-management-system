package com.weeklyroster.service;

import com.weeklyroster.dto.response.*;
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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "wrms.dev.credential-mirror.enabled=false",
        "spring.datasource.url=jdbc:sqlite:target/test-wrms-b128.db?busy_timeout=30000&journal_mode=WAL"
})
public class Batch128FinalRosterEngineRebuildTest {

    static {
        try {
            System.setProperty("spring.profiles.active", "test");
            System.setProperty("spring.datasource.url", "jdbc:sqlite:target/test-wrms-b128.db?busy_timeout=30000&journal_mode=WAL");
            File targetDir = new File("target");
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }
            File realDb = new File("data/wrms.db");
            File testDb = new File("target/test-wrms-b128.db");
            if (realDb.exists()) {
                Files.copy(realDb.toPath(), testDb.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[Batch 128 Test] Isolated test replica created at target/test-wrms-b128.db");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Autowired
    private RosterService rosterService;

    @Autowired
    private RosterCycleRepository rosterCycleRepository;

    @Autowired
    private RosterAssignmentRepository rosterAssignmentRepository;

    @Autowired
    private RosterGenerationHistoryRepository rosterGenerationHistoryRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private EmployeeService employeeService;

    private List<Employee> activeEmployees;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "admin",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );
        activeEmployees = employeeRepository.findByActiveTrueOrderByIdAsc();
        assertTrue(activeEmployees.size() >= 4, "Active workforce should have at least 4 employees");
    }

    @Test
    @DisplayName("1. Single Weekly Off Guarantee: Every active employee gets EXACTLY 1 Weekly Off in 6-day week")
    void testExactSingleWeeklyOffPerEmployeeInSixDayWeek() {
        LocalDate monday = LocalDate.now().plusMonths(6).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));

        RosterCycleResponse response = rosterService.generateWeeklyRoster(monday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(response);
        assertNotNull(response.id());

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(response.id());
        assertFalse(assignments.isEmpty());

        Map<Long, List<RosterAssignment>> empMap = assignments.stream()
                .collect(Collectors.groupingBy(a -> a.getEmployee().getId()));

        for (Employee emp : activeEmployees) {
            List<RosterAssignment> empAssigns = empMap.get(emp.getId());
            assertNotNull(empAssigns, "Assignments must exist for employee " + emp.getFirstName());
            long weeklyOffCount = empAssigns.stream().filter(RosterAssignment::isWeeklyOff).count();

            assertEquals(1, weeklyOffCount,
                    "Employee " + emp.getFirstName() + " (" + emp.getEmployeeCode() + ") must have EXACTLY 1 Weekly Off (got " + weeklyOffCount + ")");
        }

        // Clean up
        rosterService.deleteCycle(response.id());
    }

    @Test
    @DisplayName("2. Global Weekly Off Mathematics: Total workforce Weekly Offs == N in 6-day week")
    void testGlobalWeeklyOffMathematics() {
        LocalDate monday = LocalDate.now().plusMonths(7).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));

        RosterCycleResponse response = rosterService.generateWeeklyRoster(monday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(response);

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(response.id());
        long totalWeeklyOffs = assignments.stream().filter(RosterAssignment::isWeeklyOff).count();

        int expectedTotalOffs = activeEmployees.size();
        assertEquals(expectedTotalOffs, totalWeeklyOffs,
                "Workforce total weekly offs must equal eligible active employee count N (" + expectedTotalOffs + ")");

        // Clean up
        rosterService.deleteCycle(response.id());
    }

    @Test
    @DisplayName("3. Clustered Night Shifts: Max 2 nights, clustered consecutively as a block followed by Weekly Off")
    void testClusteredNightShiftsAndPostNightOff() {
        LocalDate monday = LocalDate.now().plusMonths(8).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));

        RosterCycleResponse response = rosterService.generateWeeklyRoster(monday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(response);

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(response.id());
        Map<Long, List<RosterAssignment>> empMap = assignments.stream()
                .collect(Collectors.groupingBy(a -> a.getEmployee().getId()));

        for (Employee emp : activeEmployees) {
            List<RosterAssignment> empAssigns = empMap.get(emp.getId());
            empAssigns.sort(Comparator.comparing(RosterAssignment::getRosterDate));

            List<LocalDate> nightDates = empAssigns.stream()
                    .filter(a -> !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT)
                    .map(RosterAssignment::getRosterDate)
                    .sorted()
                    .toList();

            assertTrue(nightDates.size() <= 2,
                    "Employee " + emp.getFirstName() + " assigned " + nightDates.size() + " nights (must be <= 2)");

            if (nightDates.size() == 2) {
                // Must be clustered consecutively: date2 == date1 + 1 day
                assertEquals(nightDates.get(0).plusDays(1), nightDates.get(1),
                        "Employee " + emp.getFirstName() + " 2 nights must be consecutive, but got " + nightDates);

                // If not ending on Sunday (cycle end), day immediately following 2nd night must be Weekly Off
                LocalDate postNight = nightDates.get(1).plusDays(1);
                if (!postNight.isAfter(monday.plusDays(6))) {
                    RosterAssignment postAssign = empAssigns.stream()
                            .filter(a -> a.getRosterDate().equals(postNight))
                            .findFirst().orElse(null);
                    assertNotNull(postAssign);
                    assertTrue(postAssign.isWeeklyOff(),
                            "Day after 2 consecutive nights (" + postNight + ") must be Weekly Off for " + emp.getFirstName());
                }
            } else if (nightDates.size() == 1) {
                // If not ending on Sunday, day immediately following night must be Weekly Off
                LocalDate postNight = nightDates.get(0).plusDays(1);
                if (!postNight.isAfter(monday.plusDays(6))) {
                    RosterAssignment postAssign = empAssigns.stream()
                            .filter(a -> a.getRosterDate().equals(postNight))
                            .findFirst().orElse(null);
                    assertNotNull(postAssign);
                    assertTrue(postAssign.isWeeklyOff(),
                            "Day after single night (" + postNight + ") must be Weekly Off for " + emp.getFirstName());
                }
            }

            // Total weekly offs must still be exactly 1
            long weeklyOffCount = empAssigns.stream().filter(RosterAssignment::isWeeklyOff).count();
            assertEquals(1, weeklyOffCount,
                    "Employee " + emp.getFirstName() + " must have exactly 1 Weekly Off even with night shift recovery");
        }

        // Clean up
        rosterService.deleteCycle(response.id());
    }

    @Test
    @DisplayName("4. Regeneration Diversity: Deleting and regenerating for same week produces a distinct valid candidate")
    void testRegenerationDiversityOnDeletedRoster() {
        LocalDate monday = LocalDate.now().plusMonths(9).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));

        // Generate #1
        RosterCycleResponse r1 = rosterService.generateWeeklyRoster(monday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(r1);
        List<RosterAssignment> assign1 = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(r1.id());
        String fp1 = RosterService.computeRosterFingerprint(assign1);
        assertNotNull(fp1);

        // Delete #1
        rosterService.deleteCycle(r1.id());

        // Generate #2 for same week
        RosterCycleResponse r2 = rosterService.generateWeeklyRoster(monday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(r2);
        List<RosterAssignment> assign2 = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(r2.id());
        String fp2 = RosterService.computeRosterFingerprint(assign2);
        assertNotNull(fp2);

        // Verify valid and distinct
        RosterService.FinalValidationResult val2 = rosterService.validateCycle(r2.id());
        assertNotNull(val2);
        assertEquals(0, val2.criticalConflicts(),
                "Regenerated candidate must have zero critical conflicts: " + val2.conflicts());
        assertNotEquals(fp1, fp2,
                "Regenerated candidate after deletion must have a distinct fingerprint from deleted roster");

        // Clean up
        rosterService.deleteCycle(r2.id());
    }

    @Test
    @DisplayName("5. Database-Driven Shift Capacity: Modifying capacity in table takes immediate effect")
    void testDatabaseDrivenShiftCapacity() {
        Shift morning = shiftRepository.findByShiftType(ShiftType.MORNING).orElse(null);
        assertNotNull(morning, "Morning shift must exist");

        int originalCapacity = morning.getCapacity();
        try {
            // Update capacity in database to 3
            morning.setCapacity(3);
            shiftRepository.save(morning);

            // Read back directly from shiftRepository
            Shift updated = shiftRepository.findByShiftType(ShiftType.MORNING).orElseThrow();
            assertEquals(3, updated.getCapacity(), "Capacity in database must be 3");

            LocalDate monday = LocalDate.now().plusMonths(10).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
            RosterCycleResponse resp = rosterService.generateWeeklyRoster(monday, GenerationMode.AUTOMATIC, 6);
            assertNotNull(resp);

            // Clean up cycle
            rosterService.deleteCycle(resp.id());
        } finally {
            // Restore original capacity
            morning.setCapacity(originalCapacity);
            shiftRepository.save(morning);
        }
    }

    @Test
    @DisplayName("6. Employee Profile and Directory UI contains Joining Date and Resignation Date")
    void testEmployeeProfileAndDirectoryJoiningAndResignationDates() throws Exception {
        Employee emp = activeEmployees.get(0);
        assertNotNull(emp);

        LocalDate joining = LocalDate.of(2023, 1, 15);
        emp.setJoiningDate(joining);
        employeeRepository.save(emp);

        Employee saved = employeeRepository.findById(emp.getId()).orElseThrow();
        assertEquals(joining, saved.getJoiningDate(), "Joining date must be saved and loaded from DB");

        // Verify index.html contains joining date and resignation date modal inputs
        File indexHtml = new File("src/main/resources/static/index.html");
        assertTrue(indexHtml.exists(), "index.html must exist");
        String html = Files.readString(indexHtml.toPath());
        assertTrue(html.contains("empFormJoiningDate"), "index.html must have #empFormJoiningDate input");
        assertTrue(html.contains("empFormResignationDate"), "index.html must have #empFormResignationDate input");

        // Verify app.js contains joiningDate and resignationDate bindings
        File appJs = new File("src/main/resources/static/app.js");
        assertTrue(appJs.exists(), "app.js must exist");
        String js = Files.readString(appJs.toPath());
        assertTrue(js.contains("empFormJoiningDate"), "app.js must reference empFormJoiningDate");
        assertTrue(js.contains("empFormResignationDate"), "app.js must reference empFormResignationDate");
        assertTrue(js.contains("Joining Date"), "app.js must render Joining Date in table/profile");
    }
}
