package com.weeklyroster.service;

import com.weeklyroster.dto.request.ClearManualRosterRequest;
import com.weeklyroster.dto.request.ManualAssignmentRequest;
import com.weeklyroster.dto.request.ManualShiftSwapRequest;
import com.weeklyroster.dto.response.*;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "wrms.dev.credential-mirror.enabled=false",
        "spring.datasource.url=jdbc:sqlite:target/test-wrms.db?busy_timeout=30000&journal_mode=WAL"
})
public class Batch109RosterEngineDiversityAndNightOffTest {

    static {
        try {
            System.setProperty("spring.profiles.active", "test");
            System.setProperty("spring.datasource.url", "jdbc:sqlite:target/test-wrms.db?busy_timeout=30000&journal_mode=WAL");
            java.io.File targetDir = new java.io.File("target");
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }
            java.io.File realDb = new java.io.File("data/wrms.db");
            java.io.File testDb = new java.io.File("target/test-wrms.db");
            if (realDb.exists()) {
                java.nio.file.Files.copy(realDb.toPath(), testDb.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[Batch 109 Test] Isolated test replica created at target/test-wrms.db");
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

    private List<Employee> activeEmployees;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "admin",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );
        activeEmployees = employeeRepository.findByActiveTrueOrderByIdAsc();
        assertTrue(activeEmployees.size() >= 4, "Need at least 4 active employees for comprehensive tests");
    }

    @Test
    @DisplayName("Requirement 1: Regeneration Diversity + Rejection Memory (Generate #1 -> Delete/Reject -> Generate #2 -> Delete/Reject -> Generate #3 produce distinct valid rosters)")
    void testRegenerationDiversityWithRejectionMemory() {
        LocalDate testMonday = LocalDate.now().plusMonths(4)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        LocalDate testSunday = testMonday.plusDays(6);

        // 1. Generate Roster #1
        RosterCycleResponse r1 = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(r1);
        assertNotNull(r1.id());
        List<RosterAssignment> assign1 = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(r1.id());
        assertFalse(assign1.isEmpty(), "Roster #1 must have assignments");
        String fp1 = RosterService.computeRosterFingerprint(assign1);
        assertNotNull(fp1);
        assertFalse(fp1.isBlank());

        // 2. Delete / Reject Roster #1
        rosterService.deleteCycle(r1.id());

        // Verify fingerprint is remembered in generation history
        Set<String> pastFpsAfterDelete1 = rosterGenerationHistoryRepository.findFingerprintsByDateRange(testMonday, testSunday);
        assertTrue(pastFpsAfterDelete1.contains(fp1), "History must remember rejected roster #1 fingerprint!");

        // 3. Generate Roster #2 for the exact same week
        RosterCycleResponse r2 = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(r2);
        List<RosterAssignment> assign2 = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(r2.id());
        assertFalse(assign2.isEmpty(), "Roster #2 must have assignments");
        String fp2 = RosterService.computeRosterFingerprint(assign2);

        // Assert Roster #2 has a DIFFERENT fingerprint from Roster #1
        assertNotEquals(fp1, fp2, "Regenerated Roster #2 must be different from rejected Roster #1!");

        // 4. Delete / Reject Roster #2
        rosterService.deleteCycle(r2.id());

        Set<String> pastFpsAfterDelete2 = rosterGenerationHistoryRepository.findFingerprintsByDateRange(testMonday, testSunday);
        assertTrue(pastFpsAfterDelete2.contains(fp1), "History must remember fp1");
        assertTrue(pastFpsAfterDelete2.contains(fp2), "History must remember fp2");

        // 5. Generate Roster #3 for the exact same week
        RosterCycleResponse r3 = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(r3);
        List<RosterAssignment> assign3 = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(r3.id());
        assertFalse(assign3.isEmpty(), "Roster #3 must have assignments");
        String fp3 = RosterService.computeRosterFingerprint(assign3);

        // Assert Roster #3 is different from both #1 and #2
        assertNotEquals(fp1, fp3, "Regenerated Roster #3 must be different from Roster #1!");
        assertNotEquals(fp2, fp3, "Regenerated Roster #3 must be different from Roster #2!");

        // Clean up
        rosterService.deleteCycle(r3.id());
    }

    @Test
    @DisplayName("Requirement 2: Strict Hard Rule: NIGHT (22:00-07:00) MUST receive WEEKLY OFF on the following calendar day")
    void testStrictNightShiftAlwaysFollowedByWeeklyOff() {
        LocalDate testMonday = LocalDate.now().plusMonths(5)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));

