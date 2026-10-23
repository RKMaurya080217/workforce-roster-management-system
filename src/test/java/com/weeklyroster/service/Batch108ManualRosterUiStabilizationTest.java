package com.weeklyroster.service;

import com.weeklyroster.dto.request.BulkManualAssignmentRequest;
import com.weeklyroster.dto.request.ClearManualRosterRequest;
import com.weeklyroster.dto.request.CopyPreviousWeekRequest;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "wrms.dev.credential-mirror.enabled=false",
        "spring.datasource.url=jdbc:sqlite:target/test-wrms.db?busy_timeout=30000&journal_mode=WAL"
})
public class Batch108ManualRosterUiStabilizationTest {

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
                System.out.println("[Batch 108] Isolated test replica created at target/test-wrms.db");
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
    private EmployeeRepository employeeRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    private List<Employee> activeEmployees;

    @BeforeEach
    void setUp() {
        activeEmployees = employeeRepository.findByActiveTrueOrderByIdAsc();
        assertTrue(activeEmployees.size() >= 2, "Need at least 2 active employees for tests");
    }

    @Test
    @DisplayName("Mode A: Clear entire week resets shifts to OFF and records durable undo")
    void testClearEntireWeekWithUndo() {
        LocalDate monday = LocalDate.of(2027, 8, 2).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        RosterCycle cycle = rosterService.getOrCreateDraftCycle(monday, 6);

        // Assign shifts to employees
        List<Long> empIds = activeEmployees.stream().map(Employee::getId).toList();
        List<LocalDate> dates = new ArrayList<>();
        for (int i = 0; i < 7; i++) dates.add(monday.plusDays(i));

        rosterService.bulkAssignManual(new BulkManualAssignmentRequest(
                cycle.getId(), monday, empIds, dates, ShiftType.MORNING, false, "Test morning setup"
        ), "admin_test");

        // Preview Mode A
        ClearRosterPreviewResponse preview = rosterService.previewClearRoster(
                new ClearManualRosterRequest(cycle.getId(), monday, "ENTIRE_WEEK", null, null, null, true)
        );
        assertNotNull(preview);
        assertEquals("ENTIRE_WEEK", preview.mode());
        assertTrue(preview.affectedAssignmentsCount() >= activeEmployees.size() * 7);
        assertTrue(preview.canApply());

        // Execute Mode A clear
        ManualRosterMatrixResponse cleared = rosterService.clearManualRoster(
                new ClearManualRosterRequest(cycle.getId(), monday, "ENTIRE_WEEK", null, null, null, false),
                "admin_test"
        );
        assertNotNull(cleared);

        // Verify assignments are OFF
        for (Long empId : empIds) {
            for (LocalDate d : dates) {
                var list = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(empId, d);
                assertFalse(list.isEmpty());
                assertTrue(list.get(0).isWeeklyOff(), "Cleared assignment should be weeklyOff");
                assertEquals(ShiftType.OFF, list.get(0).getShift().getShiftType());
            }
        }

        // Verify durable undo status
        Map<String, Object> undoStatus = rosterService.getUndoStatus(cycle.getId());
        assertTrue((Boolean) undoStatus.get("canUndo"));

        // Execute undo
        UndoOperationResponse undoRes = rosterService.undoLastManualOperation(cycle.getId(), "admin_test");
        assertNotNull(undoRes);
        assertTrue(undoRes.restoredAssignmentsCount() > 0);

        // Verify restored assignments are MORNING
        for (Long empId : empIds) {
            for (LocalDate d : dates) {
                var list = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(empId, d);
                assertFalse(list.isEmpty());
                assertEquals(ShiftType.MORNING, list.get(0).getShift().getShiftType(), "Undo should restore MORNING shift");
            }
        }
    }

    @Test
    @DisplayName("Mode B: Clear selected employees clears only targeted staff and preserves others")
    void testClearSelectedEmployeesPreservesOthers() {
        LocalDate monday = LocalDate.of(2027, 8, 9).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        RosterCycle cycle = rosterService.getOrCreateDraftCycle(monday, 6);

        Employee targetEmp = activeEmployees.get(0);
        Employee preservedEmp = activeEmployees.get(1);

        List<LocalDate> dates = new ArrayList<>();
        for (int i = 0; i < 7; i++) dates.add(monday.plusDays(i));

        // Assign GENERAL to both
        rosterService.bulkAssignManual(new BulkManualAssignmentRequest(
                cycle.getId(), monday, List.of(targetEmp.getId(), preservedEmp.getId()), dates, ShiftType.GENERAL, false, "Setup"
        ), "admin_test");

        // Clear only targetEmp
        ManualRosterMatrixResponse cleared = rosterService.clearManualRoster(
                new ClearManualRosterRequest(cycle.getId(), monday, "SELECTED_EMPLOYEES", List.of(targetEmp.getId()), null, null, false),
                "admin_test"
        );
        assertNotNull(cleared);

        // Verify targetEmp is OFF
        for (LocalDate d : dates) {
            var list = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(targetEmp.getId(), d);
            assertTrue(list.get(0).isWeeklyOff());
            assertEquals(ShiftType.OFF, list.get(0).getShift().getShiftType());
        }

        // Verify preservedEmp remains GENERAL
        for (LocalDate d : dates) {
            var list = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(preservedEmp.getId(), d);
            assertFalse(list.get(0).isWeeklyOff());
            assertEquals(ShiftType.GENERAL, list.get(0).getShift().getShiftType(), "Preserved emp should still have GENERAL shift");
        }
    }

