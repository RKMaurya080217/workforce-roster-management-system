# Workforce Roster Management System (WRMS) — System Changelog

All notable changes, bug fixes, database schema refinements, and architectural stabilizations for the WRMS project are documented in this file.

---

## [1.0.0] - Batch 52: Database Optimization, Table Consolidation & Schema Refinement (2026-08-29)

### Added
- **Database Architecture Documentation**:
  - `WRMS Documents/Database/WRMS-Current-Database-Inventory.md`: Complete audit of all 20 existing tables.
  - `WRMS Documents/Database/WRMS-Optimized-Database-Design.md`: Schema design consolidating overrides, activity streams, and status flags.
  - `WRMS Documents/Database/WRMS-Database-Migration-Guide.md`: Step-by-step SQL migration and index optimization script.

### Optimized & Streamlined
- **Roster Overrides Consolidation**: Merged override tracking (`is_overridden`, `original_shift_id`, `override_reason`, `overridden_by`, `overridden_at`) directly into `roster_assignments`.
- **Unified Audit Logs**: Unified system audit events and employee activity logs into centralized `audit_logs` store.
- **Full Automated Regression**: All 398 tests verified passing with zero data or functional loss.

## [1.0.0] - Batch 51: Live Gmail SMTP Verification + Full WRMS Runtime Scan + Final Production Validation (2026-08-29)

### Added
- **Automated Live Email Verification Suite**: Implemented `Batch51LiveEmailVerificationTest.java` verifying SMTP protocol handshake, tentative/final email dispatch, duplicate prevention, and personal schedule template rendering.
- **Email Subsystem Verification Report**: Published `WRMS Documents/WRMS-Email-Verification-Report.md` detailing SMTP architecture, security posture, and live transmission telemetry.

### Verified & Hardened
- **Test Isolation**: Added `@DirtiesContext` and clean state lifecycle in `Batch36TentativeToFinalWorkflowTest.java`.
- **Complete Test Suite**: All 398 automated tests passing cleanly with 100% success (0 Failures, 0 Errors, 0 Skipped).
- **Live Production Package**: Built `target/weekly-roster-management-system-1.0.0.jar` and verified live end-to-end HTTP endpoints.

## [1.0.0] - Batch 50: WRMS Final UAT, Production Release Candidate & Full Regression (2026-08-29)

### Added
- **Release Candidate Verification Suite**: Implemented `Batch50ReleaseCandidateUatTest.java` verifying complete Admin & Employee user journeys, system health diagnostics, shift handover lifecycle, and profile approvals.
- **Production Release Candidate Report**: Published `WRMS Documents/WRMS-Release-Candidate-Report.md` confirming full UAT and automated regression pass across 395 test cases.

### Verified & Released
- **Full Test Suite**: 395 automated unit and integration tests passing with 100% success (0 Failures, 0 Errors, 0 Skipped).
- **Executable Production Package**: Built `target/weekly-roster-management-system-1.0.0.jar` and verified live HTTP runtime behavior.
- **Final Go/No-Go Verdict**: READY FOR RELEASE.

## [1.0.0] - Batch 49: WRMS Final Production Hardening, Performance & Project Health (2026-08-28)

### Added
- **Automated Production Hardening & Performance Test Suite**: Implemented `Batch49ProductionHardeningAndPerformanceTest.java` verifying end-to-end business workflows, database metrics integrity, shift handover acknowledgments, and female safety invariants.
- **System & Production Health Report**: Published `WRMS Documents/WRMS-Project-Health-Report.md` providing comprehensive health scores across build, runtime, database, API performance, and security dimensions.

### Verified & Hardened
- **Total Test Suite**: Expanded regression coverage to 391 automated unit and integration tests (100% passing).
- **Production Packaging**: Verified clean generation of `weekly-roster-management-system-1.0.0.jar`.

## [1.0.0] - Batch 48: Full Project Run, Live End-to-End QA & Production Readiness (2026-08-28)

### Added
- **Full Live Runtime End-to-End Verification**: Executed live server verification testing Admin login, Employee login, Dashboard APIs, Today's Duty resolution, System Health, and Security Isolation.
- **Final QA Verification Report**: Published `WRMS Documents/WRMS-Final-QA-Report.md` detailing full test execution matrices across all 387 tests.

### Fixed & Cleaned
- **UTF-8 & Mojibake Remediation**: Cleaned all corrupted character literals across `enterprise-app.js` and frontend assets.
- **Public Health Endpoint Authorization**: Configured `SecurityConfig` to permit public health checks without credentials at `/api/public/**`.

## [1.0.0] - Batch 47: Production Hardening, Automated Regression & Health Diagnostics (2026-08-28)

### Added
- **Automated System Health Check**: Added `SystemHealthService` and `SystemHealthController` (`GET /api/admin/system-health` and `GET /api/public/health`) inspecting JVM memory, MySQL database connection, master data integrity, SSE real-time connection status, email configuration mode, and roster cycle counts.
- **Batch 47 Production Hardening Regression Suite**: Comprehensive regression test verifying end-to-end multi-role workflows, constraint solver invariants, email idempotency, SSE reconnect resiliency, and database integrity.
- **Enterprise System Architecture Document**: Published `WRMS-System-Architecture.md` with complete ASCII and mermaid sequence workflows.

### Hardened & Fixed
- **Central Exception Handling**: Hardened `GlobalExceptionHandler` to sanitize unexpected internal errors, preventing sensitive SQL or configuration disclosure.
- **Scheduler & Email Idempotency**: Verified strict upcoming-week generation invariants (`Asia/Kolkata` Sunday 09:00 AM tentative, Sunday 16:00 PM finalization) with delivery state logging (`PENDING`, `SENT`, `FAILED`).
- **Database Relational Integrity**: Verified cascade deletion boundaries across `roster_versions`, `email_delivery_logs`, `roster_overrides`, `roster_assignments`, and `roster_cycles`.

---

## [0.9.9] - Batch 46: Complete Source-Code Mapped Documentation & Zero-Skip Audit (2026-08-28)

### Added
- **Complete Administrator Technical Guide**: Published `WRMS Documents/Admin/WRMS-Admin-Documentation.md` covering all 20 Admin modules, 151 REST API endpoints, and 18 database tables.
- **Complete Employee Self-Service Guide**: Published `WRMS Documents/Employee/WRMS-Employee-Documentation.md` covering today's duty resolution, leave workflows, preferences, profile updates, and shift handovers.
- **Codebase Inventory Scanner**: Extracted complete endpoint, entity, and scheduler matrices.

---

## [0.9.8] - Batch 45: Real-Time SSE Stream, Email Diagnostics & Smart Command Center (2026-08-28)

### Added
- **Server-Sent Events (SSE) Real-Time Notification Engine**: Implemented `SseEmitterService` with 25s heartbeat keep-alives and query parameter token authentication for browser `EventSource`.
- **Smart Roster Command Center Actions**: Added 1-click `/generate-upcoming`, `/publish`, and `/lock` operational endpoints.
- **Admin Email Diagnostics**: Added `POST /api/rosters/email/test-smtp` for testing SMTP connectivity without password exposure.
- **Interactive Dashboard Drilldowns**: Connected all metrics directly to live database queries with zero full-page reloads.

---

## [0.9.7] - Batch 44: Deep Functional Validation & Regression Hardening (2026-08-28)

### Hardened
- RBAC service enforcement on leave and profile change approvals.
- Profile change requests mirror synchronization with `employees.csv`.
- Female employee safety invariants (0 Evening, 0 Night).
- 12-hour minimum rest period constraint enforcement.