# Workforce Roster Management System (WRMS) — Current Database Inventory

```text
Document Version: 1.0.0
Date: August 29, 2026
Scope: Complete catalog of all 20 existing tables in the WRMS schema before Batch 52 optimization.
```

---

## 1. Complete Table Inventory (20 Tables)

| # | Table Name | Purpose / Responsibility | Primary Key | Foreign Keys / References | Related Java Entity & Service | Consolidation Recommendation |
| :- | :--- | :--- | :--- | :--- | :--- | :--- |
| 1 | `users` | Security credentials, password hash, role (`ROLE_ADMIN`, `ROLE_EMPLOYEE`), active flag | `id` | None (Referenced by `employees.user_id`) | `User`, `UserService`, `AuthService` | **CORE (KEEP)** — Essential for Spring Security & RBAC |
| 2 | `employees` | Staff profile, contact, gender, designation, department, employment type | `id` | `user_id` -> `users.id` | `Employee`, `EmployeeService` | **CORE (KEEP)** — Central domain entity |
| 3 | `shifts` | Shift definitions (Morning, General, Evening, Night), timings, overnight flag | `id` | None | `Shift`, `ShiftService` | **CORE (KEEP)** — Data-driven operational shift master |
| 4 | `roster_cycles` | Weekly schedule cycles, start/end dates, mode, status (`DRAFT`, `TENTATIVE`, `FINAL`, `PUBLISHED`, `LOCKED`) | `id` | None | `RosterCycle`, `RosterService`, `RosterSchedulerService` | **CORE (KEEP)** — Weekly roster boundary |
| 5 | `roster_assignments` | Assignment of employee to shift on specific date, weekly OFF, leave flag | `id` | `cycle_id`, `employee_id`, `shift_id` | `RosterAssignment`, `RosterService` | **CORE (CONSOLIDATE)** — Absorb `roster_overrides` fields |
| 6 | `roster_overrides` | 1-to-1 override tracking on assignments (original shift, override reason, actor, timestamp) | `id` | `assignment_id`, `original_shift_id`, `overridden_shift_id` | `RosterOverride`, `RosterService` | **MERGE INTO `roster_assignments`** — Fields stored directly on assignment |
| 7 | `roster_versions` | Version snapshot & change diffs for historical rollback and audits | `id` | `cycle_id` -> `roster_cycles.id` | `RosterVersion`, `RosterVersionService` | **CORE (KEEP)** — Audit & rollback compliance |
| 8 | `roster_review_records` | Review notes and acknowledgment timestamps for tentative rosters | `id` | `cycle_id`, `employee_id` | `RosterReviewRecord`, `RosterReviewService` | **MERGE INTO `roster_cycles` / `audit_logs`** — Redundant with cycle status & audit trail |
| 9 | `roster_change_requests` | Shift swap / duty change requests submitted during tentative review | `id` | `cycle_id`, `employee_id`, `assignment_id`, `requested_shift_id` | `RosterChangeRequest`, `RosterReviewService` | **CORE (KEEP/UNIFY)** — Tentative change workflow |
| 10 | `leave_requests` | Employee leave applications, leave type, start/end dates, approval status, admin remarks | `id` | `employee_id` -> `employees.id` | `LeaveRequest`, `LeaveService` | **CORE (KEEP)** — Operational leave workflow |
| 11 | `employee_preferences` | Preferred shift types, avoided shift types, preferred OFF days, status | `id` | `employee_id` -> `employees.id` | `EmployeePreference`, `EmployeePreferenceService` | **CORE (KEEP)** — Solver input preferences |
| 12 | `profile_change_requests`| Self-service profile modification requests (contact, email, address), approval status | `id` | `employee_id` -> `employees.id` | `ProfileChangeRequest`, `ProfileChangeRequestService` | **CORE (KEEP)** — Profile governance & CSV mirror sync |
| 13 | `shift_handovers` | Shift handover notes, tasks, blockers, priority, outgoing/incoming staff, acknowledgment | `id` | `shift_id`, `created_by`, `incoming_employee_id` | `ShiftHandover`, `ShiftHandoverService` | **CORE (KEEP)** — Operational shift continuity |
| 14 | `notifications` | In-app user notifications, unread flags, SSE event types, timestamps | `id` | `recipient_employee_id` | `Notification`, `NotificationService`, `SseEmitterService` | **CORE (KEEP)** — Real-time event streaming |
| 15 | `audit_logs` | System audit trail (actor, action enum, entity type, entity id, timestamp, cycle id) | `id` | `cycle_id`, `employee_id` | `AuditLog`, `AuditService` | **CORE (CONSOLIDATE)** — Unified system & employee activity log |
| 16 | `employee_activity_logs`| Per-employee login/profile/request activity logs | `id` | `employee_id` -> `employees.id` | `EmployeeActivityLog`, `EmployeeActivityLogService` | **MERGE INTO `audit_logs`** — Unified queryable audit store |
| 17 | `holidays` | Organization holiday calendar (date, name, optional flag) | `id` | None | `Holiday`, `HolidayService` | **CORE (KEEP)** — Calendar master data |
| 18 | `skills` | Master catalog of technical/operational skills | `id` | None | `Skill`, `SkillMatrixService` | **CORE (KEEP)** — Skill matrix master data |
| 19 | `employee_skills` | Mapping of employees to skills with proficiency levels & certifications | `id` | `employee_id`, `skill_id` | `EmployeeSkill`, `SkillMatrixService` | **CORE (KEEP)** — Skill matrix relationship |
| 20 | `email_delivery_logs` | Telemetry logs for roster email dispatches, recipient, delivery status, timestamp | `id` | `cycle_id`, `employee_id` | `EmailDeliveryLog`, `RosterEmailService` | **CORE (KEEP)** — Email auditability & idempotency |

---

## 2. Redundancy & Consolidation Strategy
- **`roster_overrides` $ightarrow$ Merged into `roster_assignments`**: Storing `is_overridden`, `original_shift_id`, `override_reason`, `overridden_by`, `overridden_at` directly on `roster_assignments` eliminates an entire 1-to-1 join table while accelerating duty lookups.
- **`employee_activity_logs` $ightarrow$ Merged into `audit_logs`**: Both capture historical action events. Unifying them into `audit_logs` ensures a single centralized source of truth for compliance and employee activity feeds.
- **`roster_review_records` $ightarrow$ Consolidated into `roster_cycles` & `audit_logs`**: Review deadlines and statuses (`TENTATIVE`, `FINAL`, `LOCKED`) already reside on `roster_cycles`. Review submissions are logged in `audit_logs`.