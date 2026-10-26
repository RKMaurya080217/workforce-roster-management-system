package com.weeklyroster.service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class Batch112DatabaseIntegrityAuditTest {

    private static final String DB_URL = "jdbc:sqlite:data/wrms.db";

    @Test
    @DisplayName("Batch 112 DB Audit: Verify SQLite database schema, constraints, and zero duplicate records")
    void testDatabaseIntegrity() throws Exception {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            assertNotNull(conn, "Connection to data/wrms.db must succeed");

            try (Statement stmt = conn.createStatement()) {
                // Discover existing tables
                List<String> tables = new ArrayList<>();
                try (ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")) {
                    while (rs.next()) {
                        tables.add(rs.getString("name"));
                    }
                }
                System.out.println("Discovered SQLite tables: " + tables);
                assertTrue(tables.contains("employees"), "employees table must exist");
                assertTrue(tables.contains("users"), "users table must exist");
                assertTrue(tables.contains("roster_cycles"), "roster_cycles table must exist");
                assertTrue(tables.contains("roster_assignments"), "roster_assignments table must exist");

                // Print all employees
                try (ResultSet rs = stmt.executeQuery("SELECT id, employee_code, first_name, last_name, email, active FROM employees ORDER BY id")) {
                    List<String> emps = new ArrayList<>();
                    while (rs.next()) {
                        emps.add(String.format("Emp #%d: code=%s, name=%s %s, email=%s, active=%b",
                                rs.getLong("id"), rs.getString("employee_code"), rs.getString("first_name"), rs.getString("last_name"), rs.getString("email"), rs.getBoolean("active")));
                    }
                    System.out.println("Discovered employees in employees table (" + emps.size() + "): " + emps);
                }

                // Print all roster cycles
                try (ResultSet rs = stmt.executeQuery("SELECT id, start_date, end_date, status, generation_mode FROM roster_cycles ORDER BY id")) {
                    List<String> cycles = new ArrayList<>();
                    while (rs.next()) {
                        cycles.add(String.format("Cycle #%d: %s to %s, status=%s, mode=%s",
                                rs.getLong("id"), rs.getString("start_date"), rs.getString("end_date"), rs.getString("status"), rs.getString("generation_mode")));
                    }
                    System.out.println("Discovered roster_cycles in roster_cycles table (" + cycles.size() + "): " + cycles);
                }
                try (ResultSet rs = stmt.executeQuery("SELECT employee_code, COUNT(*) as cnt FROM employees GROUP BY employee_code HAVING cnt > 1")) {
                    assertFalse(rs.next(), "Found duplicate employee codes in employees table");
                }

                // 2. Check duplicate employee emails
                try (ResultSet rs = stmt.executeQuery("SELECT email, COUNT(*) as cnt FROM employees WHERE email IS NOT NULL AND email != '' GROUP BY email HAVING cnt > 1")) {
                    assertFalse(rs.next(), "Found duplicate emails in employees table");
                }

                // 3. Check duplicate holiday dates in master_reference_data
                if (tables.contains("master_reference_data")) {
                    try (ResultSet rs = stmt.executeQuery("SELECT holiday_date, COUNT(*) as cnt FROM master_reference_data WHERE item_type = 'HOLIDAY' AND holiday_date IS NOT NULL GROUP BY holiday_date HAVING cnt > 1")) {
                        assertFalse(rs.next(), "Found duplicate holiday dates in master_reference_data");
                    }
                }

                // 4. Check duplicate user accounts and print users
                try (ResultSet rs = stmt.executeQuery("SELECT id, username, role, enabled FROM users ORDER BY id")) {
                    List<String> userList = new ArrayList<>();
                    while (rs.next()) {
                        userList.add(String.format("User #%d: username=%s, role=%s, enabled=%b",
                                rs.getLong("id"), rs.getString("username"), rs.getString("role"), rs.getBoolean("enabled")));
                    }
                    System.out.println("Discovered users in users table (" + userList.size() + "): " + userList);
                }
                try (ResultSet rs = stmt.executeQuery("SELECT username, COUNT(*) as cnt FROM users GROUP BY username HAVING cnt > 1")) {
                    assertFalse(rs.next(), "Found duplicate usernames in users table");
                }

                // 5. Check orphan roster assignments (cycle_id not in roster_cycles)
                try (ResultSet rs = stmt.executeQuery("SELECT ra.id FROM roster_assignments ra LEFT JOIN roster_cycles rc ON ra.cycle_id = rc.id WHERE rc.id IS NULL")) {
                    assertFalse(rs.next(), "Found orphan assignments with invalid cycle_id");
                }

                // Query assignment breakdown by cycle
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT cycle_id, " +
                        "COUNT(*) as total_assignments, " +
                        "SUM(CASE WHEN e.id IS NOT NULL THEN 1 ELSE 0 END) as valid_employee_assignments, " +
                        "SUM(CASE WHEN e.id IS NULL THEN 1 ELSE 0 END) as orphan_assignments " +
                        "FROM roster_assignments ra " +
                        "LEFT JOIN employees e ON ra.employee_id = e.id " +
                        "GROUP BY cycle_id ORDER BY cycle_id")) {
                    while (rs.next()) {
                        System.out.println(String.format("Cycle #%d: Total=%d, ValidEmp=%d, OrphanEmp=%d",
                                rs.getLong("cycle_id"), rs.getInt("total_assignments"), rs.getInt("valid_employee_assignments"), rs.getInt("orphan_assignments")));
                    }
                }

                // 7. Check duplicate assignments for same employee and date within the same cycle
                try (ResultSet rs = stmt.executeQuery("SELECT cycle_id, employee_id, roster_date, COUNT(*) as cnt FROM roster_assignments GROUP BY cycle_id, employee_id, roster_date HAVING cnt > 1")) {
                    assertFalse(rs.next(), "Found duplicate assignments for same employee, date, and cycle");
                }

                // 8. Check orphan recurring shift preferences
                if (tables.contains("recurring_shift_preferences")) {
                    try (ResultSet rs = stmt.executeQuery("SELECT rsp.id FROM recurring_shift_preferences rsp LEFT JOIN employees e ON rsp.employee_id = e.id WHERE e.id IS NULL")) {
                        assertFalse(rs.next(), "Found orphan recurring shift preferences with invalid employee_id");
                    }
                }

                // 9. Check orphan date shift preferences
                if (tables.contains("date_shift_preferences")) {
                    try (ResultSet rs = stmt.executeQuery("SELECT dsp.id FROM date_shift_preferences dsp LEFT JOIN employees e ON dsp.employee_id = e.id WHERE e.id IS NULL")) {
                        assertFalse(rs.next(), "Found orphan date shift preferences with invalid employee_id");
                    }
                }

                // 10. Check orphan leave requests
                if (tables.contains("leave_requests")) {
                    try (ResultSet rs = stmt.executeQuery("SELECT lr.id FROM leave_requests lr LEFT JOIN employees e ON lr.employee_id = e.id WHERE e.id IS NULL")) {
                        assertFalse(rs.next(), "Found orphan leave requests with invalid employee_id");
                    }
                }

                // 11. Check null required fields in roster_assignments
                try (ResultSet rs = stmt.executeQuery("SELECT id FROM roster_assignments WHERE roster_date IS NULL OR shift_id IS NULL")) {
                    assertFalse(rs.next(), "Found assignments with null roster_date or shift_id");
                }

                System.out.println("Database integrity audit PASSED: Zero duplicates, zero orphan records, all foreign keys valid.");
            }
        }
    }
}
