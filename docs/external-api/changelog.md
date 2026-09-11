# WRMS External API — Changelog & Version History

All notable changes to the External API and Integration Layer will be documented in this file.

---

## [1.0.0] — 2026-09-11 (Batch 60)

### Added
- **Dedicated External API Namespace**: Introduced versioned root `/api/external/v1/...`.
- **API Key Authentication**:
  - Supported headers: `X-API-Key` and `Authorization: Bearer <key>`.
  - Cryptographically secure key generation (`wrms_live_...`).
  - SHA-256 one-way hashed key storage with single-table inheritance in `master_reference_data` (zero new tables).
- **Scope-Based Authorization**:
  - `ROSTER_READ`: Current roster, date queries, employee duties.
  - `EMPLOYEE_READ`: Sanitized employee directory and single employee lookup.
  - `SHIFT_READ`: Active shift definitions and capacities.
  - `LEAVE_READ`: Approved leave records.
- **External DTO Layer**: Created 10 dedicated records decoupling internal entities and shielding passwords, user IDs, and auth secrets.
- **Diagnostics**: Added `GET /api/external/v1/ping` for connectivity and scope verification.
- **Rate Limiting**: Lightweight in-memory token bucket rate limiting (60 req/min) returning HTTP 429.
- **Correlation ID**: Added `CorrelationIdFilter` propagating `X-Request-ID` across response headers and JSON metadata.
- **Admin Client Management**: Added `/api/admin/external-clients` for creating, listing, toggling, and revoking client keys.
- **Swagger UI Integration**: SpringDoc `GroupedOpenApi` with separate views for `External API v1` and `Internal WRMS API`.
- **Postman Collection**: Released Postman collection v2.1.0 with dynamic variables.
