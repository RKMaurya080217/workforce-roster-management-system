package com.weeklyroster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.weeklyroster.dto.request.BulkManualAssignmentRequest;
import com.weeklyroster.dto.request.ManualAssignmentRequest;
import com.weeklyroster.dto.response.ManualRosterMatrixResponse;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.LeaveRequestRepository;
import com.weeklyroster.repository.RosterAssignmentRepository;
import com.weeklyroster.repository.RosterCycleRepository;
import com.weeklyroster.repository.ShiftRepository;
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

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "wrms.dev.credential-mirror.enabled=false",
        "spring.datasource.url=jdbc:sqlite:target/test-wrms.db?busy_timeout=30000&journal_mode=WAL"
})
public class Batch106ManualRosterAndUxTest {

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
                System.out.println("[Batch 106] Isolated test replica created at target/test-wrms.db");
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

    private ObjectMapper objectMapper;
    private LocalDate nextMonday;
    private List<Employee> activeEmployees;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        nextMonday = LocalDate.now().plusWeeks(3).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        activeEmployees = employeeRepository.findByActiveTrueOrderByIdAsc();
        assertFalse(activeEmployees.isEmpty(), "Active employees must exist for testing");
    }

    // -------------------------------------------------------------------------
    // 1. JSON DESERIALIZATION AND ROOT CAUSE REGRESSION PROOFING
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Batch 106: BulkManualAssignmentRequest properly deserializes from clean JSON object")
    void testBulkAssignmentRequestDeserialization_CleanObject() throws Exception {
        String cleanJson = """
            {
              "cycleId": 12,
              "startDate": "2026-11-09",
              "employeeIds": [1, 2, 3],
              "dates": ["2026-11-09", "2026-11-10"],
              "shiftType": "MORNING",
              "weeklyOff": false,
              "reason": "Sprint Coverage"
            }
            """;

        BulkManualAssignmentRequest req = objectMapper.readValue(cleanJson, BulkManualAssignmentRequest.class);
        assertNotNull(req);
        assertEquals(12L, req.cycleId());
        assertEquals(LocalDate.of(2026, 11, 9), req.startDate());
        assertEquals(3, req.employeeIds().size());
        assertEquals(2, req.dates().size());
        assertEquals(ShiftType.MORNING, req.shiftType());
        assertFalse(req.weeklyOff());
        assertEquals("Sprint Coverage", req.reason());
    }

    @Test
    @DisplayName("Batch 106: BulkManualAssignmentRequest deserialization handles null cycleId, null reason, and OFF shift")
    void testBulkAssignmentRequestDeserialization_NullAndOffFields() throws Exception {
        String json = """
            {
              "cycleId": null,
              "startDate": "2026-11-09",
              "employeeIds": [5],
              "dates": ["2026-11-09"],
              "shiftType": "OFF",
              "weeklyOff": true,
              "reason": null
            }
            """;

        BulkManualAssignmentRequest req = objectMapper.readValue(json, BulkManualAssignmentRequest.class);
        assertNotNull(req);
        assertNull(req.cycleId());
        assertEquals(ShiftType.OFF, req.shiftType());
        assertTrue(req.weeklyOff());
        assertNull(req.reason());
    }

    @Test
    @DisplayName("Batch 106: Root Cause Proof - Double-stringified JSON payload fails Jackson record deserialization")
    void testRootCause_DoubleStringifiedJsonFailsDeserialization() {
        String innerJson = "{\"cycleId\":null,\"startDate\":\"2026-11-09\",\"employeeIds\":[1],\"dates\":[\"2026-11-09\"],\"shiftType\":\"MORNING\",\"weeklyOff\":false,\"reason\":null}";
        String doubleStringified = objectMapper.valueToTree(innerJson).toString(); // "\"{\\\"cycleId\\\":null,...}\""

        assertThrows(MismatchedInputException.class, () -> {
            objectMapper.readValue(doubleStringified, BulkManualAssignmentRequest.class);
        }, "Double stringified JSON literal must fail deserialization with MismatchedInputException");
    }

    // -------------------------------------------------------------------------
    // 2. BULK ASSIGNMENT COMBINATIONS (EMPLOYEE / DATE COMBINATIONS)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Batch 106: Bulk Shift Assignment with 1 Employee and 1 Day")
    void testBulkAssignment_OneEmployeeOneDay() {
        Employee emp = activeEmployees.get(0);
        LocalDate date = nextMonday;

        BulkManualAssignmentRequest req = new BulkManualAssignmentRequest(
                null,
                nextMonday,
                List.of(emp.getId()),
                List.of(date),
                ShiftType.MORNING,
                false,
                "Single Day Assignment"
        );

        ManualRosterMatrixResponse response = rosterService.bulkAssignManual(req, "AdminTester");
        assertNotNull(response);
        assertNotNull(response.cycleId());

        List<RosterAssignment> saved = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), date);
        assertEquals(1, saved.size(), "Exactly one assignment must exist");
        RosterAssignment a = saved.get(0);
        assertTrue(a.isManualAssignment());
        assertTrue(a.isOverridden());
        assertEquals("MANUAL", a.getAssignmentSource());
        assertEquals(ShiftType.MORNING, a.getShift().getShiftType());
        assertEquals("Single Day Assignment", a.getAssignmentReason());
    }

    @Test
    @DisplayName("Batch 106: Bulk Shift Assignment with Multiple Employees and 1 Day")
    void testBulkAssignment_MultipleEmployeesOneDay() {
        int count = Math.min(3, activeEmployees.size());
        List<Long> empIds = activeEmployees.subList(0, count).stream().map(Employee::getId).toList();
        LocalDate date = nextMonday.plusDays(1); // Tuesday

        BulkManualAssignmentRequest req = new BulkManualAssignmentRequest(
                null,
                nextMonday,
                empIds,
                List.of(date),
                ShiftType.GENERAL,
                false,
                "Team General Shift"
        );

        ManualRosterMatrixResponse response = rosterService.bulkAssignManual(req, "AdminTester");
        assertNotNull(response);

        for (Long empId : empIds) {
            List<RosterAssignment> saved = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(empId, date);
            assertEquals(1, saved.size());
            assertEquals(ShiftType.GENERAL, saved.get(0).getShift().getShiftType());
            assertTrue(saved.get(0).isManualAssignment());
        }
    }

    @Test
    @DisplayName("Batch 106: Bulk Shift Assignment with 1 Employee and Multiple Days (Mon-Fri Weekdays)")
    void testBulkAssignment_OneEmployeeMultipleDays() {
        Employee emp = activeEmployees.get(0);
        List<LocalDate> weekdays = List.of(
                nextMonday,
                nextMonday.plusDays(1),
                nextMonday.plusDays(2),
                nextMonday.plusDays(3),
                nextMonday.plusDays(4)
        );

        BulkManualAssignmentRequest req = new BulkManualAssignmentRequest(
                null,
                nextMonday,
                List.of(emp.getId()),
                weekdays,
                ShiftType.EVENING,
                false,
                "Weekday Evening Focus"
        );

        ManualRosterMatrixResponse response = rosterService.bulkAssignManual(req, "AdminTester");
        assertNotNull(response);

        for (LocalDate d : weekdays) {
            List<RosterAssignment> saved = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), d);
            assertEquals(1, saved.size());
            assertEquals(ShiftType.EVENING, saved.get(0).getShift().getShiftType());
            assertTrue(saved.get(0).isManualAssignment());
        }
    }

    @Test
    @DisplayName("Batch 106: Bulk Shift Assignment with Multiple Employees and Multiple Days")
    void testBulkAssignment_MultipleEmployeesMultipleDays() {
        int count = Math.min(2, activeEmployees.size());
        List<Long> empIds = activeEmployees.subList(0, count).stream().map(Employee::getId).toList();
        List<LocalDate> dates = List.of(nextMonday.plusDays(1), nextMonday.plusDays(2));

        BulkManualAssignmentRequest req = new BulkManualAssignmentRequest(
                null,
                nextMonday,
                empIds,
                dates,
                ShiftType.MORNING,
                false,
                "Multi-emp multi-day coverage"
        );

        ManualRosterMatrixResponse response = rosterService.bulkAssignManual(req, "AdminTester");
        assertNotNull(response);

        for (Long empId : empIds) {
            for (LocalDate d : dates) {
                List<RosterAssignment> saved = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(empId, d);
                assertEquals(1, saved.size());
                assertEquals(ShiftType.MORNING, saved.get(0).getShift().getShiftType());
                assertTrue(saved.get(0).isManualAssignment());
            }
        }
    }

    @Test
    @DisplayName("Batch 106: Bulk Shift Assignment with Weekend Days (Saturday & Sunday)")
    void testBulkAssignment_WeekendDays() {
        Employee emp = activeEmployees.get(0);
        LocalDate sat = nextMonday.plusDays(5);
        LocalDate sun = nextMonday.plusDays(6);

        BulkManualAssignmentRequest req = new BulkManualAssignmentRequest(
                null,
                nextMonday,
                List.of(emp.getId()),
                List.of(sat, sun),
                ShiftType.OFF,
                true,
                "Weekend Off Override"
        );

        ManualRosterMatrixResponse response = rosterService.bulkAssignManual(req, "AdminTester");
        assertNotNull(response);

        for (LocalDate d : List.of(sat, sun)) {
            List<RosterAssignment> saved = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), d);
            assertEquals(1, saved.size());
            assertTrue(saved.get(0).isWeeklyOff());
            assertTrue(saved.get(0).isManualAssignment());
        }
    }

    @Test
    @DisplayName("Batch 106: Optional and Empty Remarks default gracefully without failure")
    void testBulkAssignment_OptionalAndEmptyRemarks() {
        Employee emp = activeEmployees.get(0);
        LocalDate date = nextMonday.plusDays(3);

        BulkManualAssignmentRequest reqNullRemark = new BulkManualAssignmentRequest(
                null,
                nextMonday,
                List.of(emp.getId()),
                List.of(date),
                ShiftType.GENERAL,
                false,
                null
        );

        ManualRosterMatrixResponse res1 = rosterService.bulkAssignManual(reqNullRemark, "AdminTester");
        assertNotNull(res1);

        List<RosterAssignment> saved = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), date);
        assertEquals(1, saved.size());
        assertTrue(saved.get(0).getAssignmentReason().contains("Bulk Manual Assignment"));

        // Now test empty string remark
        BulkManualAssignmentRequest reqEmptyRemark = new BulkManualAssignmentRequest(
                res1.cycleId(),
                nextMonday,
                List.of(emp.getId()),
                List.of(date),
                ShiftType.MORNING,
                false,
                "   "
        );

        ManualRosterMatrixResponse res2 = rosterService.bulkAssignManual(reqEmptyRemark, "AdminTester");
        assertNotNull(res2);

        saved = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), date);
        assertEquals(1, saved.size());
        assertEquals(ShiftType.MORNING, saved.get(0).getShift().getShiftType());
    }

    // -------------------------------------------------------------------------
    // 3. VALIDATION, LOCK PROTECTION AND DUPLICATE CLEANUP
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Batch 106: Validation rejects empty dates or empty employeeIds")
    void testBulkAssignment_ValidationRejections() {
        assertThrows(BusinessException.class, () -> {
            rosterService.bulkAssignManual(new BulkManualAssignmentRequest(
                    null, nextMonday, List.of(), List.of(nextMonday), ShiftType.MORNING, false, null
            ), "Admin");
        });

        assertThrows(BusinessException.class, () -> {
            rosterService.bulkAssignManual(new BulkManualAssignmentRequest(
                    null, nextMonday, List.of(1L), List.of(), ShiftType.MORNING, false, null
            ), "Admin");
        });
    }

    @Test
    @DisplayName("Batch 106: Cannot modify assignments in a LOCKED roster cycle")
    void testBulkAssignment_LockedCycleProtection() {
        RosterCycle cycle = new RosterCycle();
        cycle.setStartDate(nextMonday.plusWeeks(2));
        cycle.setEndDate(nextMonday.plusWeeks(2).plusDays(6));
        cycle.setStatus(RosterStatus.LOCKED);
        cycle.setGeneratedAt(java.time.LocalDateTime.now());
        RosterCycle savedCycle = rosterCycleRepository.save(cycle);

        final Long cycleId = savedCycle.getId();
        final LocalDate cycleStart = savedCycle.getStartDate();
        assertThrows(BusinessException.class, () -> {
            rosterService.bulkAssignManual(new BulkManualAssignmentRequest(
                    cycleId,
                    cycleStart,
                    List.of(activeEmployees.get(0).getId()),
                    List.of(cycleStart),
                    ShiftType.MORNING,
                    false,
                    "Attempt edit on locked cycle"
            ), "Admin");
        });
    }

    @Test
    @DisplayName("Batch 106: Duplicate assignments are cleaned up so exactly one exists per employee/date")
    void testBulkAssignment_GuaranteesExactlyOneAssignment() {
        Employee emp = activeEmployees.get(0);
        LocalDate date = nextMonday.plusDays(4);

        // Pre-create a draft cycle
        RosterCycle cycle = rosterService.getOrCreateDraftCycle(nextMonday, 6);
        Shift offShift = shiftRepository.findByShiftType(ShiftType.OFF).orElseGet(() -> shiftRepository.findAll().get(0));

        // Insert two duplicate records manually
        RosterAssignment dup1 = new RosterAssignment();
        dup1.setCycle(cycle);
        dup1.setEmployee(emp);
        dup1.setRosterDate(date);
        dup1.setWeeklyOff(true);
        dup1.setShift(offShift);
        rosterAssignmentRepository.save(dup1);

        RosterAssignment dup2 = new RosterAssignment();
        dup2.setCycle(cycle);
        dup2.setEmployee(emp);
        dup2.setRosterDate(date);
        dup2.setWeeklyOff(false);
        dup2.setShift(offShift);
        rosterAssignmentRepository.save(dup2);

        assertTrue(rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), date).size() >= 2);

        // Perform bulk assign
        rosterService.bulkAssignManual(new BulkManualAssignmentRequest(
                cycle.getId(),
                nextMonday,
                List.of(emp.getId()),
                List.of(date),
                ShiftType.MORNING,
                false,
                "Deduplication test"
        ), "Admin");

        List<RosterAssignment> cleaned = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), date);
        assertEquals(1, cleaned.size(), "Duplicate rows must be removed; exactly one row must remain");
        assertEquals(ShiftType.MORNING, cleaned.get(0).getShift().getShiftType());
    }

    // -------------------------------------------------------------------------
    // 4. REGRESSION-PROOF AUTOMATIC GENERATION & MANUAL OVERWRITE PROTECTION
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Batch 106: Explicit manual assignments are strictly protected against automatic roster overwrite")
    void testAutomaticGenerationDoesNotOverwriteManualAssignment() {
        Employee emp = activeEmployees.get(0);
        LocalDate targetDate = nextMonday.plusDays(2); // Wednesday

        // 1. Manually assign NIGHT shift
        rosterService.saveManualAssignment(new ManualAssignmentRequest(
                null,
                nextMonday,
                emp.getId(),
                targetDate,
                ShiftType.NIGHT,
                false,
                "Strict Level 1 Lock Test"
        ), "Admin");

        List<RosterAssignment> manualSaved = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), targetDate);
        assertEquals(1, manualSaved.size());
        assertTrue(manualSaved.get(0).isManualAssignment());
        assertEquals(ShiftType.NIGHT, manualSaved.get(0).getShift().getShiftType());

        // 2. Trigger automatic generation for the same week
        RosterCycleResponse genResponse = rosterService.generateWeeklyRoster(nextMonday, GenerationMode.MANUAL, 6);
        assertNotNull(genResponse);

        // 3. Verify that the employee's Wednesday assignment is STILL NIGHT and STILL marked MANUAL
        List<RosterAssignment> postGen = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), targetDate);
        assertEquals(1, postGen.size());
        RosterAssignment afterGen = postGen.get(0);
        assertTrue(afterGen.isManualAssignment(), "Manual lock must persist after automatic generation");
        assertEquals(ShiftType.NIGHT, afterGen.getShift().getShiftType(), "Manual shift type must NOT be overwritten");
        assertEquals("MANUAL", afterGen.getAssignmentSource());
    }

    @Test
    @DisplayName("Batch 106: Single cell manual assignment and clear manual lock workflow")
    void testSingleCellManualAssignmentAndClear() {
        Employee emp = activeEmployees.get(0);
        LocalDate date = nextMonday.plusDays(1);

        // Assign
        ManualRosterMatrixResponse res1 = rosterService.saveManualAssignment(new ManualAssignmentRequest(
                null, nextMonday, emp.getId(), date, ShiftType.MORNING, false, "Individual cell edit"
        ), "Admin");
        assertNotNull(res1);

        RosterAssignment assigned = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), date).get(0);
        assertTrue(assigned.isManualAssignment());
        assertEquals(ShiftType.MORNING, assigned.getShift().getShiftType());

        // Clear
        ManualRosterMatrixResponse res2 = rosterService.clearManualAssignment(res1.cycleId(), emp.getId(), date, "Admin");
        assertNotNull(res2);

        RosterAssignment cleared = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), date).get(0);
        assertFalse(cleared.isManualAssignment(), "Manual assignment flag must be cleared");
        assertEquals("AUTOMATIC", cleared.getAssignmentSource());
    }

    @Test
    @DisplayName("Batch 106: Matrix Response accurately calculates validation status, conflicts and daily counts")
    void testMatrixResponse_ValidationStatusAndMetrics() {
        Employee maleEmp = activeEmployees.stream().filter(e -> e.getGender() == Gender.MALE).findFirst().orElse(activeEmployees.get(0));

        // Assign 12-hour rest violation intentionally: Night on Monday, Morning on Tuesday
        rosterService.bulkAssignManual(new BulkManualAssignmentRequest(
                null, nextMonday, List.of(maleEmp.getId()), List.of(nextMonday), ShiftType.NIGHT, false, "Night duty"
        ), "Admin");

        ManualRosterMatrixResponse matrix = rosterService.bulkAssignManual(new BulkManualAssignmentRequest(
                null, nextMonday, List.of(maleEmp.getId()), List.of(nextMonday.plusDays(1)), ShiftType.MORNING, false, "Morning duty"
        ), "Admin");

        assertNotNull(matrix);
        assertTrue(matrix.criticalConflicts() > 0, "12-hour rest violation must be flagged as critical conflict");
        assertFalse(matrix.validationWarnings().isEmpty(), "Validation warnings must detail the rest violation");
        assertTrue(matrix.dailyTotalWorking().get(nextMonday) >= 1L);
    }
}