        RosterCycleResponse res = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(res);

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(res.id());
        Map<Long, Map<LocalDate, RosterAssignment>> empMap = new HashMap<>();
        for (RosterAssignment a : assignments) {
            empMap.computeIfAbsent(a.getEmployee().getId(), k -> new HashMap<>()).put(a.getRosterDate(), a);
        }

        int totalNightShifts = 0;
        for (Map<LocalDate, RosterAssignment> dayMap : empMap.values()) {
            for (Map.Entry<LocalDate, RosterAssignment> entry : dayMap.entrySet()) {
                LocalDate d = entry.getKey();
                RosterAssignment a = entry.getValue();

                if (!a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT) {
                    totalNightShifts++;
                    LocalDate nextD = d.plusDays(1);
                    if (dayMap.containsKey(nextD)) {
                        RosterAssignment nextA = dayMap.get(nextD);
                        assertTrue(nextA.isWeeklyOff() || nextA.isOnLeave(),
                                "CRIS Strict Rule Violation: Employee " + a.getEmployee().getFirstName() +
                                        " worked NIGHT on " + d + " but does NOT have WEEKLY OFF on " + nextD +
                                        " (assigned: " + (nextA.getShift() != null ? nextA.getShift().getShiftType() : "NULL") + ")");
                        if (!nextA.isOnLeave()) {
                            assertEquals(ShiftType.OFF, nextA.getShift().getShiftType(),
                                    "Shift on day after NIGHT must be OFF");
                        }
                    }
                }
            }
        }

        assertTrue(totalNightShifts >= 7, "A 7-day roster must contain at least 7 night shifts across the week");

