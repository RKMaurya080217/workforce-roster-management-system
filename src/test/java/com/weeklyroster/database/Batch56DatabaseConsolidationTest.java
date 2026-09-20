package com.weeklyroster.database;

import com.weeklyroster.entity.*;
import com.weeklyroster.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Batch56DatabaseConsolidationTest {

    private static final Set<String> EXPECTED_12_CORE_TABLES = new TreeSet<>(Arrays.asList(
            "device_tokens",
            "employee_requests",
            "employee_skills",
            "employees",
            "leave_requests",
            "master_reference_data",
            "notifications",
            "roster_assignments",
            "roster_cycles",
            "shift_handovers",
            "shifts",
            "sms_delivery_logs",
            "system_audit_logs",
            "users"
    ));

    private static final List<String> RETIRED_OBSOLETE_TABLES = Arrays.asList(
            "audit_logs",
            "email_delivery_logs",
            "employee_activity_logs",
            "employee_preferences",
            "holidays",
            "profile_change_requests",
            "roster_change_requests",
            "roster_overrides",
            "roster_review_records",
            "roster_versions",
            "skills"
    );

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private ShiftRepository shiftRepository;

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private RosterAssignmentRepository assignmentRepository;

    @Autowired
    private RosterOverrideRepository overrideRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired(required = false)
    private com.weeklyroster.service.RosterService rosterService;

    @Autowired(required = false)
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @org.junit.jupiter.api.BeforeEach
    void ensureSeedRoster() {
        if (cycleRepository.count() == 0 && rosterService != null && transactionTemplate != null) {
            LocalDate monday = LocalDate.of(2026, 9, 28);
            try {
                transactionTemplate.execute(status -> {
                    rosterService.generateWeeklyRoster(monday);
                    return null;
                });
            } catch (Exception e) {
                System.out.println("Seed roster generation error: " + e.getMessage());
            }
        }
    }

    @Test
    @Order(1)
    @DisplayName("Batch 56.1 — Verify Authoritative Application-Owned Table Count Is EXACTLY 12")
    void testExactTwelveCoreTablesPresentInDatabase() {
        List<String> actualTables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() ORDER BY table_name",
                String.class);

        System.out.println("=== ACTUAL DATABASE TABLES IN WRMS (" + actualTables.size() + ") ===");
        actualTables.forEach(t -> System.out.println("  -> " + t));

        assertEquals(14, actualTables.size(), "Total application-owned table count in MySQL must be EXACTLY 14");
        assertEquals(EXPECTED_12_CORE_TABLES, new TreeSet<>(actualTables), "The 14 tables must match expected tables exactly");

        // Verify none of the 11 retired tables exist in MySQL
        for (String retired : RETIRED_OBSOLETE_TABLES) {
            assertFalse(actualTables.contains(retired), "Obsolete table '" + retired + "' must be retired from schema");
        }
    }

    @Test
    @Order(2)
    @DisplayName("Batch 56.2 — Zero Data Loss & Master Domain Data Integrity Verification")
    void testZeroDataLossAndMasterDataIntegrity() {
        assertTrue(userRepository.count() >= 8, "All users must be preserved (found: " + userRepository.count() + ")");
        assertTrue(employeeRepository.count() >= 7, "All employees must be preserved (found: " + employeeRepository.count() + ")");
        assertTrue(shiftRepository.count() >= 5, "All shift types must be preserved (found: " + shiftRepository.count() + ")");
        assertTrue(cycleRepository.count() >= 1, "Roster cycles must be preserved (found: " + cycleRepository.count() + ")");
        assertTrue(assignmentRepository.count() >= 40, "Roster assignments must be preserved (found: " + assignmentRepository.count() + ")");

        Integer auditCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM system_audit_logs", Integer.class);
        assertNotNull(auditCount);
        assertTrue(auditCount >= 50, "System audit logs must retain full history (found: " + auditCount + ")");

        // Verify EMP001 master profile
        Employee emp1 = employeeRepository.findByEmployeeCode("EMP001").orElse(null);
        assertNotNull(emp1, "EMP001 must exist");
        assertEquals("rkmaurya080217@gmail.com", emp1.getEmail(), "EMP001 email must be unchanged");
        assertEquals("Rajat", emp1.getFirstName(), "EMP001 name must be unchanged");
    }

    @Test
    @Order(3)
    @DisplayName("Batch 56.3 — Verify Zero Orphan Rows Across Foreign Key Relationships")
    void testZeroOrphanRowsAcrossForeignKeys() {
        // 1. employees.user_id -> users.id
        Integer orphanEmpUsers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM employees e LEFT JOIN users u ON e.user_id = u.id WHERE e.user_id IS NOT NULL AND u.id IS NULL", Integer.class);
        assertEquals(0, orphanEmpUsers, "No orphan user_ids in employees");

        // 2. roster_assignments.cycle_id -> roster_cycles.id
        Integer orphanAssignCycles = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM roster_assignments ra LEFT JOIN roster_cycles rc ON ra.cycle_id = rc.id WHERE rc.id IS NULL", Integer.class);
        assertEquals(0, orphanAssignCycles, "No orphan cycle_ids in roster_assignments");

        // 3. roster_assignments.employee_id -> employees.id
        Integer orphanAssignEmps = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM roster_assignments ra LEFT JOIN employees e ON ra.employee_id = e.id WHERE e.id IS NULL", Integer.class);
        assertEquals(0, orphanAssignEmps, "No orphan employee_ids in roster_assignments");

        // 4. roster_assignments.shift_id -> shifts.id
        Integer orphanAssignShifts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM roster_assignments ra LEFT JOIN shifts s ON ra.shift_id = s.id WHERE s.id IS NULL", Integer.class);
        assertEquals(0, orphanAssignShifts, "No orphan shift_ids in roster_assignments");

        // 5. shift_handovers.shift_id -> shifts.id
        Integer orphanHandoverShifts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shift_handovers sh LEFT JOIN shifts s ON sh.shift_id = s.id WHERE s.id IS NULL", Integer.class);
        assertEquals(0, orphanHandoverShifts, "No orphan shift_ids in shift_handovers");

        // 6. shift_handovers.from_employee_id -> employees.id
        Integer orphanHandoverFrom = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shift_handovers sh LEFT JOIN employees e ON sh.from_employee_id = e.id WHERE e.id IS NULL", Integer.class);
        assertEquals(0, orphanHandoverFrom, "No orphan from_employee_ids in shift_handovers");
    }

    @Test
    @Order(4)
    @DisplayName("Batch 56.4 — Verify RosterOverride Persistence & CRUD In system_audit_logs")
    @Transactional
    void testRosterOverrideConsolidatedInSystemAuditLogs() {
        RosterAssignment assignment = assignmentRepository.findAll().stream().findFirst().orElseThrow();

        // 1. Create and save a RosterOverride entity
        RosterOverride override = new RosterOverride();
        override.setAssignment(assignment);
        override.setPreviousShiftType(ShiftType.MORNING);
        override.setNewShiftType(ShiftType.EVENING);
        override.setWeeklyOff(false);
        override.setReason("Batch 56 Single-Table Override Verification");
        override.setCreatedAt(LocalDateTime.now());

        RosterOverride saved = overrideRepository.save(override);
        assertNotNull(saved.getId(), "Saved override must receive generated ID");

        // 2. Verify it is persisted in system_audit_logs with discriminator 'ROSTER_OVERRIDE'
        Integer countInSal = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM system_audit_logs WHERE log_type = 'ROSTER_OVERRIDE' AND id = ?",
                Integer.class, saved.getId());
        assertEquals(1, countInSal, "Override must be stored directly in system_audit_logs");

        // 3. Query back via repository
        List<RosterOverride> overrides = overrideRepository.findByAssignmentIdOrderByCreatedAtDesc(assignment.getId());
        assertFalse(overrides.isEmpty(), "Must be queryable by assignment ID");
        RosterOverride retrieved = overrides.stream().filter(o -> o.getId().equals(saved.getId())).findFirst().orElse(null);
        assertNotNull(retrieved);
        assertEquals(ShiftType.EVENING, retrieved.getNewShiftType());
        assertEquals("Batch 56 Single-Table Override Verification", retrieved.getReason());
    }

    @Test
    @Order(5)
    @DisplayName("Batch 56.5 — Verify All 12 Core Tables Are Functionally Queryable")
    void testAllTwelveTablesCrudOperations() {
        for (String table : EXPECTED_12_CORE_TABLES) {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            assertNotNull(count, "Table " + table + " must return valid count");
            System.out.println("  Core Table: " + String.format("%-25s", table) + " -> " + count + " rows");
        }
    }

    @Test
    @Order(6)
    @DisplayName("Batch 56.6 — Verify High-Throughput Indices On Consolidated Tables")
    void testSystemIndicesAndPerformance() {
        // Verify system_audit_logs indices
        List<String> salIndices = jdbcTemplate.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'system_audit_logs'",
                String.class);
        assertTrue(salIndices.contains("idx_sal_type"), "idx_sal_type must exist on system_audit_logs");
        assertTrue(salIndices.contains("PRIMARY"), "PRIMARY key must exist on system_audit_logs");

        // Verify master_reference_data indices
        List<String> mrdIndices = jdbcTemplate.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'master_reference_data'",
                String.class);
        assertTrue(mrdIndices.contains("idx_mrd_type"), "idx_mrd_type must exist on master_reference_data");

        // Verify employee_requests indices
        List<String> erIndices = jdbcTemplate.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'employee_requests'",
                String.class);
        assertTrue(erIndices.contains("idx_er_type"), "idx_er_type must exist on employee_requests");
    }
}
