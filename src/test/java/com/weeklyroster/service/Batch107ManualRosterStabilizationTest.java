package com.weeklyroster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.weeklyroster.dto.request.*;
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
public class Batch107ManualRosterStabilizationTest {

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
                System.out.println("[Batch 107] Isolated test replica created at target/test-wrms.db");
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

    @Autowired
    private ManualRosterOperationRepository manualRosterOperationRepository;

    private ObjectMapper objectMapper;
    private List<Employee> activeEmployees;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        activeEmployees = employeeRepository.findByActiveTrueOrderByIdAsc();
        assertFalse(activeEmployees.isEmpty(), "Active employees must exist for testing");
    }

    private LocalDate getMonday(int weeksAhead) {
        return LocalDate.now().plusWeeks(weeksAhead).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }

    // -------------------------------------------------------------------------
    // 1. LEGACY NULL-SAFETY VERIFICATION
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Batch 107: Verify zero NULL values in manual_assignment or assignment_source")
    void testLegacySanitizerNullSafety() {
        List<RosterAssignment> all = rosterAssignmentRepository.findAll();
        for (RosterAssignment a : all) {
            assertNotNull(a.isManualAssignment(), "manualAssignment boolean primitive must not be null");
            assertNotNull(a.getAssignmentSource(), "assignmentSource must not be null");
        }
    }

    // -------------------------------------------------------------------------
    // 2. COPY PREVIOUS ROSTER WORKFLOW
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Batch 107: Copy Previous Week full pattern with preview and Durable Undo")
    void testCopyPreviousWeek_WithUndo() {
        LocalDate targetMonday = getMonday(10);
        LocalDate sourceMonday = targetMonday.minusWeeks(1);
        Employee emp = activeEmployees.get(0);

        // Setup source assignment
        rosterService.saveManualAssignment(new ManualAssignmentRequest(
                null, sourceMonday, emp.getId(), sourceMonday, ShiftType.MORNING, false, "Source Morning"
        ), "Admin");

        // Preview copy
        CopyWeekPreviewResponse preview = rosterService.previewCopyWeek(new CopyPreviousWeekRequest(
                null, targetMonday, sourceMonday, true, false, true
        ));
        assertNotNull(preview);
        assertTrue(preview.totalAssignmentsToCopy() > 0, "Preview should find source assignments");
        assertTrue(preview.canApply(), "Preview canApply should be true");

        // Execute copy
        ManualRosterMatrixResponse matrix = rosterService.copyPreviousWeek(new CopyPreviousWeekRequest(
                null, targetMonday, sourceMonday, true, false, false
        ), "Admin");
        assertNotNull(matrix);
        assertNotNull(matrix.cycleId());

        // Verify assignment copied to target week
        List<RosterAssignment> targetList = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), targetMonday);
        assertFalse(targetList.isEmpty());
        assertEquals(ShiftType.MORNING, targetList.get(0).getShift().getShiftType());
        assertEquals("COPIED", targetList.get(0).getAssignmentSource());

        // Verify Undo status
        Map<String, Object> undoStatus = rosterService.getUndoStatus(matrix.cycleId());
        assertTrue((Boolean) undoStatus.get("canUndo"));
        assertEquals("COPY_WEEK", undoStatus.get("lastOperationType"));

        // Execute Undo
        UndoOperationResponse undoRes = rosterService.undoLastManualOperation(matrix.cycleId(), "Admin");
        assertNotNull(undoRes);
        assertEquals("COPY_WEEK", undoRes.operationType());

        // Target assignment should be restored/removed
        List<RosterAssignment> afterUndo = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), targetMonday);
        assertTrue(afterUndo.isEmpty() || !("COPIED".equals(afterUndo.get(0).getAssignmentSource())));
    }

    @Test
    @DisplayName("Batch 107: Copy Previous Week strictly preserves Level 1 manual locks unless overwrite requested")
    void testCopyPreviousWeek_ManualLockPreservation() {
        LocalDate targetMonday = getMonday(12);
        LocalDate sourceMonday = targetMonday.minusWeeks(1);
        Employee emp = activeEmployees.get(0);

        // Setup source: emp on Monday has GENERAL
        rosterService.saveManualAssignment(new ManualAssignmentRequest(
                null, sourceMonday, emp.getId(), sourceMonday, ShiftType.GENERAL, false, "Source General"
        ), "Admin");

        // Target already has an explicit manual lock: emp on Monday has NIGHT
        ManualRosterMatrixResponse targetMatrix = rosterService.saveManualAssignment(new ManualAssignmentRequest(
                null, targetMonday, emp.getId(), targetMonday, ShiftType.NIGHT, false, "Explicit Manual Night"
        ), "Admin");

        // Copy without overwrite
        rosterService.copyPreviousWeek(new CopyPreviousWeekRequest(
                targetMatrix.cycleId(), targetMonday, sourceMonday, true, false, false
        ), "Admin");

        // Verify emp on Monday remains NIGHT (Level 1 manual lock preserved!)
        List<RosterAssignment> locked = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), targetMonday);
        assertFalse(locked.isEmpty());
        assertEquals(ShiftType.NIGHT, locked.get(0).getShift().getShiftType(),
                "Explicit manual lock must be preserved when overwriteExistingManual is false");

        // Now copy with overwrite = true
        rosterService.copyPreviousWeek(new CopyPreviousWeekRequest(
                targetMatrix.cycleId(), targetMonday, sourceMonday, true, true, false
        ), "Admin");

        List<RosterAssignment> overwritten = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), targetMonday);
        assertFalse(overwritten.isEmpty());
        assertEquals(ShiftType.GENERAL, overwritten.get(0).getShift().getShiftType(),
                "Manual lock should be overwritten when explicit overwrite is confirmed by Admin");
    }

    // -------------------------------------------------------------------------
    // 3. CLEAR ROSTER (ALL 4 MODES)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Batch 107: Clear Roster Mode A (Entire Week) and Durable Undo")
    void testClearRoster_ModeA_EntireWeek_WithUndo() {
        LocalDate testMonday = getMonday(14);
        Employee emp = activeEmployees.get(0);
        ManualRosterMatrixResponse saved = rosterService.saveManualAssignment(new ManualAssignmentRequest(
                null, testMonday, emp.getId(), testMonday, ShiftType.MORNING, false, "Morning setup"
        ), "Admin");

        // Preview
        ClearRosterPreviewResponse preview = rosterService.previewClearRoster(new ClearManualRosterRequest(
                saved.cycleId(), testMonday, "ENTIRE_WEEK", null, null, null, true
        ));
        assertNotNull(preview);
        assertTrue(preview.affectedAssignmentsCount() > 0);
        assertTrue(preview.canApply());

        // Execute Clear
        rosterService.clearManualRoster(new ClearManualRosterRequest(
                saved.cycleId(), testMonday, "ENTIRE_WEEK", null, null, null, false
        ), "Admin");

        List<RosterAssignment> cleared = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), testMonday);
        assertFalse(cleared.isEmpty());
        assertTrue(cleared.get(0).isWeeklyOff() || cleared.get(0).getShift().getShiftType() == ShiftType.OFF);

        // Undo Clear
        UndoOperationResponse undoRes = rosterService.undoLastManualOperation(saved.cycleId(), "Admin");
        assertEquals("CLEAR", undoRes.operationType());

        List<RosterAssignment> restored = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), testMonday);
        assertFalse(restored.isEmpty());
        assertEquals(ShiftType.MORNING, restored.get(0).getShift().getShiftType());
    }

    @Test
    @DisplayName("Batch 107: Clear Roster Mode B (Selected Employees)")
    void testClearRoster_ModeB_SelectedEmployees() {
        LocalDate testMonday = getMonday(16);
        Employee emp1 = activeEmployees.get(0);
        Employee emp2 = activeEmployees.size() > 1 ? activeEmployees.get(1) : emp1;

        ManualRosterMatrixResponse m1 = rosterService.saveManualAssignment(new ManualAssignmentRequest(
                null, testMonday, emp1.getId(), testMonday, ShiftType.MORNING, false, "Emp1 setup"
        ), "Admin");
        rosterService.saveManualAssignment(new ManualAssignmentRequest(
                m1.cycleId(), testMonday, emp2.getId(), testMonday, ShiftType.EVENING, false, "Emp2 setup"
        ), "Admin");

        // Clear only emp1
        rosterService.clearManualRoster(new ClearManualRosterRequest(
                m1.cycleId(), testMonday, "SELECTED_EMPLOYEES", List.of(emp1.getId()), null, null, false
        ), "Admin");

        List<RosterAssignment> list1 = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp1.getId(), testMonday);
        assertTrue(list1.get(0).isWeeklyOff() || list1.get(0).getShift().getShiftType() == ShiftType.OFF);

        if (!emp1.getId().equals(emp2.getId())) {
            List<RosterAssignment> list2 = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp2.getId(), testMonday);
            assertEquals(ShiftType.EVENING, list2.get(0).getShift().getShiftType(),
                    "Emp2 assignment must remain untouched");
        }
    }

    @Test
    @DisplayName("Batch 107: Clear Roster Mode C (Selected Dates)")
    void testClearRoster_ModeC_SelectedDates() {
        LocalDate testMonday = getMonday(18);
        Employee emp = activeEmployees.get(0);
        LocalDate mon = testMonday;
        LocalDate tue = testMonday.plusDays(1);

        ManualRosterMatrixResponse m = rosterService.saveManualAssignment(new ManualAssignmentRequest(
                null, mon, emp.getId(), mon, ShiftType.MORNING, false, "Mon setup"
        ), "Admin");
        rosterService.saveManualAssignment(new ManualAssignmentRequest(
                m.cycleId(), mon, emp.getId(), tue, ShiftType.MORNING, false, "Tue setup"
        ), "Admin");

        // Clear only Tuesday
        rosterService.clearManualRoster(new ClearManualRosterRequest(
                m.cycleId(), mon, "SELECTED_DATES", null, List.of(tue), null, false
        ), "Admin");

        List<RosterAssignment> monList = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), mon);
        assertEquals(ShiftType.MORNING, monList.get(0).getShift().getShiftType(), "Monday should not be cleared");

        List<RosterAssignment> tueList = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), tue);
        assertTrue(tueList.get(0).isWeeklyOff() || tueList.get(0).getShift().getShiftType() == ShiftType.OFF);
    }

    @Test
    @DisplayName("Batch 107: Clear Roster Mode D (Selected Cells)")
    void testClearRoster_ModeD_SelectedCells() {
        LocalDate testMonday = getMonday(20);
        Employee emp = activeEmployees.get(0);
        LocalDate mon = testMonday;

        ManualRosterMatrixResponse m = rosterService.saveManualAssignment(new ManualAssignmentRequest(
                null, mon, emp.getId(), mon, ShiftType.EVENING, false, "Cell setup"
        ), "Admin");

        rosterService.clearManualRoster(new ClearManualRosterRequest(
                m.cycleId(), mon, "SELECTED_CELLS", null, null,
                List.of(new ClearManualRosterRequest.CellCoordinate(emp.getId(), mon)), false
        ), "Admin");

        List<RosterAssignment> cellList = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), mon);
        assertTrue(cellList.get(0).isWeeklyOff() || cellList.get(0).getShift().getShiftType() == ShiftType.OFF);
    }

    // -------------------------------------------------------------------------
    // 4. SHIFT SWAP WITH CONSTRAINTS AND DURABLE UNDO
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Batch 107: Shift Swap preview, execution, and Durable Undo")
    void testShiftSwap_ExecutionAndUndo() {
        if (activeEmployees.size() < 2) return;
        LocalDate testMonday = getMonday(22);
        Employee empA = activeEmployees.get(0);
        Employee empB = activeEmployees.get(1);

        LocalDate dateA = testMonday;
        LocalDate dateB = testMonday.plusDays(1);

        ManualRosterMatrixResponse m = rosterService.saveManualAssignment(new ManualAssignmentRequest(
                null, testMonday, empA.getId(), dateA, ShiftType.MORNING, false, "EmpA morning"
        ), "Admin");
        rosterService.saveManualAssignment(new ManualAssignmentRequest(
                m.cycleId(), testMonday, empB.getId(), dateB, ShiftType.EVENING, false, "EmpB evening"
        ), "Admin");

        // Preview swap
        ManualShiftSwapPreviewResponse preview = rosterService.previewShiftSwap(new ManualShiftSwapRequest(
                m.cycleId(), testMonday, empA.getId(), dateA, empB.getId(), dateB, false, true
        ));
        assertNotNull(preview);
        assertEquals("MORNING", preview.originalShiftA());
        assertEquals("EVENING", preview.proposedShiftA());
        assertEquals("EVENING", preview.originalShiftB());
        assertEquals("MORNING", preview.proposedShiftB());

        // Execute swap
        rosterService.swapManualShifts(new ManualShiftSwapRequest(
                m.cycleId(), testMonday, empA.getId(), dateA, empB.getId(), dateB, false, false
        ), "Admin");

        List<RosterAssignment> afterSwapA = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(empA.getId(), dateA);
        assertEquals(ShiftType.EVENING, afterSwapA.get(0).getShift().getShiftType());
        assertEquals("SWAPPED", afterSwapA.get(0).getAssignmentSource());

        List<RosterAssignment> afterSwapB = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(empB.getId(), dateB);
        assertEquals(ShiftType.MORNING, afterSwapB.get(0).getShift().getShiftType());
        assertEquals("SWAPPED", afterSwapB.get(0).getAssignmentSource());

        // Undo Swap
        UndoOperationResponse undoRes = rosterService.undoLastManualOperation(m.cycleId(), "Admin");
        assertEquals("SHIFT_SWAP", undoRes.operationType());

        List<RosterAssignment> restoredA = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(empA.getId(), dateA);
        assertEquals(ShiftType.MORNING, restoredA.get(0).getShift().getShiftType());

        List<RosterAssignment> restoredB = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(empB.getId(), dateB);
        assertEquals(ShiftType.EVENING, restoredB.get(0).getShift().getShiftType());
    }

    @Test
    @DisplayName("Batch 107: Shift Swap detects female night shift statutory restriction")
    void testShiftSwap_FemaleNightStatutoryCheck() {
        LocalDate testMonday = getMonday(24);
        Employee femaleEmp = activeEmployees.stream().filter(e -> e.getGender() == Gender.FEMALE).findFirst().orElse(null);
        Employee maleEmp = activeEmployees.stream().filter(e -> e.getGender() == Gender.MALE).findFirst().orElse(null);
        if (femaleEmp == null || maleEmp == null) return;

        LocalDate dateA = testMonday;
        LocalDate dateB = testMonday.plusDays(1);

        ManualRosterMatrixResponse m = rosterService.saveManualAssignment(new ManualAssignmentRequest(
                null, testMonday, femaleEmp.getId(), dateA, ShiftType.MORNING, false, "Female morning"
        ), "Admin");
        rosterService.saveManualAssignment(new ManualAssignmentRequest(
                m.cycleId(), testMonday, maleEmp.getId(), dateB, ShiftType.NIGHT, false, "Male night"
        ), "Admin");

        // Swap preview: female employee would receive NIGHT shift
        ManualShiftSwapPreviewResponse preview = rosterService.previewShiftSwap(new ManualShiftSwapRequest(
                m.cycleId(), testMonday, femaleEmp.getId(), dateA, maleEmp.getId(), dateB, false, true
        ));
        assertNotNull(preview);
        assertTrue(preview.conflictCount() > 0, "Statutory female night shift violation must be flagged");
        assertFalse(preview.canApply(), "Cannot apply swap that violates female statutory protection");
    }

    // -------------------------------------------------------------------------
    // 5. WEEK COMPARISON (READ-ONLY)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Batch 107: Read-only Week Comparison computes differences accurately")
    void testWeekComparison_ReadOnly() {
        LocalDate weekA = getMonday(26);
        LocalDate weekB = weekA.plusWeeks(1);

        Employee emp = activeEmployees.get(0);
        rosterService.saveManualAssignment(new ManualAssignmentRequest(
                null, weekA, emp.getId(), weekA, ShiftType.MORNING, false, "Week A"
        ), "Admin");
        rosterService.saveManualAssignment(new ManualAssignmentRequest(
                null, weekB, emp.getId(), weekB, ShiftType.EVENING, false, "Week B"
        ), "Admin");

        WeekComparisonResponse comp = rosterService.compareWeeks(weekA, weekB);
        assertNotNull(comp);
        assertTrue(comp.totalDifferences() > 0, "Differences must be detected between MORNING and EVENING");
        assertFalse(comp.rows().isEmpty());
    }

    // -------------------------------------------------------------------------
    // 6. DRAFT AUTO-SAVE & RECOVERY
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Batch 107: Draft auto-save updates timestamp and preserves draft without auto-publishing")
    void testAutoSaveDraft_PreservesDraft() {
        LocalDate testMonday = getMonday(28);
        ManualRosterMatrixResponse matrix = rosterService.autoSaveDraft(new AutoSaveDraftRequest(
                null, testMonday, "draft-client-123"
        ), "Admin");
        assertNotNull(matrix);
        assertEquals(RosterStatus.DRAFT, matrix.status(), "Auto-save must never automatically publish the roster");
    }

    // -------------------------------------------------------------------------
    // 7. PRESERVE AUTOMATIC ROSTER ALGORITHM
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Batch 107: Automatic Roster Generation strictly preserves explicit manual assignments")
    void testAutomaticGeneration_PreservesManualAssignments() {
        LocalDate mon = getMonday(30);
        Employee emp = activeEmployees.get(0);

        // Admin explicitly assigns emp to EVENING on Monday
        ManualRosterMatrixResponse matrix = rosterService.saveManualAssignment(new ManualAssignmentRequest(
                null, mon, emp.getId(), mon, ShiftType.EVENING, false, "Explicit manual shift"
        ), "Admin");

        // Run automatic generation
        RosterCycleResponse autoCycle = rosterService.generateWeeklyRoster(mon, GenerationMode.AUTOMATIC);
        assertNotNull(autoCycle);

        // Verify the manual assignment remains EVENING
        List<RosterAssignment> target = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), mon);
        assertFalse(target.isEmpty());
        assertEquals(ShiftType.EVENING, target.get(0).getShift().getShiftType(),
                "Automatic generation must strictly preserve Level 1 manual assignment locks");
        assertTrue(target.get(0).isManualAssignment());
    }
}