        // Clean up
        rosterService.deleteCycle(res.id());
    }

    @Test
    @DisplayName("Requirement 2b: Never produce consecutive night shifts (NIGHT -> NIGHT is strictly 0)")
    void testNeverConsecutiveNights() {
        LocalDate testMonday = LocalDate.now().plusMonths(6)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));

        RosterCycleResponse res = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);
        assertNotNull(res);

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByCycleIdOrderByRosterDateAsc(res.id());
        Map<Long, List<RosterAssignment>> empMap = new HashMap<>();
        for (RosterAssignment a : assignments) {
            empMap.computeIfAbsent(a.getEmployee().getId(), k -> new ArrayList<>()).add(a);
        }

        for (List<RosterAssignment> list : empMap.values()) {
            list.sort(Comparator.comparing(RosterAssignment::getRosterDate));
            for (int i = 0; i < list.size() - 1; i++) {
                RosterAssignment cur = list.get(i);
                RosterAssignment next = list.get(i + 1);

                if (!cur.isWeeklyOff() && cur.getShift() != null && cur.getShift().getShiftType() == ShiftType.NIGHT) {
                    assertNotEquals(ShiftType.NIGHT, next.getShift() != null ? next.getShift().getShiftType() : null,
                            "Forbidden consecutive NIGHT duties for employee " + cur.getEmployee().getFirstName() + " between " + cur.getRosterDate() + " and " + next.getRosterDate());
                    assertNotEquals(ShiftType.MORNING, next.getShift() != null ? next.getShift().getShiftType() : null);
                    assertNotEquals(ShiftType.GENERAL, next.getShift() != null ? next.getShift().getShiftType() : null);
                    assertNotEquals(ShiftType.EVENING, next.getShift() != null ? next.getShift().getShiftType() : null);
                }
            }
        }

        // Clean up
        rosterService.deleteCycle(res.id());
    }

    @Test
    @DisplayName("Requirement 2c: Manual Assignment Layer - assigning working shift day after NIGHT throws Level 0 Constraint Conflict")
    void testManualAssignmentNightOffConstraint() {
        LocalDate testMonday = LocalDate.now().plusMonths(7)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);

        Employee maleEmployee = activeEmployees.stream()
                .filter(e -> e.getGender() == Gender.MALE)
                .findFirst()
                .orElseThrow();

        LocalDate mon = testMonday;
        LocalDate tue = testMonday.plusDays(1);

        // Admin assigns Monday to NIGHT
        ManualAssignmentRequest nightReq = new ManualAssignmentRequest(
                cycle.id(), testMonday, maleEmployee.getId(), mon, ShiftType.NIGHT, false, "Admin Night Test"
        );
        ManualRosterMatrixResponse matrix1 = rosterService.saveManualAssignment(nightReq, "admin");
        assertNotNull(matrix1);

        // Verify Tuesday was automatically set to Weekly OFF
        List<RosterAssignment> tueList = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(maleEmployee.getId(), tue);
        assertFalse(tueList.isEmpty());
        assertTrue(tueList.get(0).isWeeklyOff(), "Tuesday must be set to Weekly OFF following Monday NIGHT");

        // Now attempt to assign Tuesday to MORNING -> Must throw Level 0 Constraint Conflict
        ManualAssignmentRequest morningReq = new ManualAssignmentRequest(
                cycle.id(), testMonday, maleEmployee.getId(), tue, ShiftType.MORNING, false, "Forbidden Morning"
        );
        BusinessException ex = assertThrows(BusinessException.class, () ->
                rosterService.saveManualAssignment(morningReq, "admin")
        );
        assertTrue(ex.getMessage().contains("worked NIGHT") || ex.getMessage().contains("fatigue safety rule"),
                "Exception message must mention night shift rule: " + ex.getMessage());

        // Attempting to assign Tuesday to NIGHT -> Also throws Level 0 Constraint Conflict
        ManualAssignmentRequest secondNightReq = new ManualAssignmentRequest(
                cycle.id(), testMonday, maleEmployee.getId(), tue, ShiftType.NIGHT, false, "Forbidden 2nd Night"
        );
        assertThrows(BusinessException.class, () ->
                rosterService.saveManualAssignment(secondNightReq, "admin")
        );

        // Clean up
        rosterService.deleteCycle(cycle.id());
    }

    @Test
    @DisplayName("Requirement 3: Manual Builder Features Preservation (Clear 4 modes, Compare, Swap, Undo remain 100% functional)")
    void testManualBuilderFeaturesPreserved() {
        LocalDate testMonday = LocalDate.now().plusMonths(8)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        RosterCycleResponse cycle = rosterService.generateWeeklyRoster(testMonday, GenerationMode.AUTOMATIC, 6);

        // 1. Matrix retrieval
        ManualRosterMatrixResponse matrix = rosterService.getManualRosterMatrix(cycle.id(), testMonday);
        assertNotNull(matrix);
        assertEquals(7, matrix.dates().size());

        // 2. Shift Swap preview
        Employee empA = activeEmployees.get(0);
        Employee empB = activeEmployees.get(1);
        ManualShiftSwapRequest swapReq = new ManualShiftSwapRequest(
                cycle.id(), testMonday, empA.getId(), testMonday, empB.getId(), testMonday, false, false
        );
        ManualShiftSwapPreviewResponse preview = rosterService.previewShiftSwap(swapReq);
        assertNotNull(preview);
        assertNotNull(preview.proposedShiftA());
        assertNotNull(preview.proposedShiftB());

        // 3. Clear Preview: Entire Week
        ClearManualRosterRequest clearWeekReq = new ClearManualRosterRequest(
                cycle.id(), testMonday, "ENTIRE_WEEK", null, null, null, false
        );
        ClearRosterPreviewResponse clearPreview = rosterService.previewClearRoster(clearWeekReq);
        assertNotNull(clearPreview);
        assertEquals("ENTIRE_WEEK", clearPreview.mode());
        assertTrue(clearPreview.affectedAssignmentsCount() > 0);

        // 4. Week Comparison
        WeekComparisonResponse compareRes = rosterService.compareWeeks(testMonday, testMonday.minusWeeks(1));
        assertNotNull(compareRes);
        assertEquals(testMonday, compareRes.weekAStartDate());

        // 5. Perform manual edit & verify Undo Operation
        ManualAssignmentRequest manualReq = new ManualAssignmentRequest(
                cycle.id(), testMonday, empA.getId(), testMonday, ShiftType.MORNING, false, "Manual adjustment test"
        );
        rosterService.saveManualAssignment(manualReq, "admin");

        UndoOperationResponse undoRes = rosterService.undoLastManualOperation(cycle.id(), "admin");
        assertNotNull(undoRes);
        assertNotNull(undoRes.matrix());

        // Clean up
        rosterService.deleteCycle(cycle.id());
    }
}
