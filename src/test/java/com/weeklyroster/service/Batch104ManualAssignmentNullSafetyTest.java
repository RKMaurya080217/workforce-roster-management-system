package com.weeklyroster.service;

import com.weeklyroster.dto.request.ManualAssignmentRequest;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.GenerationMode;
import com.weeklyroster.entity.RosterAssignment;
import com.weeklyroster.entity.RosterCycle;
import com.weeklyroster.entity.Shift;
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.RosterAssignmentRepository;
import com.weeklyroster.repository.RosterCycleRepository;
import com.weeklyroster.repository.ShiftRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
@TestPropertySource(properties = {"wrms.dev.credential-mirror.enabled=false"})
public class Batch104ManualAssignmentNullSafetyTest {

    @Autowired
    private RosterAssignmentRepository rosterAssignmentRepository;

    @Autowired
    private RosterCycleRepository rosterCycleRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private RosterService rosterService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("Case 1: Entity safely converts null manualAssignment and assignmentSource in memory and postLoad")
    public void testEntityNullSafety() {
        RosterAssignment assignment = new RosterAssignment();
        // Default values
        assertFalse(assignment.isManualAssignment());
        assertEquals(Boolean.FALSE, assignment.getManualAssignment());
        assertEquals("AUTOMATIC", assignment.getAssignmentSource());

        // Explicit null setter
        assignment.setManualAssignment((Boolean) null);
        assertFalse(assignment.isManualAssignment(), "Null manualAssignment should evaluate to false");
        assertEquals(Boolean.FALSE, assignment.getManualAssignment());

        assignment.setAssignmentSource(null);
        assertEquals("AUTOMATIC", assignment.getAssignmentSource(), "Null assignmentSource should evaluate to AUTOMATIC");

        // Primitive boolean setter
        assignment.setManualAssignment(true);
        assertTrue(assignment.isManualAssignment());
        assertEquals(Boolean.TRUE, assignment.getManualAssignment());
    }

    @Test
    @DisplayName("Case 1b: Database records with legacy NULL manual_assignment load without JpaSystemException")
    public void testDatabaseNullHydrationDoesNotThrow() {
        List<Employee> emps = employeeRepository.findAll();
        assertFalse(emps.isEmpty());
        Employee emp = emps.get(0);

        Shift shift = shiftRepository.findByShiftType(ShiftType.MORNING)
                .orElseGet(() -> shiftRepository.findAll().get(0));

        LocalDate monday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        RosterCycle cycle = rosterService.getOrCreateDraftCycle(monday, 6);

        LocalDate testDate = monday.plusDays(3);
        
        // Create an assignment via repository first
        RosterAssignment assignment = new RosterAssignment();
        assignment.setCycle(cycle);
        assignment.setEmployee(emp);
        assignment.setShift(shift);
        assignment.setRosterDate(testDate);
        assignment.setWeeklyOff(false);
        assignment.setOnLeave(false);
        assignment.setOverridden(false);
        assignment.setManualAssignment(false);
        assignment.setAssignmentSource("AUTOMATIC");

        RosterAssignment saved = rosterAssignmentRepository.saveAndFlush(assignment);
        Long savedId = saved.getId();
        assertNotNull(savedId);

        // Directly set manual_assignment and assignment_source to NULL in SQLite database (simulating legacy data)
        jdbcTemplate.update("UPDATE roster_assignments SET manual_assignment = NULL, assignment_source = NULL WHERE id = ?", savedId);

        // Clear 1st-level cache so Hibernate MUST re-hydrate from database ResultSet
        entityManager.clear();

        // Hydrate through Spring Data JPA repository (This previously caused JpaSystemException)
        RosterAssignment loaded = rosterAssignmentRepository.findById(savedId).orElse(null);
        assertNotNull(loaded, "Should successfully load row with NULL manual_assignment from DB");

        // Verify it was treated as false and AUTOMATIC
        assertFalse(loaded.isManualAssignment(), "NULL manual_assignment in DB must be treated as false");
        assertEquals(Boolean.FALSE, loaded.getManualAssignment());
        assertEquals("AUTOMATIC", loaded.getAssignmentSource(), "NULL assignment_source in DB must be treated as AUTOMATIC");
    }

    @Test
    @DisplayName("Case 2: Automatically generated assignments have manualAssignment = false")
    public void testAutomaticAssignmentHasManualAssignmentFalse() {
        LocalDate monday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusWeeks(2);
        rosterService.generateWeeklyRoster(monday, GenerationMode.AUTOMATIC);

        List<RosterAssignment> assignments = rosterAssignmentRepository.findByRosterDateBetweenWithDetails(monday, monday.plusDays(6));
        assertFalse(assignments.isEmpty());

        for (RosterAssignment a : assignments) {
            assertFalse(a.isManualAssignment(), "Automatic generation must set manualAssignment = false");
            assertEquals("AUTOMATIC", a.getAssignmentSource());
        }
    }

    @Test
    @DisplayName("Case 3: Admin explicit manual assignment sets manualAssignment = true")
    public void testAdminManualAssignmentSetsTrue() {
        List<Employee> emps = employeeRepository.findAll();
        Employee emp = emps.get(0);

        LocalDate monday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusWeeks(3);
        LocalDate targetDate = monday.plusDays(1); // Tuesday

        RosterCycle cycle = rosterService.getOrCreateDraftCycle(monday, 6);
        ManualAssignmentRequest req = new ManualAssignmentRequest(
                cycle.getId(),
                monday,
                emp.getId(),
                targetDate,
                ShiftType.EVENING,
                false,
                "Admin manual assignment test"
        );

        rosterService.saveManualAssignment(req, "admin");

        List<RosterAssignment> list = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), targetDate);
        assertFalse(list.isEmpty());
        RosterAssignment saved = list.get(0);

        assertTrue(saved.isManualAssignment(), "Explicit assignment must have manualAssignment = true");
        assertEquals("MANUAL", saved.getAssignmentSource());
        assertEquals(ShiftType.EVENING, saved.getShift().getShiftType());
    }

    @Test
    @DisplayName("Case 4: Strict assignment rule - Automatic generation does not overwrite explicit manual assignment")
    public void testStrictAssignmentNotOverwrittenByGeneration() {
        List<Employee> emps = employeeRepository.findAll();
        Employee emp = emps.get(0);

        LocalDate monday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusWeeks(4);
        LocalDate targetDate = monday.plusDays(5); // Saturday

        RosterCycle cycle = rosterService.getOrCreateDraftCycle(monday, 6);
        rosterService.saveManualAssignment(new ManualAssignmentRequest(
                cycle.getId(),
                monday,
                emp.getId(),
                targetDate,
                ShiftType.MORNING,
                false,
                "Strict test manual assignment"
        ), "admin");

        // Run automated generation for this week
        rosterService.generateWeeklyRoster(monday, GenerationMode.AUTOMATIC);

        List<RosterAssignment> list = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), targetDate);
        assertFalse(list.isEmpty());
        RosterAssignment finalAssign = list.get(0);

        assertEquals(ShiftType.MORNING, finalAssign.getShift().getShiftType(), "Rajat's Saturday Morning MUST NOT be changed");
        assertTrue(finalAssign.isManualAssignment(), "Must remain marked as manual assignment");
    }
}
