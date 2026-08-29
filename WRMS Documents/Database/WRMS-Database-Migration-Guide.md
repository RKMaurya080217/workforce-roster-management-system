# Workforce Roster Management System (WRMS) — Database Migration Guide

```text
Migration Version: Batch 52 Schema Refinement
Compatibility: MySQL 8.0+ / MariaDB / H2 (Test Mode)
Strategy: Non-Destructive In-Place Column Addition & Consolidation
```

---

## 1. Migration Steps & Execution Plan

### Step 1: Add Override Fields to `roster_assignments`
```sql
ALTER TABLE roster_assignments
    ADD COLUMN IF NOT EXISTS is_overridden BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS original_shift_id BIGINT NULL,
    ADD COLUMN IF NOT EXISTS override_reason VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS overridden_by VARCHAR(80) NULL,
    ADD COLUMN IF NOT EXISTS overridden_at DATETIME NULL;

-- Migrate existing overrides into roster_assignments
UPDATE roster_assignments ra
JOIN roster_overrides ro ON ro.assignment_id = ra.id
SET ra.is_overridden = TRUE,
    ra.original_shift_id = ro.original_shift_id,
    ra.override_reason = ro.reason,
    ra.overridden_by = ro.overridden_by,
    ra.overridden_at = ro.overridden_at;
```

### Step 2: Unify `employee_activity_logs` into `audit_logs`
```sql
-- Migrate employee activities to audit_logs
INSERT INTO audit_logs (actor_username, action, entity_type, entity_id, reason, timestamp, employee_id)
SELECT e.employee_code, eal.action, 'EMPLOYEE_ACTIVITY', eal.id, eal.details, eal.timestamp, eal.employee_id
FROM employee_activity_logs eal
JOIN employees e ON e.id = eal.employee_id
WHERE NOT EXISTS (
    SELECT 1 FROM audit_logs al
    WHERE al.entity_type = 'EMPLOYEE_ACTIVITY' AND al.entity_id = eal.id
);
```

### Step 3: Composite Indexing for Performance
```sql
CREATE INDEX idx_assignment_lookup ON roster_assignments (cycle_id, roster_date, shift_id);
CREATE INDEX idx_audit_lookup ON audit_logs (employee_id, timestamp);
CREATE INDEX idx_notification_user ON notifications (recipient_username, read_status, created_at);
```

---

## 2. Verification Checklist
1. All foreign keys on `roster_assignments`, `roster_cycles`, `employees`, and `users` validated.
2. Verified 0 orphan records across assignments and audit logs.
3. Verified clean startup in Spring Boot with `spring.jpa.hibernate.ddl-auto=update`.