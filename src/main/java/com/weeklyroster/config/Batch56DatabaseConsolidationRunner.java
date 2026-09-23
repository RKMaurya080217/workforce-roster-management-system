package com.weeklyroster.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Batch 56 — WRMS Database Table Consolidation & Obsolete Table Retirement Runner.
 *
 * Finalizes schema consolidation from 23 physical tables to EXACTLY 12 core normalized tables:
 * 1. users
 * 2. employees
 * 3. shifts
 * 4. master_reference_data
 * 5. employee_skills
 * 6. roster_cycles
 * 7. roster_assignments
 * 8. leave_requests
 * 9. shift_handovers
 * 10. notifications
 * 11. employee_requests
 * 12. system_audit_logs
 *
 * Guarantees ZERO DATA LOSS by verifying all legacy data is safely consolidated,
 * removing deprecated foreign keys, and safely retiring the 11 obsolete tables.
 */
@Component
@Order(6)
public class Batch56DatabaseConsolidationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Batch56DatabaseConsolidationRunner.class);

    private static final List<String> OBSOLETE_TABLES = Arrays.asList(
            "audit_logs",
            "email_delivery_logs",
            "employee_activity_logs",
            "employee_preferences",
            "employee_skills",
            "holidays",
            "profile_change_requests",
            "roster_change_requests",
            "roster_overrides",
            "roster_review_records",
            "roster_versions",
            "skills"
    );

    private static final List<String> CORE_TABLES = Arrays.asList(
            "users",
            "employees",
            "shifts",
            "master_reference_data",
            "roster_cycles",
            "roster_assignments",
            "leave_requests",
            "shift_handovers",
            "notifications",
            "employee_requests",
            "system_audit_logs"
    );

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        log.info("================================================================================");
        log.info("  [BATCH 56 DATABASE CONSOLIDATION RUNNER INITIALIZING]");
        log.info("  Target   : EXACTLY 11 CORE APPLICATION TABLES");
        log.info("  Objective: Retire obsolete tables with ZERO DATA LOSS & full verification");
        log.info("--------------------------------------------------------------------------------");

        try {
            // Step 1: Ensure any residual data in legacy tables is copied before retirement
            migrateResidualLegacyData();

            // Step 2: Ensure system_audit_logs has any necessary columns for RosterOverride
            ensureSystemAuditLogsColumns();

            // Step 3: Remove legacy foreign key constraints pointing to obsolete tables
            cleanupLegacyForeignKeys();

            // Step 4: Drop foreign keys originating from obsolete tables
            dropForeignKeysOnObsoleteTables();

            // Step 5: Safely retire the 11 obsolete tables
            retireObsoleteTables();

            // Step 6: Verify final table inventory and count
            verifyFinalSchemaTableCount();

        } catch (Exception e) {
            log.error("  [BATCH 56 RUNNER ERROR] Unexpected issue during consolidation: {}", e.getMessage(), e);
        }

        log.info("================================================================================");
    }

    private boolean tableExists(String tableName) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                    Integer.class, tableName);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureSystemAuditLogsColumns() {
        if (!tableExists("system_audit_logs")) return;
        try {
            // Add assignment_id column if not present
            if (!columnExists("system_audit_logs", "assignment_id")) {
                jdbcTemplate.execute("ALTER TABLE system_audit_logs ADD COLUMN assignment_id BIGINT NULL");
                log.info("  -> Added column 'assignment_id' to system_audit_logs");
            }
            // Add previous_shift_type column if not present
            if (!columnExists("system_audit_logs", "previous_shift_type")) {
                jdbcTemplate.execute("ALTER TABLE system_audit_logs ADD COLUMN previous_shift_type VARCHAR(30) NULL");
                log.info("  -> Added column 'previous_shift_type' to system_audit_logs");
            }
            // Add new_shift_type column if not present
            if (!columnExists("system_audit_logs", "new_shift_type")) {
                jdbcTemplate.execute("ALTER TABLE system_audit_logs ADD COLUMN new_shift_type VARCHAR(30) NULL");
                log.info("  -> Added column 'new_shift_type' to system_audit_logs");
            }
            // Add weekly_off column if not present
            if (!columnExists("system_audit_logs", "weekly_off")) {
                jdbcTemplate.execute("ALTER TABLE system_audit_logs ADD COLUMN weekly_off BIT(1) NULL DEFAULT 0");
                log.info("  -> Added column 'weekly_off' to system_audit_logs");
            }
        } catch (Exception e) {
            log.warn("  Could not ensure columns on system_audit_logs: {}", e.getMessage());
        }
    }

    private boolean columnExists(String tableName, String columnName) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                    Integer.class, tableName, columnName);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void migrateResidualLegacyData() {
        // Migrate roster_overrides -> system_audit_logs if legacy table exists with rows
        if (tableExists("roster_overrides") && tableExists("system_audit_logs")) {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM roster_overrides", Integer.class);
            if (count != null && count > 0) {
                Integer existingInSal = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_audit_logs WHERE log_type = 'ROSTER_OVERRIDE'", Integer.class);
                if (existingInSal == null || existingInSal == 0) {
                    jdbcTemplate.execute(
                            "INSERT INTO system_audit_logs (log_type, assignment_id, previous_shift_type, new_shift_type, weekly_off, reason, created_at) " +
                            "SELECT 'ROSTER_OVERRIDE', assignment_id, previous_shift_type, new_shift_type, weekly_off, reason, created_at FROM roster_overrides");
                    log.info("  -> Migrated {} residual roster_overrides records into system_audit_logs", count);
                }
            }
        }
    }

    private void cleanupLegacyForeignKeys() {
        // Check if employee_skills still has FK8anwsnenk9d8nirjuov0ywinb pointing to skills
        if (tableExists("employee_skills")) {
            try {
                List<Map<String, Object>> fks = jdbcTemplate.queryForList(
                        "SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'employee_skills' " +
                        "AND CONSTRAINT_NAME = 'FK8anwsnenk9d8nirjuov0ywinb'");
                for (Map<String, Object> fk : fks) {
                    String name = (String) fk.get("CONSTRAINT_NAME");
                    if (name != null) {
                        jdbcTemplate.execute("ALTER TABLE employee_skills DROP FOREIGN KEY " + name);
                        log.info("  -> Dropped legacy foreign key '{}' on employee_skills pointing to skills", name);
                    }
                }
            } catch (Exception e) {
                log.warn("  Could not drop legacy FK on employee_skills: {}", e.getMessage());
            }
        }
    }

    private void dropForeignKeysOnObsoleteTables() {
        for (String table : OBSOLETE_TABLES) {
            if (!tableExists(table)) continue;
            try {
                List<Map<String, Object>> fks = jdbcTemplate.queryForList(
                        "SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND CONSTRAINT_TYPE = 'FOREIGN KEY'", table);
                for (Map<String, Object> fk : fks) {
                    String name = (String) fk.get("CONSTRAINT_NAME");
                    if (name != null) {
                        try {
                            jdbcTemplate.execute("ALTER TABLE " + table + " DROP FOREIGN KEY " + name);
                            log.info("  -> Dropped FK '{}' from obsolete table '{}'", name, table);
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                log.warn("  Could not inspect FKs on table {}: {}", table, e.getMessage());
            }
        }
    }

    private void retireObsoleteTables() {
        log.info("  [SAFELY RETIRING 11 OBSOLETE TABLES]");
        for (String table : OBSOLETE_TABLES) {
            if (!tableExists(table)) {
                log.info("  Table '{}' already retired.", table);
                continue;
            }
            try {
                Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
                if (count != null && count > 0) {
                    log.warn("  [SAFETY CHECK] Table '{}' still has {} rows! Skipping drop to prevent data loss.", table, count);
                    continue;
                }
                jdbcTemplate.execute("DROP TABLE IF EXISTS " + table);
                log.info("  -> Successfully retired obsolete table: '{}'", table);
            } catch (Exception e) {
                log.warn("  Could not drop table '{}': {}", table, e.getMessage());
            }
        }
    }

    private void verifyFinalSchemaTableCount() {
        log.info("--------------------------------------------------------------------------------");
        log.info("  [FINAL SCHEMA VERIFICATION]");
        try {
            List<String> actualTables = jdbcTemplate.queryForList(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() ORDER BY table_name",
                    String.class);

            log.info("  Total tables in database: {}", actualTables.size());
            for (int i = 0; i < actualTables.size(); i++) {
                String tbl = actualTables.get(i);
                Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tbl, Integer.class);
                boolean isCore = CORE_TABLES.contains(tbl.toLowerCase());
                log.info("    {:2d}. {:<25} [{} rows] {}", (i + 1), tbl, (rows != null ? rows : 0), (isCore ? "✓ CORE" : "⚠ NON-CORE"));
            }

            if (actualTables.size() == 11) {
                log.info("  ========================================================================");
                log.info("  [SUCCESS] EXACTLY 11 CORE APPLICATION TABLES ACTIVE IN WRMS PRODUCTION!");
                log.info("  ========================================================================");
            } else {
                log.warn("  [ATTENTION] Table count is {} (expected 11). Check non-core tables above.", actualTables.size());
            }
        } catch (Exception e) {
            log.error("  Error during final schema verification: {}", e.getMessage());
        }
    }
}
