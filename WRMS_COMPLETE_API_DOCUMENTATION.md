# ðŸ“˜ WRMS â€” COMPLETE BACKEND API INVENTORY & INTEGRATION REFERENCE MANUAL

**Platform**: Workforce Roster Management System (WRMS)  
**Version**: 2.5.0 (Enterprise Operations v2.0)  
**Backend Framework**: Spring Boot 3.3.5 / Java 17  
**Database**: MySQL 8.0+ (HikariCP / Hibernate 6 / JPA)  
**Security Architecture**: Spring Security 6 (Stateless JWT Bearer + HMAC-SHA256 API Key Authentication)  
**Deployment Runtime**: Railway / Cloud Docker Container  
**Date**: September 2026  

---

## ðŸ“‹ TABLE OF CONTENTS

1. [Architectural Overview & Global Conventions](#1-architectural-overview--global-conventions)
2. [Authentication, Authorization & Security Matrix](#2-authentication-authorization--security-matrix)
3. [Global Error Handling & Standard Responses](#3-global-error-handling--standard-responses)
4. [Complete API Reference by Functional Module](#4-complete-api-reference-by-functional-module)
   - [Module 1: Authentication & User Session](#module-1-authentication--user-session)
   - [Module 2: Employee Profile & Directory Management](#module-2-employee-profile--directory-management)
   - [Module 3: Shift Management & Capacity Configuration](#module-3-shift-management--capacity-configuration)
   - [Module 4: Core Roster Engine & Schedule Generation](#module-4-core-roster-engine--schedule-generation)
   - [Module 5: Roster Change Impact Preview & Atomic Swaps](#module-5-roster-change-impact-preview--atomic-swaps)
   - [Module 6: Roster Review & Employee Correction Window](#module-6-roster-review--employee-correction-window)
   - [Module 7: Roster Verification & Safety Rules Validation](#module-7-roster-verification--safety-rules-validation)
   - [Module 8: Roster Version History, Diff & Safe Rollback](#module-8-roster-version-history-diff--safe-rollback)
   - [Module 9: Leave Request Lifecycle & Approvals](#module-9-leave-request-lifecycle--approvals)
   - [Module 10: Shift Availability & Employee Preferences](#module-10-shift-availability--employee-preferences)
   - [Module 11: Shift Handover & Reliever Logs](#module-11-shift-handover--reliever-logs)
   - [Module 12: Skill Matrix & Employee Qualifications](#module-12-skill-matrix--employee-qualifications)
   - [Module 13: Company & Operational Holidays](#module-13-company--operational-holidays)
   - [Module 14: Operations Dashboard & Live Overview](#module-14-operations-dashboard--live-overview)
   - [Module 15: Smart Roster Command Center](#module-15-smart-roster-command-center)
   - [Module 16: Unified Admin Approvals Center](#module-16-unified-admin-approvals-center)
   - [Module 17: Workload Distribution & Shift Analytics](#module-17-workload-distribution--shift-analytics)
   - [Module 18: Notification Center & Real-Time SSE Stream](#module-18-notification-center--real-time-sse-stream)
   - [Module 19: Employee Activity Logs & System Audit Trails](#module-19-employee-activity-logs--system-audit-trails)
   - [Module 20: Enterprise Export Center (Excel, PNG, CSV)](#module-20-enterprise-export-center-excel-png-csv)
   - [Module 21: Transactional Email Infrastructure (Brevo & SMTP)](#module-21-transactional-email-infrastructure-brevo--smtp)
   - [Module 22: System Health Diagnostics & Error Routing](#module-22-system-health-diagnostics--error-routing)
   - [Module 23: Global Visitor Analytics & Live Online Counters](#module-23-global-visitor-analytics--live-online-counters)
   - [Module 24: External Third-Party Integration Layer (v1 API)](#module-24-external-third-party-integration-layer-v1-api)
5. [Complete Data Transfer Object (DTO) Dictionary](#5-complete-data-transfer-object-dto-dictionary)
6. [External Project Integration Guide & Best Practices](#6-external-project-integration-guide--best-practices)

---

## 1. Architectural Overview & Global Conventions

The Workforce Roster Management System (WRMS) exposes RESTful HTTP APIs built on Spring Boot 3.3.5, complying with standard HTTP semantics and RFC 7231.

### Global URI Base Paths
- **Internal / Application APIs**: `/api/*` (Consists of Admin endpoints, Employee workspace endpoints, and Shared utility endpoints).
- **Public Utility & Analytics APIs**: `/api/visitor-stats/*`, `/api/public/health`, `/api/auth/login`.
- **External Integration APIs**: `/api/external/v1/*` (Designed for external services such as HRMS, Payroll, Mobile apps, and automated third-party consumers).

### Date and Time Formatting
Unless otherwise stated:
- Dates (`LocalDate`): ISO-8601 string format `YYYY-MM-DD` (e.g. `2026-09-15`).
- Timestamps (`LocalDateTime`): ISO-8601 format `YYYY-MM-DDTHH:mm:ss` (e.g. `2026-09-15T14:30:00`).
- Timezone: Configured as `Asia/Kolkata` (IST, UTC+05:30).

---

## 2. Authentication, Authorization & Security Matrix

WRMS enforces two primary authentication schemes:

### A. JWT Bearer Token (User Authentication)
Used by human operators (Administrators and Employees) logging into the web SPA.
- **Login Endpoint**: `POST /api/auth/login`
- **Request Header**: `Authorization: Bearer <JWT_TOKEN>`
- **Token Validity**: 24 hours (86,400,000 ms) by default.
- **Roles & Authorities**:
  - `ROLE_ADMIN`: Full access to scheduling, employee CRUD, approvals, system configuration, audit logs, and exports.
  - `ROLE_EMPLOYEE`: Access to personal duty views, personal leave management, preference submission, handovers, personal profile change requests, and notifications.

### B. External API Key Authentication (System-to-System Integration)
Used by third-party systems communicating with `/api/external/v1/*`.
- **Request Headers**:
  - `X-API-Key: <SECRET_API_KEY>` (Recommended) OR `Authorization: Bearer <SECRET_API_KEY>`
  - Optional `X-Request-ID: <UUID>`: For correlation and end-to-end tracing.
- **Scopes Enforced**:
  - `ROSTER_READ`: Access to current, historical, and daily roster schedules.
  - `EMPLOYEE_READ`: Access to employee directory and employee profile information.
  - `LEAVE_READ`: Access to approved and pending leave records.
  - `SHIFT_READ`: Access to shift types, capacities, and operational timings.

### Global Endpoint Access Matrix

| Endpoint Pattern | Authentication Required | Permitted Roles / Clients |
| :--- | :--- | :--- |
| `/`, `/index.html`, `/styles.css`, `/app.js`, `/favicon.ico` | No | Public (Static Web Assets) |
| `/api/auth/login`, `/api/auth/login/**` | No | Public |
| `/api/visitor-stats`, `/api/visitor-stats/**` | No | Public |
| `/api/public/health` | No | Public |
| `/swagger-ui/**`, `/v3/api-docs/**` | No | Public |
| `/api/external/v1/ping` | Yes (API Key) | `ROLE_EXTERNAL_CLIENT`, `ROLE_ADMIN` |
| `/api/external/v1/**` | Yes (API Key + Scope) | `ROLE_EXTERNAL_CLIENT`, `ROLE_ADMIN` |
| `/api/admin/external-clients/**` | Yes (JWT) | `ROLE_ADMIN` |
| `/api/auth/me`, `/api/auth/change-password`, `/api/auth/logout` | Yes (JWT) | `ROLE_ADMIN`, `ROLE_EMPLOYEE` |
| `/api/leaves` (POST), `/api/leaves/*/modification`, `/cancellation` | Yes (JWT) | `ROLE_ADMIN`, `ROLE_EMPLOYEE` |
| `/api/leaves/my/**`, `/api/rosters/employee/**` | Yes (JWT) | `ROLE_ADMIN`, `ROLE_EMPLOYEE` |
| `/api/rosters/my-duty/today`, `/api/rosters/effective-duty` | Yes (JWT) | `ROLE_ADMIN`, `ROLE_EMPLOYEE` |
| `/api/notifications/**`, `/api/activities/**`, `/api/profile-change-requests/**` | Yes (JWT) | `ROLE_ADMIN`, `ROLE_EMPLOYEE` |
| `/api/employees/me`, `/api/holidays/**`, `/api/preferences/**`, `/api/handovers/**`, `/api/skills/**` | Yes (JWT) | `ROLE_ADMIN`, `ROLE_EMPLOYEE` |
| `GET /api/shifts`, `GET /api/employees/**`, `GET /api/rosters/cycle/*/export/*` | Yes (JWT) | `ROLE_ADMIN`, `ROLE_EMPLOYEE` |
| `GET /api/dashboard/day-view`, `GET /api/dashboard/employee-view` | Yes (JWT) | `ROLE_ADMIN`, `ROLE_EMPLOYEE` |
| `/api/**` (All other administrative and mutating endpoints) | Yes (JWT) | `ROLE_ADMIN` |

---

## 3. Global Error Handling & Standard Responses

### Standard Internal API Error Format (`ApiErrorResponse`)
Returned by `/api/*` internal endpoints upon validation failure, business rule violation, or runtime error:
```json
{
  "timestamp": "2026-09-14T19:00:00",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/leaves",
  "validationErrors": {
    "reason": "Reason cannot be empty",
    "startDate": "Start date must be today or in the future"
  }
}
```

### Standard External API Envelope Format (`ExternalApiResponse<T>`)
All responses under `/api/external/v1/*` use a strict envelope format:
```json
{
  "success": true,
  "data": { ... },
  "error": null,
  "meta": {
    "timestamp": "2026-09-14T19:00:00",
    "requestId": "f8a9e1d2-bc34-4a21-9e87-1234567890ab",
    "version": "v1"
  }
}
```
If an error occurs in the external layer:
```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "FORBIDDEN",
    "message": "Access denied: Client lacks the required scope [ROSTER_READ] for this operation",
    "details": "Insufficient permissions"
  },
  "meta": {
    "timestamp": "2026-09-14T19:00:00",
    "requestId": "f8a9e1d2-bc34-4a21-9e87-1234567890ab",
    "version": "v1"
  }
}
```

---

## 4. Complete API Reference by Functional Module

---

### MODULE 1: AUTHENTICATION & USER SESSION

---

#### 1. User Login & Token Issuance

- **Purpose**: Authenticates admin or employee credentials and generates a signed JWT token containing user identity and assigned authorities.
- **Module**: Authentication
- **HTTP Method**: `POST`
- **Endpoint**: `/api/auth/login`
- **Authentication**: Public (No credentials required)
- **Headers**:
  - `Content-Type: application/json`
- **Path Parameters**: None.
- **Query Parameters**: None.
- **Request Body**:
  ```json
  {
    "username": "admin",
    "password": "Password123"
  }
  ```
  | Field | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `username` | `String` | Yes | Login username (`users.username`). Must not be blank. |
  | `password` | `String` | Yes | User account password. Must not be blank. |
- **Example Request**:
  ```bash
  curl -X POST "http://localhost:8080/api/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"username":"admin","password":"Password123"}'
  ```
- **Response Body**:
  ```json
  {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "type": "Bearer",
    "username": "admin",
    "role": "ROLE_ADMIN",
    "employeeId": null,
    "employeeCode": null,
    "displayName": "System Administrator"
  }
  ```
  | Field | Type | Description |
  | :--- | :--- | :--- |
  | `token` | `String` | Signed JWT Bearer token valid for 24 hours. |
  | `type` | `String` | Token scheme prefix, always `"Bearer"`. |
  | `username` | `String` | Authenticated username. |
  | `role` | `String` | Security role: `"ROLE_ADMIN"` or `"ROLE_EMPLOYEE"`. |
  | `employeeId` | `Long` | Associated `employees.id` (null for standalone admin). |
  | `employeeCode` | `String` | Employee code (e.g. `"EMP001"`). |
  | `displayName` | `String` | Full name of the authenticated user. |
- **Success Response**: `200 OK`
- **Error Responses**:
  - `401 UNAUTHORIZED`: Invalid username or password (`BadCredentialsException`).
  - `400 BAD_REQUEST`: Username or password omitted.
- **Dependencies**: Validates against `users` and `employees` tables.

---

#### 2. Get Current Authenticated Profile

- **Purpose**: Returns the profile, roles, and linked employee metadata of the currently authenticated JWT bearer.
- **Module**: Authentication
- **HTTP Method**: `GET`
- **Endpoint**: `/api/auth/me`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Headers**:
  - `Authorization: Bearer <JWT_TOKEN>`
- **Path Parameters**: None.
- **Query Parameters**: None.
- **Request Body**: No request body.
- **Response Body**:
  ```json
  {
    "username": "emp001",
    "role": "ROLE_EMPLOYEE",
    "employeeId": 1,
    "employeeCode": "EMP001",
    "firstName": "Rajat",
    "lastName": "Maurya",
    "email": "rajat@cris.org.in",
    "gender": "MALE"
  }
  ```
- **Success Response**: `200 OK`
- **Error Responses**:
  - `401 UNAUTHORIZED`: Missing, expired, or malformed JWT token.

---

#### 3. Change Account Password

- **Purpose**: Allows the authenticated user to change their login password with current password verification.
- **Module**: Authentication
- **HTTP Method**: `POST`
- **Endpoint**: `/api/auth/change-password`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Headers**:
  - `Authorization: Bearer <JWT_TOKEN>`
  - `Content-Type: application/json`
- **Request Body**:
  ```json
  {
    "currentPassword": "OldPassword123",
    "newPassword": "NewPassword456"
  }
  ```
  | Field | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `currentPassword` | `String` | Yes | Existing password for identity verification. |
  | `newPassword` | `String` | Yes | New password (minimum 6 characters). |
- **Response Body**:
  ```json
  {
    "success": true,
    "message": "Password changed successfully"
  }
  ```
- **Success Response**: `200 OK`
- **Error Responses**:
  - `400 BAD_REQUEST`: Current password incorrect or new password too short.

---

#### 4. User Logout

- **Purpose**: Signals user logout and records logout activity in system audit logs.
- **Module**: Authentication
- **HTTP Method**: `POST`
- **Endpoint**: `/api/auth/logout`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Headers**:
  - `Authorization: Bearer <JWT_TOKEN>`
- **Response Body**:
  ```json
  {
    "success": true,
    "message": "Logged out successfully"
  }
  ```
- **Success Response**: `200 OK`

---

### MODULE 2: EMPLOYEE PROFILE & DIRECTORY MANAGEMENT

---

#### 5. List All Employees

- **Purpose**: Retrieves the list of all registered employees in the system, including active and inactive records.
- **Module**: Employee Management
- **HTTP Method**: `GET`
- **Endpoint**: `/api/employees`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Headers**: `Authorization: Bearer <JWT_TOKEN>`
- **Query Parameters**: None.
- **Response Body**:
  ```json
  [
    {
      "id": 1,
      "employeeCode": "EMP001",
      "firstName": "Rajat",
      "lastName": "Maurya",
      "email": "rajat@cris.org.in",
      "gender": "MALE",
      "active": true,
      "userId": 2,
      "username": "emp001"
    }
  ]
  ```
- **Success Response**: `200 OK`

---

#### 6. List Active Employees Only

- **Purpose**: Retrieves only active employees eligible for roster scheduling.
- **Module**: Employee Management
- **HTTP Method**: `GET`
- **Endpoint**: `/api/employees/active`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Headers**: `Authorization: Bearer <JWT_TOKEN>`
- **Response Body**: Array of `EmployeeResponse` objects where `active == true`.
- **Success Response**: `200 OK`

---

#### 7. Get Employee By ID

- **Purpose**: Retrieves full employee details by database primary key.
- **Module**: Employee Management
- **HTTP Method**: `GET`
- **Endpoint**: `/api/employees/{id}`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Headers**: `Authorization: Bearer <JWT_TOKEN>`
- **Path Parameters**:
  | Parameter | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `id` | `Long` | Yes | Employee primary key (`employees.id`). |
- **Success Response**: `200 OK` with `EmployeeResponse`.
- **Error Responses**: `404 NOT_FOUND` if employee does not exist.

---

#### 8. Create New Employee

- **Purpose**: Registers a new employee and optionally provisions their login user account.
- **Module**: Employee Management
- **HTTP Method**: `POST`
- **Endpoint**: `/api/employees`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Headers**:
  - `Authorization: Bearer <JWT_TOKEN>`
  - `Content-Type: application/json`
- **Request Body**:
  ```json
  {
    "employeeCode": "EMP008",
    "firstName": "Pooja",
    "lastName": "Sharma",
    "email": "pooja.sharma@cris.org.in",
    "gender": "FEMALE",
    "active": true,
    "username": "emp008",
    "password": "Password123"
  }
  ```
  | Field | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `employeeCode` | `String` | Yes | Unique alphanumeric employee code (e.g. `EMP008`). |
  | `firstName` | `String` | Yes | Employee's given name. |
  | `lastName` | `String` | No | Employee's surname. |
  | `email` | `String` | Yes | Valid unique email address. |
  | `gender` | `String` | Yes | `"MALE"` or `"FEMALE"` (enforces night shift restrictions). |
  | `active` | `Boolean`| No | Active status for roster generation (default `true`). |
  | `username` | `String` | No | User account username if creating credentials. |
  | `password` | `String` | No | Initial user account password. |
- **Success Response**: `201 CREATED` with `EmployeeResponse`.
- **Error Responses**:
  - `400 BAD_REQUEST`: Validation failure or duplicate employee code / email.
  - `409 CONFLICT`: Data integrity constraint violation.

---

#### 9. Update Employee

- **Purpose**: Modifies an existing employee record.
- **Module**: Employee Management
- **HTTP Method**: `PUT`
- **Endpoint**: `/api/employees/{id}`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Headers**:
  - `Authorization: Bearer <JWT_TOKEN>`
  - `Content-Type: application/json`
- **Path Parameters**:
  | Parameter | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `id` | `Long` | Yes | Employee ID to update. |
- **Request Body**: Same structure as `EmployeeRequest`.
- **Success Response**: `200 OK` with updated `EmployeeResponse`.

---

#### 10. Toggle Employee Active Status

- **Purpose**: Activates or deactivates an employee without deleting historical roster assignments.
- **Module**: Employee Management
- **HTTP Method**: `PUT`
- **Endpoint**: `/api/employees/{id}/toggle`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Headers**: `Authorization: Bearer <JWT_TOKEN>`
- **Path Parameters**: `id` (`Long`)
- **Success Response**: `200 OK` with empty body.

---

#### 11. Delete Employee

- **Purpose**: Deletes an employee record and associated credentials if no historical roster constraints prevent it.
- **Module**: Employee Management
- **HTTP Method**: `DELETE`
- **Endpoint**: `/api/employees/{id}`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Headers**: `Authorization: Bearer <JWT_TOKEN>`
- **Path Parameters**: `id` (`Long`)
- **Success Response**: `204 NO_CONTENT`

---

#### 12. Submit Employee Profile Change Request

- **Purpose**: Allows an employee to request updates to their profile (e.g. phone, email, surname) subject to admin approval.
- **Module**: Employee Management
- **HTTP Method**: `POST`
- **Endpoint**: `/api/profile-change-requests`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Headers**: `Authorization: Bearer <JWT_TOKEN>`, `Content-Type: application/json`
- **Request Body**:
  ```json
  {
    "fieldName": "email",
    "requestedValue": "new.email@cris.org.in",
    "reason": "Official domain update"
  }
  ```
- **Success Response**: `201 CREATED` with `ProfileChangeRequestResponse`.

---

#### 13. Admin Approve Profile Change Request

- **Purpose**: Approves a pending profile change request and immediately applies the new value to the `employees` record.
- **Module**: Employee Management
- **HTTP Method**: `POST` (and `PUT`)
- **Endpoint**: `/api/admin/profile-change-requests/{id}/approve`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Path Parameters**: `id` (`Long`) - Request ID.
- **Request Body**:
  ```json
  {
    "adminRemarks": "Verified official notification"
  }
  ```
- **Success Response**: `200 OK` with updated `ProfileChangeRequestResponse`.

---

#### 14. Admin Reject Profile Change Request

- **Purpose**: Rejects a pending profile change request with administrative justification.
- **Module**: Employee Management
- **HTTP Method**: `POST` (and `PUT`)
- **Endpoint**: `/api/admin/profile-change-requests/{id}/reject`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Path Parameters**: `id` (`Long`)
- **Request Body**: `{"adminRemarks": "Invalid documentation provided"}`
- **Success Response**: `200 OK` with updated `ProfileChangeRequestResponse`.

---

### MODULE 3: SHIFT MANAGEMENT & CAPACITY CONFIGURATION

---

#### 15. List All Shifts

- **Purpose**: Returns all configured operational shifts with their assigned staffing capacity and active status.
- **Module**: Shift Configuration
- **HTTP Method**: `GET`
- **Endpoint**: `/api/shifts`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Headers**: `Authorization: Bearer <JWT_TOKEN>`
- **Response Body**:
  ```json
  [
    {
      "id": 1,
      "shiftType": "MORNING",
      "capacity": 2,
      "active": true,
      "startTime": "07:00",
      "endTime": "15:00"
    },
    {
      "id": 2,
      "shiftType": "GENERAL",
      "capacity": 2,
      "active": true,
      "startTime": "09:30",
      "endTime": "18:00"
    },
    {
      "id": 3,
      "shiftType": "EVENING",
      "capacity": 2,
      "active": true,
      "startTime": "14:00",
      "endTime": "22:00"
    },
    {
      "id": 4,
      "shiftType": "NIGHT",
      "capacity": 1,
      "active": true,
      "startTime": "22:00",
      "endTime": "07:00"
    }
  ]
  ```
- **Success Response**: `200 OK`

---

#### 16. Update Shift Capacity

- **Purpose**: Updates the minimum required staffing capacity for a shift type.
- **Module**: Shift Configuration
- **HTTP Method**: `PUT`
- **Endpoint**: `/api/shifts/{id}`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Headers**: `Authorization: Bearer <JWT_TOKEN>`, `Content-Type: application/json`
- **Path Parameters**: `id` (`Long`) - Shift ID.
- **Request Body**:
  ```json
  {
    "capacity": 3,
    "active": true
  }
  ```
- **Success Response**: `200 OK` with updated `ShiftResponse`.

---

### MODULE 4: CORE ROSTER ENGINE & SCHEDULE GENERATION

---

#### 17. Generate Weekly Roster

- **Purpose**: Executes the constraint-satisfaction scheduling algorithm to generate a 7-day roster cycle starting from the specified date.
- **Module**: Roster Engine
- **HTTP Method**: `POST`
- **Endpoint**: `/api/rosters/generate`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Headers**: `Authorization: Bearer <JWT_TOKEN>`
- **Query Parameters**:
  | Parameter | Type | Required | Description | Example |
  | :--- | :--- | :--- | :--- | :--- |
  | `startDate` | `String` | No | Cycle start date (YYYY-MM-DD). Defaults to next calendar day. | `2026-09-15` |
  | `regenerate`| `Boolean`| No | If true, regenerates an existing cycle, preserving manual overrides. | `true` |
- **Constraints Applied**:
  - Minimum shift staffing capacity satisfied per shift configuration.
  - Female staff assigned strictly to Morning and General shifts (Night/Evening prohibited).
  - Strict rest interval: Zero direct transitions from Night shift into Morning shift.
  - Maximum 2 consecutive night shifts per employee.
  - Balanced weekly off distribution across workforce.
  - Approved leaves automatically reflected as on-leave.
- **Response Body**: Returns full `RosterCycleResponse` with 49 assignments (7 employees Ã— 7 days).
- **Success Response**: `200 OK`

---

#### 18. Check Existing Cycle Conflict

- **Purpose**: Checks whether a roster cycle already exists covering a specific date range prior to generation.
- **Module**: Roster Engine
- **HTTP Method**: `GET`
- **Endpoint**: `/api/rosters/check-existing`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Query Parameters**: `startDate` (`String`, e.g. `2026-09-15`)
- **Response Body**:
  ```json
  {
    "exists": true,
    "cycleId": 1,
    "startDate": "2026-09-15",
    "endDate": "2026-09-21",
    "conflict": true
  }
  ```
- **Success Response**: `200 OK`

---

#### 19. Get Roster Cycle By ID

- **Purpose**: Retrieves a specific roster cycle including all 49 assignments, override details, and shift statistics.
- **Module**: Roster Engine
- **HTTP Method**: `GET`
- **Endpoint**: `/api/rosters/cycle/{id}`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Path Parameters**: `id` (`Long`) - Roster Cycle ID.
- **Success Response**: `200 OK` with `RosterCycleResponse`.

---

#### 20. Optimize Existing Roster Cycle

- **Purpose**: Re-executes the soft-constraint optimization engine on an existing cycle to improve preference match score.
- **Module**: Roster Engine
- **HTTP Method**: `POST`
- **Endpoint**: `/api/rosters/cycle/{id}/optimize`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Path Parameters**: `id` (`Long`)
- **Success Response**: `200 OK` with updated `RosterCycleResponse`.

---

#### 21. Publish Roster Cycle

- **Purpose**: Transitions a roster cycle from `TENTATIVE` to `PUBLISHED` state and broadcasts notifications to all employees.
- **Module**: Roster Engine
- **HTTP Method**: `POST`
- **Endpoint**: `/api/rosters/cycle/{id}/publish`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Path Parameters**: `id` (`Long`)
- **Success Response**: `200 OK` with `RosterCycleResponse`.

---

#### 22. Lock Roster Cycle

- **Purpose**: Locks a published roster cycle, preventing any further non-administrative edits.
- **Module**: Roster Engine
- **HTTP Method**: `POST`
- **Endpoint**: `/api/rosters/cycle/{id}/lock`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Path Parameters**: `id` (`Long`)
- **Success Response**: `200 OK` with `RosterCycleResponse`.

---

#### 23. Unlock Roster Cycle

- **Purpose**: Unlocks a locked roster cycle with mandatory operational justification.
- **Module**: Roster Engine
- **HTTP Method**: `POST`
- **Endpoint**: `/api/rosters/cycle/{id}/unlock`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Path Parameters**: `id` (`Long`)
- **Request Body**: `{"reason": "Emergency schedule reconfiguration"}`
- **Success Response**: `200 OK` with `RosterCycleResponse`.

---

#### 24. Get Shift Assignment Decision Explanation

- **Purpose**: Returns the AI/algorithmic audit reasoning behind a specific shift assignment decision.
- **Module**: Roster Engine
- **HTTP Method**: `GET`
- **Endpoint**: `/api/rosters/assignments/{assignmentId}/explanation`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Path Parameters**: `assignmentId` (`Long`)
- **Response Body**:
  ```json
  {
    "assignmentId": 12,
    "employeeName": "Rajat Maurya",
    "shiftType": "MORNING",
    "rosterDate": "2026-09-16",
    "explanation": "Assigned Morning shift based on 12-hour rest interval satisfaction and preferred shift request.",
    "rulesSatisfied": ["REST_INTERVAL_12H", "PREFERENCE_MATCH", "CAPACITY_TARGET"],
    "fairnessScore": 96.5
  }
  ```
- **Success Response**: `200 OK`

---

#### 25. Override Shift Assignment

- **Purpose**: Manually overrides an employee's assigned shift with mandatory justification and audit logging.
- **Module**: Roster Engine
- **HTTP Method**: `POST`
- **Endpoint**: `/api/rosters/overrides`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Headers**: `Authorization: Bearer <JWT_TOKEN>`, `Content-Type: application/json`
- **Request Body**:
  ```json
  {
    "assignmentId": 14,
    "shiftType": "GENERAL",
    "weeklyOff": false,
    "reason": "Operational surge coverage in General shift"
  }
  ```
  | Field | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `assignmentId` | `Long` | Yes | Target assignment primary key. |
  | `shiftType` | `String` | Yes | New shift type (`MORNING`, `GENERAL`, `EVENING`, `NIGHT`, `OFF`). |
  | `weeklyOff` | `Boolean`| Yes | Set true if assigning a rest day. |
  | `reason` | `String` | Yes | Administrative justification. |
- **Success Response**: `200 OK` with updated `RosterAssignmentResponse`.

---

#### 26. Atomic Shift Swap

- **Purpose**: Atomically exchanges shift assignments between two employees on the same date with rollback safety.
- **Module**: Roster Engine
- **HTTP Method**: `POST`
- **Endpoint**: `/api/rosters/swap`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Headers**: `Authorization: Bearer <JWT_TOKEN>`, `Content-Type: application/json`
- **Request Body**:
  ```json
  {
    "assignment1Id": 14,
    "assignment2Id": 15,
    "reason": "Mutual staff availability swap approved by supervisor"
  }
  ```
- **Response Body**: Array of 2 updated `RosterAssignmentResponse` records.
- **Success Response**: `200 OK`

---

#### 27. Get Current User's Today Duty

- **Purpose**: Returns the real-time duty assignment for the authenticated employee for the current calendar date.
- **Module**: Roster Engine
- **HTTP Method**: `GET`
- **Endpoint**: `/api/rosters/my-duty/today`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Response Body**:
  ```json
  {
    "date": "2026-09-14",
    "shiftType": "MORNING",
    "shiftTiming": "07:00 - 15:00",
    "weeklyOff": false,
    "onLeave": false,
    "relieverName": "Pooja Sharma",
    "cycleId": 1
  }
  ```
- **Success Response**: `200 OK`

---

#### 28. Get Effective Duty on Date

- **Purpose**: Computes effective duty for an employee on a target date, factoring in overrides, leaves, and swaps.
- **Module**: Roster Engine
- **HTTP Method**: `GET`
- **Endpoint**: `/api/rosters/effective-duty`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Query Parameters**:
  | Parameter | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `employeeId`| `Long` | Yes | Target employee ID. |
  | `date` | `String` | No | Target date (YYYY-MM-DD). Defaults to today. |
- **Success Response**: `200 OK` with `TodayDutyResponse`.

---

#### 29. Get Employee Roster Schedule

- **Purpose**: Retrieves all roster assignments for a specific employee across the active cycle.
- **Module**: Roster Engine
- **HTTP Method**: `GET`
- **Endpoint**: `/api/rosters/employee/{employeeId}`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Path Parameters**: `employeeId` (`Long`)
- **Success Response**: `200 OK` with `List<RosterAssignmentResponse>`.

---

#### 30. Scheduler Status & Upcoming Automatic Cycle

- **Purpose**: Returns the status of the automated Sunday 09:00 IST generation cron scheduler.
- **Module**: Roster Engine
- **HTTP Method**: `GET`
- **Endpoint**: `/api/rosters/scheduler/status`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Response Body**:
  ```json
  {
    "enabled": true,
    "cronExpression": "0 0 9 * * SUN",
    "timezone": "Asia/Kolkata",
    "nextExecutionTime": "2026-09-20T09:00:00",
    "upcomingCycleStartDate": "2026-09-21"
  }
  ```
- **Success Response**: `200 OK`

---

### MODULE 5: ROSTER CHANGE IMPACT PREVIEW & ATOMIC SWAPS

---

#### 31. Preview Assignment Change Impact (GET)

- **Purpose**: Simulates the effect of changing an assignment on shift capacity, rest rules, and health scores before committing.
- **Module**: Change Impact Analysis
- **HTTP Method**: `GET`
- **Endpoint**: `/api/rosters/impact-preview/assignment/{assignmentId}`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Path Parameters**: `assignmentId` (`Long`)
- **Query Parameters**:
  | Parameter | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `newShiftType`| `String` | Yes | Proposed shift type. |
  | `weeklyOff` | `Boolean`| No | Set true for proposed rest day. |
- **Response Body**:
  ```json
  {
    "assignmentId": 14,
    "currentShiftType": "MORNING",
    "proposedShiftType": "NIGHT",
    "valid": false,
    "ruleViolations": [
      "REST_INTERVAL_VIOLATION: Employee has Morning duty following this date."
    ],
    "capacityDelta": -1,
    "healthScoreBefore": 98,
    "healthScoreAfter": 82
  }
  ```
- **Success Response**: `200 OK`

---

#### 32. Apply Assignment Change with Impact Check (POST)

- **Purpose**: Applies an assignment change directly from the impact preview evaluation modal.
- **Module**: Change Impact Analysis
- **HTTP Method**: `POST`
- **Endpoint**: `/api/rosters/impact-preview/assignment/{assignmentId}/apply`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Path Parameters**: `assignmentId` (`Long`)
- **Request Body**:
  ```json
  {
    "newShiftType": "GENERAL",
    "weeklyOff": false,
    "reason": "Impact preview verified: Zero violations"
  }
  ```
- **Success Response**: `200 OK` with `RosterAssignmentResponse`.

---

### MODULE 6: ROSTER REVIEW & EMPLOYEE CORRECTION WINDOW

---

#### 33. Get Employee Review Summary

- **Purpose**: Returns the employee review window status, active cycle countdown, and submission deadlines.
- **Module**: Roster Review
- **HTTP Method**: `GET`
- **Endpoint**: `/api/roster-review/summary`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Success Response**: `200 OK` with `EmployeeRosterReviewSummaryResponse`.

---

#### 34. Submit Shift Correction Request

- **Purpose**: Allows an employee to submit a correction request during the 24-hour review window.
- **Module**: Roster Review
- **HTTP Method**: `POST`
- **Endpoint**: `/api/roster-review/request`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Request Body**:
  ```json
  {
    "assignmentId": 18,
    "requestedShiftType": "GENERAL",
    "requestedWeeklyOff": false,
    "reason": "Medical follow-up scheduled in early morning"
  }
  ```
- **Success Response**: `201 CREATED` with `RosterChangeRequestResponse`.

---

#### 35. Cancel Correction Request

- **Purpose**: Cancels a previously submitted correction request while still pending review.
- **Module**: Roster Review
- **HTTP Method**: `DELETE`
- **Endpoint**: `/api/roster-review/request/{id}`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Path Parameters**: `id` (`Long`)
- **Success Response**: `200 OK` with `RosterChangeRequestResponse`.

---

#### 36. Mark Employee Review Complete

- **Purpose**: Signals that the employee has reviewed and acknowledged their weekly roster schedule.
- **Module**: Roster Review
- **HTTP Method**: `POST`
- **Endpoint**: `/api/roster-review/mark-complete`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Success Response**: `200 OK` returning `true`.

---

#### 37. Admin Review Window Team Summary

- **Purpose**: Provides administrators with an aggregated view of team review acknowledgments and pending correction requests.
- **Module**: Roster Review
- **HTTP Method**: `GET`
- **Endpoint**: `/api/roster-review/admin/summary`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Success Response**: `200 OK` with `TeamRosterReviewSummaryResponse`.

---

#### 38. Admin Approve Correction Request

- **Purpose**: Approves an employee's roster correction request, immediately updating the assignment.
- **Module**: Roster Review
- **HTTP Method**: `POST`
- **Endpoint**: `/api/roster-review/admin/request/{id}/approve`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Path Parameters**: `id` (`Long`)
- **Request Body**: `{"adminRemarks": "Approved per medical document verification"}`
- **Success Response**: `200 OK` with updated `RosterChangeRequestResponse`.

---

#### 39. Admin Reject Correction Request

- **Purpose**: Rejects an employee correction request with justification.
- **Module**: Roster Review
- **HTTP Method**: `POST`
- **Endpoint**: `/api/roster-review/admin/request/{id}/reject`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Path Parameters**: `id` (`Long`)
- **Request Body**: `{"adminRemarks": "Capacity shortfall prevents shift swap"}`
- **Success Response**: `200 OK` with updated `RosterChangeRequestResponse`.

### MODULE 7: ROSTER VERIFICATION & SAFETY RULES VALIDATION

---

#### 40. Validate Active Roster Cycle

- **Purpose**: Runs a complete verification scan on the currently active roster cycle against all hard safety constraints and operational regulations.
- **Module**: Roster Validation
- **HTTP Method**: `GET`
- **Endpoint**: `/api/admin/validation/active`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Headers**: `Authorization: Bearer <JWT_TOKEN>`
- **Rules Evaluated**:
  - `MIN_CAPACITY`: Minimum required staffing on every shift each day.
  - `GENDER_RULE`: Prohibits female personnel on Night and Evening shifts.
  - `REST_INTERVAL`: 12-hour minimum rest window (Zero direct Night -> Morning transitions).
  - `MAX_NIGHT_SHIFTS`: Maximum 2 consecutive night shifts per employee.
  - `LEAVE_OVERLAP`: Verifies no employee is assigned duty while on approved leave.
- **Response Body**:
  ```json
  {
    "cycleId": 1,
    "valid": true,
    "healthScore": 100,
    "violationsCount": 0,
    "violations": [],
    "warnings": [],
    "checkedAt": "2026-09-14T19:05:00"
  }
  ```
- **Success Response**: `200 OK`

---

#### 41. Validate Roster Cycle By ID

- **Purpose**: Performs the comprehensive validation scan on a specific roster cycle ID.
- **Module**: Roster Validation
- **HTTP Method**: `GET` (and `POST`)
- **Endpoint**: `/api/admin/validation/cycle/{cycleId}`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Path Parameters**:
  | Parameter | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `cycleId` | `Long` | Yes | Roster cycle ID to audit. |
- **Success Response**: `200 OK` with `RosterValidationResponse`.

---

#### 42. Validate Roster Parametric Search

- **Purpose**: Validates either a specific cycle or the active cycle using request parameter resolution.
- **Module**: Roster Validation
- **HTTP Method**: `GET`
- **Endpoint**: `/api/admin/validation`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Query Parameters**:
  | Parameter | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `cycleId` | `Long` | No | Optional cycle ID; defaults to current active cycle. |
- **Success Response**: `200 OK` with `RosterValidationResponse`.

---

### MODULE 8: ROSTER VERSION HISTORY, DIFF & SAFE ROLLBACK

---

#### 43. List Versions of Roster Cycle

- **Purpose**: Retrieves all historical snapshots and version checkpoints created for a roster cycle.
- **Module**: Roster Versions
- **HTTP Method**: `GET`
- **Endpoint**: `/api/admin/roster-versions/cycle/{cycleId}`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Path Parameters**: `cycleId` (`Long`)
- **Response Body**:
  ```json
  [
    {
      "id": 101,
      "cycleId": 1,
      "versionNumber": 1,
      "versionAction": "INITIAL_GENERATION",
      "actionReason": "Automated Sunday generation",
      "createdTimestamp": "2026-09-14T09:00:00",
      "createdBy": "system_scheduler",
      "affectedAssignmentsCount": 49,
      "healthScore": 100
    },
    {
      "id": 102,
      "cycleId": 1,
      "versionNumber": 2,
      "versionAction": "SHIFT_OVERRIDE",
      "actionReason": "Assigned Morning duty to EMP002",
      "createdTimestamp": "2026-09-14T11:20:00",
      "createdBy": "admin",
      "affectedAssignmentsCount": 1,
      "healthScore": 98
    }
  ]
  ```
- **Success Response**: `200 OK`

---

#### 44. Compare Two Versions (Cycle Context)

- **Purpose**: Generates a side-by-side visual diff of assignments and health metrics between two versions of a cycle.
- **Module**: Roster Versions
- **HTTP Method**: `GET`
- **Endpoint**: `/api/admin/roster-versions/cycle/{cycleId}/compare`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Path Parameters**: `cycleId` (`Long`)
- **Query Parameters**:
  | Parameter | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `v1` | `int` | Yes | Baseline version number (e.g. `1`). |
  | `v2` | `int` | Yes | Target version number (e.g. `2`). |
- **Response Body**:
  ```json
  {
    "cycleId": 1,
    "v1": 1,
    "v2": 2,
    "differencesCount": 1,
    "assignmentDiffs": [
      {
        "employeeCode": "EMP002",
        "employeeName": "Pooja Sharma",
        "rosterDate": "2026-09-15",
        "oldShift": "GENERAL",
        "newShift": "MORNING",
        "reason": "Administrative override"
      }
    ],
    "healthScoreDiff": -2
  }
  ```
- **Success Response**: `200 OK` with `VersionComparisonResponse`.

---

#### 45. Preview Safe Rollback

- **Purpose**: Pre-validates a rollback to a target version number, testing constraint feasibility before creating a new revision.
- **Module**: Roster Versions
- **HTTP Method**: `GET`
- **Endpoint**: `/api/admin/roster-versions/cycle/{cycleId}/rollback-preview/{targetVersionNumber}`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Path Parameters**:
  | Parameter | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `cycleId` | `Long` | Yes | Target cycle ID. |
  | `targetVersionNumber` | `int` | Yes | Historical version to preview rollback to. |
- **Response Body**:
  ```json
  {
    "cycleId": 1,
    "currentVersionNumber": 2,
    "targetVersionNumber": 1,
    "feasible": true,
    "ruleViolations": [],
    "affectedAssignments": 1,
    "projectedHealthScore": 100
  }
  ```
- **Success Response**: `200 OK`

---

#### 46. Execute Safe Rollback

- **Purpose**: Safely rolls back assignments to match a historical version, creating a brand new version record without destroying history.
- **Module**: Roster Versions
- **HTTP Method**: `POST`
- **Endpoint**: `/api/admin/roster-versions/cycle/{cycleId}/rollback/{targetVersionNumber}`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Headers**: `Authorization: Bearer <JWT_TOKEN>`, `Content-Type: application/json`
- **Request Body**:
  ```json
  {
    "reason": "Restoring baseline schedule prior to scheduling dispute"
  }
  ```
- **Success Response**: `200 OK` with newly created `RosterVersionResponse` (V3).

---

### MODULE 9: LEAVE REQUEST LIFECYCLE & APPROVALS

---

#### 47. Apply For Leave

- **Purpose**: Submits a new leave request. Automatically verifies non-overlapping leave and updates future roster assignments upon approval.
- **Module**: Leave Management
- **HTTP Method**: `POST`
- **Endpoint**: `/api/leaves`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Headers**: `Authorization: Bearer <JWT_TOKEN>`, `Content-Type: application/json`
- **Request Body**:
  ```json
  {
    "employeeId": 1,
    "startDate": "2026-09-18",
    "endDate": "2026-09-19",
    "reason": "Personal medical appointment"
  }
  ```
  | Field | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `employeeId` | `Long` | Yes | Target employee ID (`employees.id`). |
  | `startDate` | `String` | Yes | Leave starting date (YYYY-MM-DD). |
  | `endDate` | `String` | Yes | Leave end date (inclusive). |
  | `reason` | `String` | Yes | Justification for absence. |
- **Success Response**: `201 CREATED` with `LeaveResponse`.

---

#### 48. Request Leave Modification

- **Purpose**: Submits a formal request to alter dates or reason of an approved or pending leave.
- **Module**: Leave Management
- **HTTP Method**: `POST`
- **Endpoint**: `/api/leaves/{id}/modification`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Path Parameters**: `id` (`Long`) - Leave request ID.
- **Request Body**:
  ```json
  {
    "startDate": "2026-09-18",
    "endDate": "2026-09-20",
    "reason": "Extended recovery recommended by doctor"
  }
  ```
- **Success Response**: `200 OK` with updated `LeaveResponse`.

---

#### 49. Request Leave Cancellation

- **Purpose**: Submits a request to cancel an approved leave, releasing dates back to the available duty pool.
- **Module**: Leave Management
- **HTTP Method**: `POST`
- **Endpoint**: `/api/leaves/{id}/cancellation`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Path Parameters**: `id` (`Long`)
- **Request Body**:
  ```json
  {
    "reason": "Appointment rescheduled by hospital"
  }
  ```
- **Success Response**: `200 OK` with updated `LeaveResponse`.

---

#### 50. Get Employee's Leave History

- **Purpose**: Retrieves all leave requests (Pending, Approved, Rejected, Cancelled) for a given employee.
- **Module**: Leave Management
- **HTTP Method**: `GET`
- **Endpoint**: `/api/leaves/my/{employeeId}`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Path Parameters**: `employeeId` (`Long`)
- **Success Response**: `200 OK` with `List<LeaveResponse>`.

---

#### 51. List All Pending Leaves

- **Purpose**: Retrieves all leaves currently awaiting administrative review.
- **Module**: Leave Management
- **HTTP Method**: `GET`
- **Endpoint**: `/api/leaves/pending`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Success Response**: `200 OK` with `List<LeaveResponse>`.

---

### MODULE 10: SHIFT AVAILABILITY & EMPLOYEE PREFERENCES

---

#### 52. Submit Shift Preferences

- **Purpose**: Submits preferred shifts, avoided shifts, and preferred rest days for upcoming roster cycles.
- **Module**: Shift Preferences
- **HTTP Method**: `POST`
- **Endpoint**: `/api/preferences`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Request Body**:
  ```json
  {
    "preferredShifts": "MORNING,GENERAL",
    "avoidShifts": "NIGHT",
    "preferredOffDays": "SUNDAY,SATURDAY",
    "preferredWorkDays": "MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY",
    "temporaryRestrictions": "Preparing for civil services evening exam",
    "effectiveFrom": "2026-09-15",
    "effectiveTo": "2026-10-15"
  }
  ```
- **Success Response**: `201 CREATED` with `PreferenceResponse`.

---

#### 53. Get Employee Active Preference

- **Purpose**: Returns the currently active approved preference record guiding the scheduling engine for the authenticated user.
- **Module**: Shift Preferences
- **HTTP Method**: `GET`
- **Endpoint**: `/api/preferences/my/active`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Success Response**: `200 OK` with `PreferenceResponse`.

---

#### 54. Admin Review Shift Preference Decision

- **Purpose**: Approves or rejects an employee shift availability preference.
- **Module**: Shift Preferences
- **HTTP Method**: `POST` (and `PUT`)
- **Endpoint**: `/api/admin/preferences/{id}/decision`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Path Parameters**: `id` (`Long`) - Preference ID.
- **Request Body**:
  ```json
  {
    "status": "APPROVED",
    "adminRemarks": "Approved subject to operational staffing sufficiency"
  }
  ```
- **Success Response**: `200 OK` with updated `PreferenceResponse`.

---

### MODULE 11: SHIFT HANDOVER & RELIEVER LOGS

---

#### 55. Create Shift Handover Note

- **Purpose**: Logs operational handover notes, completed checklist items, and pending critical tasks for the oncoming shift reliever.
- **Module**: Shift Handover
- **HTTP Method**: `POST`
- **Endpoint**: `/api/handovers`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Request Body**:
  ```json
  {
    "handoverDate": "2026-09-14",
    "shiftId": 1,
    "fromEmployeeId": 1,
    "toEmployeeId": 2,
    "summary": "Morning roster execution stable. Zero server incidents.",
    "priority": "MEDIUM",
    "pendingTasks": "Verify evening batch sync with Railway database at 18:00",
    "completedTasks": "Morning roster broadcast emails distributed",
    "importantNotes": "Employee EMP005 on sick leave"
  }
  ```
- **Success Response**: `201 CREATED` with `HandoverResponse`.

---

#### 56. Acknowledge Shift Handover

- **Purpose**: Allows oncoming relieving staff to formally acknowledge receipt and review of handover instructions.
- **Module**: Shift Handover
- **HTTP Method**: `POST` (and `PUT`)
- **Endpoint**: `/api/handovers/{id}/acknowledge`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Path Parameters**: `id` (`Long`)
- **Success Response**: `200 OK` with updated `HandoverResponse` (`status="ACKNOWLEDGED"`).

---

#### 57. List Incoming Handovers For Reliever

- **Purpose**: Retrieves all handover notes addressed specifically to the authenticated employee.
- **Module**: Shift Handover
- **HTTP Method**: `GET`
- **Endpoint**: `/api/handovers/incoming`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Success Response**: `200 OK` with `List<HandoverResponse>`.

---

### MODULE 12: SKILL MATRIX & EMPLOYEE QUALIFICATIONS

---

#### 58. List Master Skills Catalog

- **Purpose**: Returns the directory of all operational skills, competencies, and system certifications tracked by WRMS.
- **Module**: Skill Matrix
- **HTTP Method**: `GET`
- **Endpoint**: `/api/skills`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Success Response**: `200 OK` with `List<SkillResponse>`.

---

#### 59. Assign Skill to Employee

- **Purpose**: Maps a qualification or skill proficiency to an employee with optional certification validity dates.
- **Module**: Skill Matrix
- **HTTP Method**: `POST`
- **Endpoint**: `/api/admin/skills/assign`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Request Body**:
  ```json
  {
    "employeeId": 1,
    "skillId": 4,
    "proficiencyLevel": "EXPERT",
    "certificationName": "Certified Incident Commander",
    "certificationExpiryDate": "2027-12-31",
    "certified": true
  }
  ```
- **Success Response**: `201 CREATED` with `EmployeeSkillResponse`.

---

#### 60. Get Employee Skill Matrix

- **Purpose**: Retrieves the global cross-matrix of all employees and their active skills for staffing feasibility analysis.
- **Module**: Skill Matrix
- **HTTP Method**: `GET`
- **Endpoint**: `/api/admin/skills/employee-matrix`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Success Response**: `200 OK` with `List<EmployeeSkillResponse>`.

---

### MODULE 13: COMPANY & OPERATIONAL HOLIDAYS

---

#### 61. List Upcoming Holidays

- **Purpose**: Returns official organizational holidays scheduled on or after the current calendar date.
- **Module**: Holiday Management
- **HTTP Method**: `GET`
- **Endpoint**: `/api/holidays/upcoming`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Response Body**:
  ```json
  [
    {
      "id": 1,
      "holidayName": "Gandhi Jayanti",
      "holidayDate": "2026-10-02",
      "description": "National Public Holiday",
      "active": true
    }
  ]
  ```
- **Success Response**: `200 OK`

---

#### 62. Create Holiday

- **Purpose**: Registers a new official holiday into the system calendar.
- **Module**: Holiday Management
- **HTTP Method**: `POST`
- **Endpoint**: `/api/admin/holidays`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Request Body**:
  ```json
  {
    "holidayName": "Diwali",
    "holidayDate": "2026-11-08",
    "description": "Festival Holiday - Minimum staffing mode",
    "active": true
  }
  ```
- **Success Response**: `201 CREATED` with `HolidayResponse`.

---

### MODULE 14: OPERATIONS DASHBOARD & LIVE OVERVIEW

---

#### 63. Get Dashboard KPI Summary

- **Purpose**: Aggregates high-level operational metrics including total active employees, active cycle date range, pending approvals, and today's duty roster.
- **Module**: Dashboard
- **HTTP Method**: `GET`
- **Endpoint**: `/api/dashboard`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Success Response**: `200 OK` with `DashboardResponse`.

---

#### 64. Get Dashboard Day View

- **Purpose**: Returns all employees grouped by assigned shift for a specific date (Morning, General, Evening, Night, Off, Leave).
- **Module**: Dashboard
- **HTTP Method**: `GET`
- **Endpoint**: `/api/dashboard/day-view`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Query Parameters**:
  | Parameter | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `date` | `String` | No | Target date (YYYY-MM-DD). Defaults to current date. |
- **Success Response**: `200 OK` with `DashboardDayViewResponse`.

---

#### 65. Get Dashboard Employee View

- **Purpose**: Returns the 7-day schedule of a specific employee or all employees for horizontal grid rendering.
- **Module**: Dashboard
- **HTTP Method**: `GET`
- **Endpoint**: `/api/dashboard/employee-view`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Query Parameters**:
  | Parameter | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `date` | `String` | No | Base date. Defaults to today. |
  | `employeeId`| `Long` | No | Optional filter for single employee. |
- **Success Response**: `200 OK` with `DashboardEmployeeViewResponse`.

### MODULE 15: SMART ROSTER COMMAND CENTER

---

#### 66. Get Command Center Active Cycle Summary

- **Purpose**: Aggregates comprehensive intelligence for the active roster cycle: staffing balance score, night duty fatigue index, gender compliance metrics, and automated recommendations.
- **Module**: Smart Command Center
- **HTTP Method**: `GET`
- **Endpoint**: `/api/command-center/summary`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Headers**: `Authorization: Bearer <JWT_TOKEN>`
- **Response Body**:
  ```json
  {
    "cycleId": 1,
    "startDate": "2026-09-15",
    "endDate": "2026-09-21",
    "overallHealthScore": 97.4,
    "fairnessScore": 95.8,
    "capacitySatisfaction": 100.0,
    "consecutiveNightAlerts": 0,
    "restIntervalViolations": 0,
    "femaleNightViolations": 0,
    "recommendations": [
      "Staffing distribution is optimal across all 4 shifts.",
      "Consider rotating EMP003 to General shift next week to balance night distribution."
    ]
  }
  ```
- **Success Response**: `200 OK`

---

#### 67. Get Command Center Cycle Summary by ID

- **Purpose**: Generates full command center intelligence for a specified cycle ID.
- **Module**: Smart Command Center
- **HTTP Method**: `GET`
- **Endpoint**: `/api/command-center/cycle/{cycleId}`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Path Parameters**: `cycleId` (`Long`)
- **Success Response**: `200 OK` with `SmartCommandCenterResponse`.

---

### MODULE 16: UNIFIED ADMIN APPROVALS CENTER

---

#### 68. Get Unified Approvals Summary & Badge Counts

- **Purpose**: Returns single-call counters of all pending approvals across leave requests, roster shift change requests, profile change requests, and availability preferences.
- **Module**: Unified Approvals
- **HTTP Method**: `GET`
- **Endpoint**: `/api/admin/approvals/summary`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Response Body**:
  ```json
  {
    "totalPending": 5,
    "pendingLeavesCount": 2,
    "pendingRosterChangesCount": 1,
    "profileRequestsCount": 1,
    "pendingPreferencesCount": 1
  }
  ```
- **Success Response**: `200 OK`

---

#### 69. Get All Pending Approvals Combined

- **Purpose**: Fetches the unified multi-category queue of all pending administrative items for single-screen decision making.
- **Module**: Unified Approvals
- **HTTP Method**: `GET`
- **Endpoint**: `/api/admin/approvals/all`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Response Body**: Returns `UnifiedApprovalsResponse` containing lists of pending leaves, roster changes, profile updates, and preferences.
- **Success Response**: `200 OK`

---

### MODULE 17: WORKLOAD DISTRIBUTION & SHIFT ANALYTICS

---

#### 70. Get Roster Workload Analytics

- **Purpose**: Analyzes cumulative shifts worked, night duties completed, weekly offs granted, and fairness metrics per staff member.
- **Module**: Workload & Analytics
- **HTTP Method**: `GET`
- **Endpoint**: `/api/admin/analytics`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Query Parameters**:
  | Parameter | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `cycleId` | `Long` | No | Optional target cycle. Defaults to active cycle. |
- **Success Response**: `200 OK` with `RosterAnalyticsResponse`.

---

#### 71. Get Workload Report

- **Purpose**: Generates detailed workload reports across custom date intervals.
- **Module**: Workload & Analytics
- **HTTP Method**: `GET`
- **Endpoint**: `/api/admin/workload`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Query Parameters**:
  | Parameter | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `startDate` | `String` | No | Beginning of evaluation interval. |
  | `endDate` | `String` | No | End of evaluation interval. |
- **Success Response**: `200 OK` with `WorkloadReportResponse`.

---

#### 72. Get Authenticated Employee Workload

- **Purpose**: Returns the personalized workload and duty balance metrics for the logged-in employee.
- **Module**: Workload & Analytics
- **HTTP Method**: `GET`
- **Endpoint**: `/api/workload/me`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Success Response**: `200 OK` with `WorkloadReportResponse`.

---

### MODULE 18: NOTIFICATION CENTER & REAL-TIME SSE STREAM

---

#### 73. Server-Sent Events (SSE) Real-Time Notification Stream

- **Purpose**: Establishes a persistent, push-based HTTP connection to stream real-time events (roster published, leave approved, handover received, mutation invalidation) directly to the browser.
- **Module**: Notifications & SSE
- **HTTP Method**: `GET`
- **Endpoint**: `/api/notifications/stream`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Headers**:
  - `Accept: text/event-stream`
  - `Authorization: Bearer <JWT_TOKEN>`
- **Behavior**:
  - Emits an initial heartbeat event: `{"event": "INIT", "data": "connected"}`.
  - Heartbeat interval: Every 30 seconds to prevent proxy / cloud timeout.
  - Client auto-reconnects on disconnection.
- **Response Format**: `text/event-stream;charset=UTF-8`

---

#### 74. Get My Notifications

- **Purpose**: Retrieves persistent in-app notifications for the authenticated employee with unread flags and deep links.
- **Module**: Notifications
- **HTTP Method**: `GET`
- **Endpoint**: `/api/notifications/my`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Response Body**:
  ```json
  [
    {
      "id": 101,
      "recipientUsername": "emp001",
      "title": "Roster Published",
      "message": "Weekly Roster for period 2026-09-15 to 2026-09-21 has been published.",
      "type": "ROSTER_PUBLISHED",
      "readStatus": false,
      "createdAt": "2026-09-14T09:05:00",
      "linkPage": "employeeWorkspace",
      "linkId": 1
    }
  ]
  ```
- **Success Response**: `200 OK`

---

#### 75. Get Unread Notification Count

- **Purpose**: Lightweight counter for updating navigation sidebar badge without payload overhead.
- **Module**: Notifications
- **HTTP Method**: `GET`
- **Endpoint**: `/api/notifications/unread-count`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Response Body**:
  ```json
  {
    "count": 3
  }
  ```
- **Success Response**: `200 OK`

---

#### 76. Mark Notification as Read

- **Purpose**: Marks an individual notification as read.
- **Module**: Notifications
- **HTTP Method**: `PUT`
- **Endpoint**: `/api/notifications/{id}/read`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Path Parameters**: `id` (`Long`)
- **Success Response**: `200 OK` with updated `NotificationResponse`.

---

#### 77. Mark All Notifications as Read

- **Purpose**: Batch marks all unread notifications for the user as read.
- **Module**: Notifications
- **HTTP Method**: `PUT`
- **Endpoint**: `/api/notifications/read-all`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Response Body**: `{"updated": 3}`
- **Success Response**: `200 OK`

---

### MODULE 19: EMPLOYEE ACTIVITY LOGS & SYSTEM AUDIT TRAILS

---

#### 78. List Global System Audit Logs

- **Purpose**: Administrative access to immutable audit log trails documenting shift overrides, logins, role adjustments, approvals, and system mutations.
- **Module**: Audit & Activity
- **HTTP Method**: `GET`
- **Endpoint**: `/api/audit-logs`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Query Parameters**:
  | Parameter | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `limit` | `Integer`| No | Max records to retrieve (default 100). |
- **Success Response**: `200 OK` with `List<AuditLogResponse>`.

---

#### 79. Get Authenticated Employee Personal Activity

- **Purpose**: Retrieves pagination-enabled log of user actions (logins, leave applications, profile change requests, shift handovers).
- **Module**: Audit & Activity
- **HTTP Method**: `GET`
- **Endpoint**: `/api/activities/my`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Success Response**: `200 OK` with `EmployeeActivityPageResponse`.

---

#### 80. Log Roster View Event

- **Purpose**: Records that the employee has opened and inspected the published weekly roster (compliance timestamping).
- **Module**: Audit & Activity
- **HTTP Method**: `POST`
- **Endpoint**: `/api/activities/view-roster`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Response Body**: `{"success": true, "timestamp": "2026-09-14T19:10:00"}`
- **Success Response**: `200 OK`

---

### MODULE 20: ENTERPRISE EXPORT CENTER (EXCEL, PNG, CSV)

---

#### 81. Export Roster to Excel (.xlsx)

- **Purpose**: Generates and streams a styled, formatted Microsoft Excel workbook of the 7-day schedule with shift statistics.
- **Module**: Export Center
- **HTTP Method**: `GET`
- **Endpoint**: `/api/rosters/cycle/{id}/export/excel`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Path Parameters**: `id` (`Long`) - Roster Cycle ID.
- **Headers**:
  - `Authorization: Bearer <JWT_TOKEN>`
- **Response**: Binary stream (`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`).
- **Filename**: `WRMS_Roster_Cycle_{id}.xlsx`
- **Success Response**: `200 OK`

---

#### 82. Export Roster to Image (.png)

- **Purpose**: Renders the complete roster grid as a high-resolution PNG image suitable for mobile messaging distribution (e.g. WhatsApp, Slack, Notice Boards).
- **Module**: Export Center
- **HTTP Method**: `GET`
- **Endpoint**: `/api/rosters/cycle/{id}/export/image`
- **Authentication**: Authenticated (`ROLE_ADMIN` or `ROLE_EMPLOYEE`)
- **Path Parameters**: `id` (`Long`)
- **Response**: Binary stream (`image/png`).
- **Filename**: `WRMS_Roster_Cycle_{id}.png`
- **Success Response**: `200 OK`

---

#### 83. Advanced Multi-Format Report Download

- **Purpose**: Export center endpoint supporting customizable entity extraction (Rosters, Employees, Leaves, Audit Logs) in Excel or CSV.
- **Module**: Export Center
- **HTTP Method**: `GET`
- **Endpoint**: `/api/admin/exports/download`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Query Parameters**:
  | Parameter | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `type` | `String` | Yes | Export type: `"ROSTER"`, `"EMPLOYEES"`, `"LEAVES"`, `"AUDIT"`. |
  | `format` | `String` | Yes | Target format: `"EXCEL"` or `"CSV"`. |
  | `cycleId`| `Long` | No | Roster cycle ID (required when type is `"ROSTER"`). |
- **Success Response**: `200 OK` with binary file download.

---

### MODULE 21: TRANSACTIONAL EMAIL INFRASTRUCTURE (BREVO & SMTP)

---

#### 84. Get Email Dispatcher Provider Status

- **Purpose**: Checks the active email transport provider (Primary: Brevo HTTPS REST API; Fallback: SMTP) and connection health.
- **Module**: Email Infrastructure
- **HTTP Method**: `GET`
- **Endpoint**: `/api/admin/email/provider-status`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Response Body**:
  ```json
  {
    "provider": "BREVO",
    "status": "OPERATIONAL",
    "senderEmail": "rajatkumarmaury@gmail.com",
    "senderName": "WRMS",
    "endpoint": "https://api.brevo.com/v3/smtp/email"
  }
  ```
- **Success Response**: `200 OK`

---

#### 85. Send Test Transactional Email

- **Purpose**: Dispatches a test transactional email to verify API key validity, domain verification, and inbox delivery.
- **Module**: Email Infrastructure
- **HTTP Method**: `POST`
- **Endpoint**: `/api/admin/email/test`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Query Parameters**:
  | Parameter | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `to` | `String` | No | Destination email address. Defaults to admin email. |
- **Response Body**:
  ```json
  {
    "success": true,
    "provider": "BREVO",
    "recipient": "rajatkumarmaury@gmail.com",
    "messageId": "<202609141340.123456@smtp-relay.brevo.com>",
    "timestamp": "2026-09-14T19:15:00"
  }
  ```
- **Success Response**: `200 OK`

---

### MODULE 22: SYSTEM HEALTH DIAGNOSTICS & ERROR ROUTING

---

#### 86. Public Health Check

- **Purpose**: Lightweight liveness check for Railway health checks, cloud container orchestrators, and uptime monitors.
- **Module**: System Health
- **HTTP Method**: `GET`
- **Endpoint**: `/api/public/health`
- **Authentication**: Public (No auth required)
- **Response Body**:
  ```json
  {
    "status": "UP",
    "application": "Weekly Roster Management System",
    "timestamp": "2026-09-14T19:15:00",
    "version": "1.0.0"
  }
  ```
- **Success Response**: `200 OK`

---

#### 87. Administrative Deep Health Diagnostics

- **Purpose**: Returns in-depth system metrics including database connectivity, HikariCP active pool usage, JVM memory, and active threads.
- **Module**: System Health
- **HTTP Method**: `GET`
- **Endpoint**: `/api/admin/system-health`
- **Authentication**: Admin Only (`ROLE_ADMIN`)
- **Response Body**:
  ```json
  {
    "status": "UP",
    "database": {
      "status": "UP",
      "product": "MySQL",
      "version": "8.0.35",
      "activeConnections": 1,
      "idleConnections": 5,
      "maxPoolSize": 6
    },
    "memory": {
      "heapUsedMb": 142,
      "heapMaxMb": 512
    },
    "uptimeSeconds": 18240
  }
  ```
- **Success Response**: `200 OK`

---

#### 88. Standard Error Dispatcher

- **Purpose**: Handles unhandled servlet container errors, 404s, and internal servlet exceptions.
- **Module**: System Health
- **HTTP Method**: Any (`GET`, `POST`, `PUT`, `DELETE`)
- **Endpoint**: `/error`
- **Authentication**: Public
- **Response Body**: Standard `Map<String, Object>` containing `status`, `error`, `message`, `timestamp`, `path`.

---

### MODULE 23: GLOBAL VISITOR ANALYTICS & LIVE ONLINE COUNTERS

---

#### 89. Get Visitor Statistics

- **Purpose**: Returns the cumulative total visitors count and currently active online visitors count.
- **Module**: Visitor Analytics
- **HTTP Method**: `GET`
- **Endpoint**: `/api/visitor-stats`
- **Authentication**: Public (No auth required)
- **Response Body**:
  ```json
  {
    "success": true,
    "totalVisits": 1234,
    "onlineNow": 7,
    "message": "OK"
  }
  ```
- **Success Response**: `200 OK`

---

#### 90. Record New Browser Visit

- **Purpose**: Atomically increments the global total visits counter in MySQL and registers the visitor's online presence.
- **Module**: Visitor Analytics
- **HTTP Method**: `POST`
- **Endpoint**: `/api/visitor-stats/visit`
- **Authentication**: Public (No auth required)
- **Headers**: `Content-Type: application/json`
- **Request Body**:
  ```json
  {
    "visitorId": "v_c78a912e-3456-4789-9012-34567890abcd"
  }
  ```
- **Response Body**: Updated `VisitorStatsResponse`.
- **Success Response**: `200 OK`

---

#### 91. Refresh Active Visitor Heartbeat

- **Purpose**: Updates the visitor's `last_seen_at` timestamp. Does NOT increment total visits counter.
- **Module**: Visitor Analytics
- **HTTP Method**: `POST`
- **Endpoint**: `/api/visitor-stats/heartbeat`
- **Authentication**: Public (No auth required)
- **Request Body**: `{"visitorId": "v_c78a912e..."}`
- **Success Response**: `200 OK`

---

#### 92. Terminate Active Visitor Session (Offline)

- **Purpose**: Immediately deletes the active visitor session upon browser/tab unload.
- **Module**: Visitor Analytics
- **HTTP Method**: `POST`
- **Endpoint**: `/api/visitor-stats/offline`
- **Authentication**: Public (No auth required)
- **Request Body**: `{"visitorId": "v_c78a912e..."}`
- **Success Response**: `200 OK`

---

### MODULE 24: EXTERNAL THIRD-PARTY INTEGRATION LAYER (v1 API)

---

#### 93. External API Health Ping

- **Purpose**: Verifies that third-party client API key authentication, rate limiting, and correlation tracking are operational.
- **Module**: External Integration Layer
- **HTTP Method**: `GET`
- **Endpoint**: `/api/external/v1/ping`
- **Authentication**: API Key Required (`X-API-Key` or `Bearer <key>`)
- **Headers**:
  - `X-API-Key: <SECRET_API_KEY>`
  - Optional `X-Request-ID: <UUID>`
- **Response Body**:
  ```json
  {
    "success": true,
    "data": {
      "status": "UP",
      "message": "WRMS External API v1 is operational",
      "timestamp": "2026-09-14T19:20:00",
      "client": "PayrollSystem-Integration"
    },
    "error": null,
    "meta": {
      "timestamp": "2026-09-14T19:20:00",
      "requestId": "9c8a1b2c-3d4e-5f6a-7b8c-9d0e1f2a3b4c",
      "version": "v1"
    }
  }
  ```
- **Success Response**: `200 OK`

---

#### 94. External List Active Shifts

- **Purpose**: Allows third-party systems to discover operational shift types, start/end hours, and staffing requirements.
- **Module**: External Integration Layer
- **HTTP Method**: `GET`
- **Endpoint**: `/api/external/v1/shifts`
- **Authentication**: API Key with `SHIFT_READ` scope
- **Response Body**:
  ```json
  {
    "success": true,
    "data": [
      {
        "shiftType": "MORNING",
        "capacity": 2,
        "active": true,
        "startTime": "07:00",
        "endTime": "15:00"
      },
      {
        "shiftType": "GENERAL",
        "capacity": 2,
        "active": true,
        "startTime": "09:30",
        "endTime": "18:00"
      },
      {
        "shiftType": "EVENING",
        "capacity": 2,
        "active": true,
        "startTime": "14:00",
        "endTime": "22:00"
      },
      {
        "shiftType": "NIGHT",
        "capacity": 1,
        "active": true,
        "startTime": "22:00",
        "endTime": "07:00"
      }
    ],
    "error": null,
    "meta": { ... }
  }
  ```
- **Success Response**: `200 OK`

---

#### 95. External List Employees

- **Purpose**: Exports employee directory to external HRMS or Payroll systems with optional active-status filtering.
- **Module**: External Integration Layer
- **HTTP Method**: `GET`
- **Endpoint**: `/api/external/v1/employees`
- **Authentication**: API Key with `EMPLOYEE_READ` scope
- **Query Parameters**:
  | Parameter | Type | Required | Description | Example |
  | :--- | :--- | :--- | :--- | :--- |
  | `activeOnly`| `Boolean`| No | Filter active employees only (default `true`). | `true` |
- **Response Body**: Array of `ExternalEmployeeResponse` records (`employeeCode`, `firstName`, `lastName`, `email`, `gender`, `active`).
- **Success Response**: `200 OK`

---

#### 96. External Get Employee by Code

- **Purpose**: Looks up an employee's details using their unique business identifier code.
- **Module**: External Integration Layer
- **HTTP Method**: `GET`
- **Endpoint**: `/api/external/v1/employees/{employeeCode}`
- **Authentication**: API Key with `EMPLOYEE_READ` scope
- **Path Parameters**:
  | Parameter | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `employeeCode` | `String` | Yes | Employee code (e.g. `EMP001`). |
- **Success Response**: `200 OK` with `ExternalEmployeeResponse`.
- **Error Responses**: `404 NOT_FOUND` with error code `RESOURCE_NOT_FOUND`.

---

#### 97. External Get Current Roster Schedule

- **Purpose**: Fetches the active 7-day roster cycle with all assignments for foreign time & attendance or clock-in integrations.
- **Module**: External Integration Layer
- **HTTP Method**: `GET`
- **Endpoint**: `/api/external/v1/rosters/current`
- **Authentication**: API Key with `ROSTER_READ` scope
- **Response Body**:
  ```json
  {
    "success": true,
    "data": {
      "cycleId": 1,
      "startDate": "2026-09-15",
      "endDate": "2026-09-21",
      "generationMode": "AUTOMATIC",
      "totalAssignments": 49,
      "assignments": [
        {
          "assignmentId": 101,
          "employeeCode": "EMP001",
          "employeeName": "Rajat Maurya",
          "rosterDate": "2026-09-15",
          "shiftType": "MORNING",
          "weeklyOff": false,
          "onLeave": false,
          "overridden": false
        }
      ]
    },
    "error": null,
    "meta": { ... }
  }
  ```
- **Success Response**: `200 OK`

---

#### 98. External Get Assignments by Date

- **Purpose**: Retrieves all staff assignments across shifts for a single date.
- **Module**: External Integration Layer
- **HTTP Method**: `GET`
- **Endpoint**: `/api/external/v1/rosters/by-date`
- **Authentication**: API Key with `ROSTER_READ` scope
- **Query Parameters**:
  | Parameter | Type | Required | Description | Example |
  | :--- | :--- | :--- | :--- | :--- |
  | `date` | `String` | Yes | Target date (YYYY-MM-DD). | `2026-09-15` |
- **Success Response**: `200 OK` with `List<ExternalRosterAssignmentResponse>`.

---

#### 99. External Get Roster for Specific Employee

- **Purpose**: Queries scheduled duties for a specific employee code across an optional date range.
- **Module**: External Integration Layer
- **HTTP Method**: `GET`
- **Endpoint**: `/api/external/v1/rosters/employee/{employeeCode}`
- **Authentication**: API Key with `ROSTER_READ` scope
- **Path Parameters**: `employeeCode` (`String`)
- **Query Parameters**:
  | Parameter | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `startDate` | `String` | No | Beginning of search window. |
  | `endDate` | `String` | No | End of search window. |
- **Success Response**: `200 OK` with `List<ExternalRosterAssignmentResponse>`.

---

#### 100. External List Approved Leaves

- **Purpose**: Retrieves approved leaves to feed into third-party payroll deduction or HRMS absence tracking engines.
- **Module**: External Integration Layer
- **HTTP Method**: `GET`
- **Endpoint**: `/api/external/v1/leaves`
- **Authentication**: API Key with `LEAVE_READ` scope
- **Query Parameters**:
  | Parameter | Type | Required | Description | Example |
  | :--- | :--- | :--- | :--- | :--- |
  | `startDate` | `String` | No | Filter leaves active on or after date. | `2026-09-01` |
  | `endDate` | `String` | No | Filter leaves active on or before date. | `2026-09-30` |
  | `employeeCode`| `String`| No | Filter by specific employee code. | `EMP001` |
- **Success Response**: `200 OK` with `List<ExternalLeaveResponse>`.

---

#### 101. Admin Manage External API Clients (CRUD)

- **Purpose**: Allows WRMS administrators to provision, view, toggle status, and revoke third-party client API keys.
- **Module**: External Integration Layer
- **Authentication**: Admin Only (`ROLE_ADMIN` via JWT)
- **Endpoints**:
  - `GET /api/admin/external-clients`: Lists all registered external integration clients.
  - `POST /api/admin/external-clients`: Creates a new external client. Generates a secure random 40-character secret API key displayed **only once** upon creation.
    ```json
    {
      "clientName": "Workday Payroll Service",
      "scopes": "ROSTER_READ,EMPLOYEE_READ,LEAVE_READ",
      "rateLimitPerMinute": 120
    }
    ```
  - `PUT /api/admin/external-clients/{id}/toggle`: Enables or disables client access immediately.
  - `DELETE /api/admin/external-clients/{id}`: Permanently revokes and deletes the client credentials.

## 5. Complete Data Transfer Object (DTO) Dictionary

This section catalogs the core Request and Response DTO structures powering WRMS.

### Core Authentication & User DTOs

#### `LoginRequest`
```java
public class LoginRequest {
    @NotBlank(message = "Username is required")
    private String username;
    
    @NotBlank(message = "Password is required")
    private String password;
}
```

#### `AuthResponse`
```java
public record AuthResponse(
    String token,
    String type,
    String username,
    String role,
    Long employeeId,
    String employeeCode,
    String displayName
) {}
```

#### `UserProfileResponse`
```java
public record UserProfileResponse(
    Long id,
    String username,
    String role,
    Long employeeId,
    String employeeCode,
    String firstName,
    String lastName,
    String email,
    String gender
) {}
```

---

### Core Employee DTOs

#### `EmployeeRequest`
```java
public record EmployeeRequest(
    String employeeCode,
    String firstName,
    String lastName,
    String email,
    String gender,       // MALE or FEMALE
    Boolean active,
    String username,     // Optional account link
    String password      // Optional initial password
) {}
```

#### `EmployeeResponse`
```java
public record EmployeeResponse(
    Long id,
    String employeeCode,
    String firstName,
    String lastName,
    String email,
    String gender,
    boolean active,
    Long userId,
    String username
) {}
```

---

### Core Roster & Scheduling DTOs

#### `RosterCycleResponse`
```java
public record RosterCycleResponse(
    Long id,
    LocalDate startDate,
    LocalDate endDate,
    LocalDateTime generatedAt,
    GenerationMode generationMode,
    List<RosterAssignmentResponse> assignments,
    boolean locked,
    String status
) {}
```

#### `RosterAssignmentResponse`
```java
public record RosterAssignmentResponse(
    Long id,
    Long cycleId,
    Long employeeId,
    String employeeCode,
    String employeeName,
    Long shiftId,
    String shiftType,
    LocalDate rosterDate,
    boolean weeklyOff,
    boolean onLeave,
    boolean overridden,
    String assignmentReason,
    String previousShiftType,
    String overrideReason,
    LocalDateTime overrideCreatedAt
) {}
```

#### `TodayDutyResponse`
```java
public record TodayDutyResponse(
    LocalDate date,
    String shiftType,
    String shiftTiming,
    boolean weeklyOff,
    boolean onLeave,
    String relieverName,
    Long cycleId
) {}
```

#### `RosterOverrideRequest`
```java
public record RosterOverrideRequest(
    Long assignmentId,
    String shiftType,
    Boolean weeklyOff,
    String reason
) {}
```

#### `RosterSwapRequest`
```java
public record RosterSwapRequest(
    Long assignment1Id,
    Long assignment2Id,
    String reason
) {}
```

---

### Core Leave DTOs

#### `ApplyLeaveRequest`
```java
public record ApplyLeaveRequest(
    Long employeeId,
    LocalDate startDate,
    LocalDate endDate,
    String reason
) {}
```

#### `LeaveResponse`
```java
public record LeaveResponse(
    Long id,
    Long employeeId,
    String employeeCode,
    String employeeName,
    LocalDate startDate,
    LocalDate endDate,
    String reason,
    LeaveStatus status,
    String adminRemarks,
    LocalDateTime requestedAt,
    LocalDateTime reviewedAt
) {}
```

---

### Core Shift Preferences & Handover DTOs

#### `PreferenceSubmitRequest`
```java
public record PreferenceSubmitRequest(
    String preferredShifts,
    String avoidShifts,
    String preferredOffDays,
    String preferredWorkDays,
    String temporaryRestrictions,
    String remarks,
    LocalDate effectiveFrom,
    LocalDate effectiveTo
) {}
```

#### `CreateHandoverRequest`
```java
public record CreateHandoverRequest(
    LocalDate handoverDate,
    Long shiftId,
    Long fromEmployeeId,
    Long toEmployeeId,
    String summary,
    String priority,
    String pendingTasks,
    String completedTasks,
    String importantNotes
) {}
```

---

### Core External API v1 DTOs

#### `ExternalApiResponse<T>`
```java
public record ExternalApiResponse<T>(
    boolean success,
    T data,
    ExternalApiError error,
    ExternalApiMeta meta
) {}
```

#### `ExternalRosterCycleResponse`
```java
public record ExternalRosterCycleResponse(
    Long cycleId,
    LocalDate startDate,
    LocalDate endDate,
    String generationMode,
    int totalAssignments,
    List<ExternalRosterAssignmentResponse> assignments
) {}
```

#### `ExternalRosterAssignmentResponse`
```java
public record ExternalRosterAssignmentResponse(
    Long assignmentId,
    String employeeCode,
    String employeeName,
    LocalDate rosterDate,
    String shiftType,
    boolean weeklyOff,
    boolean onLeave,
    boolean overridden
) {}
```

---

## 6. External Project Integration Guide & Best Practices

This guide provides practical instructions for integrating WRMS capabilities into **external host systems**, such as enterprise HRMS platforms, payroll computation engines, biometric clock-in devices, and mobile portals.

### Step 1: Client Onboarding & API Key Provisioning
1. Log into the WRMS Admin Portal with an account possessing `ROLE_ADMIN`.
2. Navigate to **External API Management** (`/api/admin/external-clients`).
3. Click **Generate New API Client**.
4. Enter your system's identifier (e.g. `Corporate-Payroll-Service`).
5. Select required permissions (scopes):
   - `ROSTER_READ`: Required to fetch schedules and staff assignments.
   - `EMPLOYEE_READ`: Required to synchronize staff codes and active statuses.
   - `LEAVE_READ`: Required for deduction or absence synchronization.
   - `SHIFT_READ`: Required to map operational shift hours to external timecards.
6. Copy and securely store the generated secret API key (`wrms_...`). It is cryptographically hashed with SHA-256 and will never be displayed again.

---

### Step 2: Making Authenticated Requests

Provide the secret key in the `X-API-Key` HTTP header.

#### Minimal Node.js / JavaScript Example:
```javascript
import fetch from 'node-fetch';

const WRMS_BASE_URL = 'https://your-wrms-deployment.up.railway.app';
const API_KEY = process.env.WRMS_API_KEY;

async function getCurrentRoster() {
  const response = await fetch(`${WRMS_BASE_URL}/api/external/v1/rosters/current`, {
    headers: {
      'X-API-Key': API_KEY,
      'Accept': 'application/json',
      'X-Request-ID': crypto.randomUUID()
    }
  });

  if (!response.ok) {
    const errorBody = await response.json();
    throw new Error(`WRMS Error [${errorBody.error?.code}]: ${errorBody.error?.message}`);
  }

  const json = await response.json();
  return json.data;
}
```

#### Minimal Python Example:
```python
import os
import uuid
import requests

WRMS_BASE_URL = os.getenv("WRMS_BASE_URL", "https://your-wrms-deployment.up.railway.app")
API_KEY = os.getenv("WRMS_API_KEY")

def get_employee_daily_duty(employee_code: str):
    url = f"{WRMS_BASE_URL}/api/external/v1/rosters/employee/{employee_code}"
    headers = {
        "X-API-Key": API_KEY,
        "Accept": "application/json",
        "X-Request-ID": str(uuid.uuid4())
    }
    resp = requests.get(url, headers=headers, timeout=10)
    resp.raise_for_status()
    result = resp.json()
    return result.get("data", [])
```

#### Minimal Java (Spring `RestClient` / `WebClient`) Example:
```java
@Service
public class WrmsIntegrationService {

    private final RestClient restClient;

    public WrmsIntegrationService(@Value("${wrms.base-url}") String baseUrl,
                                  @Value("${wrms.api-key}") String apiKey) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-API-Key", apiKey)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public ExternalRosterCycleResponse fetchCurrentCycle() {
        ExternalApiResponse<ExternalRosterCycleResponse> response = restClient.get()
                .uri("/api/external/v1/rosters/current")
                .header("X-Request-ID", UUID.randomUUID().toString())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        
        if (response != null && response.success()) {
            return response.data();
        }
        throw new IllegalStateException("Failed to load current cycle from WRMS");
    }
}
```

---

### Step 3: Handling Rate Limits & Throttling
WRMS enforces a token-bucket rate limiter on external endpoints:
- Default rate limit: 60 requests per minute per client key (configurable up to 300 req/min).
- When exceeded, WRMS returns HTTP `429 TOO_MANY_REQUESTS` with error code `RATE_LIMIT_EXCEEDED`.
- **Recommended Client Behavior**:
  - Implement an exponential backoff with jitter (e.g. wait 1s, 2s, 4s).
  - Cache static data (e.g. shift types and master employee list) locally for 15â€“60 minutes.

---

### Step 4: Distributed Tracing & Correlation
- External callers should always supply an `X-Request-ID` header (UUID v4) on every HTTP call.
- WRMS will bind this ID to the request lifecycle and echo it back in the `meta.requestId` block and in all error logs.
- When reporting anomalies or filing support inquiries with WRMS administrators, provide this `requestId` for instantaneous root-cause log lookup in Railway logs.

---

### Step 5: Common Integration Patterns

#### Pattern A: Time & Attendance / Biometric Clock-in Verification
1. External clock-in terminal reads employee badge `EMP001` at 06:55.
2. Terminal service calls `GET /api/external/v1/rosters/by-date?date=2026-09-15`.
3. Locate record for `EMP001`.
4. Verify `shiftType == "MORNING"` and `weeklyOff == false` and `onLeave == false`.
5. Grant or flag access based on scheduled duty.

#### Pattern B: Payroll Absence Deductions
1. At the end of the monthly payroll cycle, payroll engine queries:
   `GET /api/external/v1/leaves?startDate=2026-09-01&endDate=2026-09-30`.
2. Compute total approved leave days per `employeeCode`.
3. Reconcile against paid leave entitlement balances.

#### Pattern C: Mobile App Employee Schedule Sync
1. Mobile app backend polls:
   `GET /api/external/v1/rosters/employee/{employeeCode}?startDate=2026-09-15&endDate=2026-09-21`.
2. Syncs duty shifts directly to employee's mobile device calendar.

---

## 7. Document Revision & Compliance Notice

This technical documentation has been derived directly from verified inspection of the compiled WRMS Spring Boot application codebase (`v2.5.0`). All endpoints, path parameters, query parameters, request bodies, response shapes, and security definitions reflect the active production system running on Railway.