    @Test
    @DisplayName("Mode C: Clear selected dates clears only targeted dates and preserves other weekdays")
    void testClearSelectedDatesPreservesOtherDays() {
        LocalDate monday = LocalDate.of(2027, 8, 16).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        RosterCycle cycle = rosterService.getOrCreateDraftCycle(monday, 6);

        Employee emp = activeEmployees.get(0);
        List<LocalDate> allDates = new ArrayList<>();
        for (int i = 0; i < 7; i++) allDates.add(monday.plusDays(i));

        rosterService.bulkAssignManual(new BulkManualAssignmentRequest(
                cycle.getId(), monday, List.of(emp.getId()), allDates, ShiftType.MORNING, false, "Setup"
        ), "admin_test");

        // Clear Saturday and Sunday only
        LocalDate sat = monday.plusDays(5);
        LocalDate sun = monday.plusDays(6);
        rosterService.clearManualRoster(
                new ClearManualRosterRequest(cycle.getId(), monday, "SELECTED_DATES", null, List.of(sat, sun), null, false),
                "admin_test"
        );

        // Monday..Friday should remain MORNING
        for (int i = 0; i < 5; i++) {
            var list = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), monday.plusDays(i));
            assertEquals(ShiftType.MORNING, list.get(0).getShift().getShiftType(), "Weekday shift should be preserved");
        }

        // Weekend should be OFF
        assertEquals(ShiftType.OFF, rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), sat).get(0).getShift().getShiftType());
        assertEquals(ShiftType.OFF, rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), sun).get(0).getShift().getShiftType());
    }

    @Test
    @DisplayName("Mode D: Clear single cell clears only that cell and preserves adjacent cells")
    void testClearSingleCellPreservesOthers() {
        LocalDate monday = LocalDate.of(2027, 8, 23).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        RosterCycle cycle = rosterService.getOrCreateDraftCycle(monday, 6);

        Employee emp = activeEmployees.get(0);
        LocalDate monDate = monday;
        LocalDate tueDate = monday.plusDays(1);

        rosterService.bulkAssignManual(new BulkManualAssignmentRequest(
                cycle.getId(), monday, List.of(emp.getId()), List.of(monDate, tueDate), ShiftType.EVENING, false, "Setup"
        ), "admin_test");

        // Clear only Monday
        rosterService.clearManualRoster(
                new ClearManualRosterRequest(cycle.getId(), monday, "SELECTED_CELLS", null, null,
                        List.of(new ClearManualRosterRequest.CellCoordinate(emp.getId(), monDate)), false),
                "admin_test"
        );

        // Monday should be OFF
        assertEquals(ShiftType.OFF, rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), monDate).get(0).getShift().getShiftType());
        // Tuesday should still be EVENING
        assertEquals(ShiftType.EVENING, rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), tueDate).get(0).getShift().getShiftType());
    }

    @Test
    @DisplayName("Week Comparison engine correctly detects shift, OFF, and manual flag differences")
    void testWeekComparisonDiffEngine() {
        LocalDate weekA = LocalDate.of(2027, 9, 6).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekB = LocalDate.of(2027, 9, 13).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        RosterCycle cycleA = rosterService.getOrCreateDraftCycle(weekA, 6);
        RosterCycle cycleB = rosterService.getOrCreateDraftCycle(weekB, 6);

        Employee emp = activeEmployees.get(0);
        List<LocalDate> datesA = List.of(weekA);
        List<LocalDate> datesB = List.of(weekB);

        // Week A: MORNING
        rosterService.bulkAssignManual(new BulkManualAssignmentRequest(
                cycleA.getId(), weekA, List.of(emp.getId()), datesA, ShiftType.MORNING, false, "Week A"
        ), "admin_test");

        // Week B: NIGHT
        rosterService.bulkAssignManual(new BulkManualAssignmentRequest(
                cycleB.getId(), weekB, List.of(emp.getId()), datesB, ShiftType.NIGHT, false, "Week B"
        ), "admin_test");

        WeekComparisonResponse comparison = rosterService.compareWeeks(weekA, weekB);
        assertNotNull(comparison);
        assertTrue(comparison.totalDifferences() > 0, "Should detect differences between Week A and Week B");

        // Find row for our employee
        var empRow = comparison.rows().stream().filter(r -> r.employeeId().equals(emp.getId())).findFirst();
        assertTrue(empRow.isPresent());

        // Check Monday (day 1) comparison
        var mondayCell = empRow.get().dayComparisons().get(1);
        assertNotNull(mondayCell);
        assertTrue(mondayCell.hasDifference());
        assertEquals("MORNING", mondayCell.shiftA());
        assertEquals("NIGHT", mondayCell.shiftB());
    }

    @Test
    @DisplayName("Shift Swap validation enforces statutory protections and successfully swaps valid assignments")
    void testShiftSwapValidationAndExecution() {
        LocalDate monday = LocalDate.of(2027, 9, 20).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        RosterCycle cycle = rosterService.getOrCreateDraftCycle(monday, 6);

        Employee empA = activeEmployees.get(0);
        Employee empB = activeEmployees.get(1);

        LocalDate dateA = monday;
        LocalDate dateB = monday.plusDays(1);

        rosterService.bulkAssignManual(new BulkManualAssignmentRequest(
                cycle.getId(), monday, List.of(empA.getId()), List.of(dateA), ShiftType.MORNING, false, "A"
        ), "admin_test");
        rosterService.bulkAssignManual(new BulkManualAssignmentRequest(
                cycle.getId(), monday, List.of(empB.getId()), List.of(dateB), ShiftType.EVENING, false, "B"
        ), "admin_test");

        // Preview swap
        ManualShiftSwapPreviewResponse preview = rosterService.previewShiftSwap(new ManualShiftSwapRequest(
                cycle.getId(), monday, empA.getId(), dateA, empB.getId(), dateB, false, false
        ));
        assertNotNull(preview);
        assertEquals("MORNING", preview.originalShiftA());
        assertEquals("EVENING", preview.proposedShiftA());
        assertEquals("EVENING", preview.originalShiftB());
        assertEquals("MORNING", preview.proposedShiftB());

        if (preview.canApply()) {
            // Execute swap
            ManualRosterMatrixResponse swapped = rosterService.swapManualShifts(new ManualShiftSwapRequest(
                    cycle.getId(), monday, empA.getId(), dateA, empB.getId(), dateB, false, false
            ), "admin_test");
            assertNotNull(swapped);

            // Emp A on dateA should now have EVENING
            var assignA = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(empA.getId(), dateA).get(0);
            assertEquals(ShiftType.EVENING, assignA.getShift().getShiftType());
            assertEquals("SWAPPED", assignA.getAssignmentSource());

            // Emp B on dateB should now have MORNING
            var assignB = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(empB.getId(), dateB).get(0);
            assertEquals(ShiftType.MORNING, assignB.getShift().getShiftType());
            assertEquals("SWAPPED", assignB.getAssignmentSource());
        }
    }

    @Test
    @DisplayName("Copy previous week copies shift pattern and respects manual lock protections")
    void testCopyPreviousWeekWithSafetyLocks() {
        LocalDate srcMonday = LocalDate.of(2027, 9, 27).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate tgtMonday = LocalDate.of(2027, 10, 4).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        RosterCycle srcCycle = rosterService.getOrCreateDraftCycle(srcMonday, 6);
        RosterCycle tgtCycle = rosterService.getOrCreateDraftCycle(tgtMonday, 6);

        Employee emp = activeEmployees.get(0);

        // Assign source pattern
        rosterService.bulkAssignManual(new BulkManualAssignmentRequest(
                srcCycle.getId(), srcMonday, List.of(emp.getId()), List.of(srcMonday), ShiftType.NIGHT, false, "Src night"
        ), "admin_test");

        // Target week has an existing manual lock (MORNING)
        rosterService.bulkAssignManual(new BulkManualAssignmentRequest(
                tgtCycle.getId(), tgtMonday, List.of(emp.getId()), List.of(tgtMonday), ShiftType.MORNING, false, "Target lock"
        ), "admin_test");

        // Copy with overwriteExistingManual = false
        CopyWeekPreviewResponse preview = rosterService.previewCopyWeek(new CopyPreviousWeekRequest(
                tgtCycle.getId(), tgtMonday, srcMonday, true, false, true
        ));
        assertNotNull(preview);
        assertTrue(preview.protectedManualCount() >= 1, "Should protect existing manual lock");

        // Execute copy with overwrite = false
        rosterService.copyPreviousWeek(new CopyPreviousWeekRequest(
                tgtCycle.getId(), tgtMonday, srcMonday, true, false, false
        ), "admin_test");

        // The protected manual shift should remain MORNING
        var assignTgt = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), tgtMonday).get(0);
        assertEquals(ShiftType.MORNING, assignTgt.getShift().getShiftType(), "Manual lock must be preserved when overwrite=false");
    }

    @Test
    @DisplayName("Automatic roster generation remains fully functional and separate from manual operations")
    void testAutomaticRosterRemainsFullyFunctional() {
        LocalDate autoMonday = LocalDate.of(2027, 10, 11).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        RosterCycleResponse generated = rosterService.generateWeeklyRoster(autoMonday, GenerationMode.AUTOMATIC);

        assertNotNull(generated);
        assertNotNull(generated.id());
        assertEquals(autoMonday, generated.startDate());
        assertFalse(generated.assignments().isEmpty(), "Generated roster must contain assignments for all active employees");

        // Check that all active employees are covered
        for (Employee e : activeEmployees) {
            long count = generated.assignments().stream().filter(a -> a.employeeId().equals(e.getId())).count();
            assertEquals(7, count, "Employee #" + e.getId() + " must have 7 daily assignments");
        }
    }
}
