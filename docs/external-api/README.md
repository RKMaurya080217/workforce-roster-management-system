# WRMS External API & Integration Layer (v1)

Welcome to the **Workforce Roster Management System (WRMS)** External API documentation. This integration layer enables approved third-party enterprise software (e.g. HRMS, attendance systems, Slack/Teams bots, external dashboards) to consume roster, employee, shift, and leave data through secure, high-performance REST APIs.

---

## Architecture Overview

* **Namespace**: All external endpoints are strictly isolated under `/api/external/v1/...`.
* **Security**: Authentication via dedicated API Keys (`X-API-Key` or `Authorization: Bearer <key>`) with SHA-256 hashed storage. Internal credentials (admin/employee usernames and passwords) are never shared.
* **Scope-Based Authorization**: Granular permissions (`ROSTER_READ`, `EMPLOYEE_READ`, `SHIFT_READ`, `LEAVE_READ`).
* **Clean DTO Layer**: Internal database entities are decoupled. Sensitive personal records (passwords, auth tokens, user IDs) are never exposed.
* **Zero Database Bloat**: API client credentials utilize single-table inheritance within the existing `master_reference_data` table, strictly preserving WRMS's **12 core database tables**.
* **Observability**: Distributed correlation tracking via `X-Request-ID` and structured audit logging.
* **Rate Limiting**: In-memory token bucket rate limiting (default: 60 requests/minute) returning HTTP 429 when exceeded.

---

## Documentation Index

| Document | Purpose |
|---|---|
| [Quick Start Guide](quick-start.md) | Make your first API call in 5 minutes with cURL and Postman |
| [Authentication & Scopes](authentication.md) | Header formats, key generation, hashing, and permission scopes |
| [Error Codes & Responses](error-codes.md) | Standard JSON envelope, HTTP status codes, and error formats |
| [Third-Party Onboarding](THIRD_PARTY_ONBOARDING.md) | Step-by-step onboarding walkthrough for external partners |
| [Troubleshooting Guide](TROUBLESHOOTING.md) | Solutions for 401, 403, 404, 429, and 500 responses |
| [Technical Architecture & Flow](WRMS_EXTERNAL_API_EXPLANATION.md) | Detailed architectural guide, request journey, and 22 concept explainers |
| [Hinglish Owner Explanation](BATCH_60_FINAL_EXPLANATION.md) | Simple, non-technical explanation for project owner Rajat |
| [Changelog](changelog.md) | Version history and API roadmap |
| [Postman Collection](WRMS_External_API_v1.postman_collection.json) | Ready-to-import Postman collection v2.1.0 |

---

## Interactive Swagger UI

When running WRMS locally or in production, open Swagger UI in your browser:
```
http://localhost:8080/swagger-ui.html
```
Use the **Select a spec** dropdown at the top right:
* Choose **`External API v1 (Partner Integration)`** to explore and test integration endpoints.
* Click the green **Authorize** button and input your API key under `apiKeyAuth` (`X-API-Key`).

---

## External Endpoints Reference

### 1. Diagnostics
* `GET /api/external/v1/ping`: Verify API connectivity, key validity, and granted scopes.

### 2. Rosters (`SCOPE_ROSTER_READ`)
* `GET /api/external/v1/rosters/current`: Get the current week's published roster cycle and duty assignments.
* `GET /api/external/v1/rosters?startDate=YYYY-MM-DD`: Get the weekly roster cycle for a specific start date (Monday).
* `GET /api/external/v1/rosters/by-date?date=YYYY-MM-DD`: Get all shift assignments across the team on a specific date.
* `GET /api/external/v1/rosters/employee/{employeeCode}`: Get shift assignments for a specific employee code (e.g. `EMP001`).

### 3. Employees (`SCOPE_EMPLOYEE_READ`)
* `GET /api/external/v1/employees?activeOnly=true`: List active employees (sanitized: code, name, email, gender).
* `GET /api/external/v1/employees/{employeeCode}`: Get details for an individual employee.

### 4. Shifts (`SCOPE_SHIFT_READ`)
* `GET /api/external/v1/shifts`: List active shift definitions (MORNING, GENERAL, EVENING, NIGHT, OFF) and slot capacities.

### 5. Leaves (`SCOPE_LEAVE_READ`)
* `GET /api/external/v1/leaves`: List approved leaves with optional filters (`startDate`, `endDate`, `employeeCode`).

---

## Administrative Client Management (`ROLE_ADMIN`)

Admins can issue, inspect, and revoke API client keys via:
* `GET /api/admin/external-clients`: List registered API clients.
* `POST /api/admin/external-clients`: Provision a new client key (unmasked key shown only once).
* `PUT /api/admin/external-clients/{id}/toggle`: Enable or disable an existing client.
* `DELETE /api/admin/external-clients/{id}`: Revoke and permanently delete a client.
