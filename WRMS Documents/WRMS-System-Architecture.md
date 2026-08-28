# Workforce Roster Management System (WRMS) — System Architecture

```text
Document Version: 1.0.0
Classification: System Engineering Architecture
Target Audience: Enterprise Architects, Lead Developers, DevOps Engineers, Security Officers
```

---

## 1. High-Level Architectural Flow

```
+-----------------------------------------------------------------------------------+
|                            CLIENT PRESENTATION LAYER                              |
|   Vanilla ES6+ SPA  |  Glassmorphism UI  |  Native SSE Listener  |  SVG Visuals   |
+-----------------------------------------------------------------------------------+
                                          | REST API / JSON + Server-Sent Events
                                          v
+-----------------------------------------------------------------------------------+
|                           SECURITY & AUTHORIZATION LAYER                          |
|   JwtAuthenticationFilter  |  DaoAuthenticationProvider  |  Spring Security RBAC  |
+-----------------------------------------------------------------------------------+
                                          |
                                          v
+-----------------------------------------------------------------------------------+
|                            CORE SERVICE & BUSINESS LOGIC                          |
|  +--------------------+  +----------------------+  +---------------------------+  |
|  | RosterService      |  | SmartCommandCenter   |  | RosterSchedulerService    |  |
|  | Constraint Solver  |  | Health & Conflicts   |  | Upcoming-Week-Only Engine |  |
|  +--------------------+  +----------------------+  +---------------------------+  |
|  +--------------------+  +----------------------+  +---------------------------+  |
|  | SseEmitterService  |  | RosterEmailService   |  | ExportCenterService       |  |
|  | Live Real-Time Bus |  | Gmail SMTP Engine    |  | PDF / XLSX / CSV / PNG    |  |
|  +--------------------+  +----------------------+  +---------------------------+  |
+-----------------------------------------------------------------------------------+
                                          | Spring Data JPA / Hibernate ORM
                                          v
+-----------------------------------------------------------------------------------+
|                              PERSISTENCE STORAGE LAYER                            |
|     MySQL 8.0 Database  |  Indexed Relational Schema  |  CSV Credential Mirror    |
+-----------------------------------------------------------------------------------+
```

---

## 2. Roster Scheduling & Execution Pipeline

```
+-----------------------------------------------------------------------------------+
| 1. SCHEDULE TRIGGER (Sunday 09:00 AM IST / Manual 1-Click via Command Center)     |
+-----------------------------------------------------------------------------------+
                                          |
                                          v
+-----------------------------------------------------------------------------------+
| 2. DATA INGESTION & PREREQUISITE GATHERING                                        |
|    - Active Employees (Gender, Skills, Active Status)                             |
|    - Approved Leave Applications (Start Date, End Date)                           |
|    - Shift Preferences (Preferred Shifts, Avoid Shifts, Preferred OFF Days)       |
|    - Public & Gazetted Holidays                                                   |
+-----------------------------------------------------------------------------------+
                                          |
                                          v
+-----------------------------------------------------------------------------------+
| 3. CONSTRAINT SOLVER & HEURISTIC ENGINE                                           |
|    [HARD INVARIANTS]                                                              |
|    - Morning >= 1, General >= 1, Evening >= 1, Night = 1                          |
|    - Female Employees: 0 Evening, 0 Night                                         |
|    - Max 2 Night shifts per male employee per week                                |
|    - Min 12 Hours Rest between consecutive duties                                 |
|    - Approved Leave -> Shift converted to OFF (Leave) + 1 distinct Weekly OFF     |
|    [SOFT PREFERENCES]                                                             |
|    - Maximize Preferred Shift alignment                                           |
|    - Avoid requested shifts                                                       |
|    - Fair Weekend OFF distribution                                                |
+-----------------------------------------------------------------------------------+
                                          |
                                          v
+-----------------------------------------------------------------------------------+
| 4. PERSISTENCE & VERSION SNAPSHOT                                                 |
|    - Save RosterCycle (Status: TENTATIVE)                                         |
|    - Save RosterAssignment entities (7 days x N employees)                        |
|    - Generate JSON Version Snapshot in `roster_versions`                          |
+-----------------------------------------------------------------------------------+
                                          |
                                          v
+-----------------------------------------------------------------------------------+
| 5. REAL-TIME NOTIFICATION & EMAIL DISTRIBUTION                                    |
|    - Dispatch SSE `NOTIFICATION_RECEIVED` to active employee browsers             |
|    - Dispatch HTML Email with Excel (.xlsx) & PNG visual schedule attachments     |
|    - Record Delivery Status in `email_delivery_logs`                              |
+-----------------------------------------------------------------------------------+
```

---

## 3. Employee Self-Service & Admin Approval Pipeline

```
+-------------------+        1. Submit Request (Leave / Profile / Preference)
|                   | ----------------------------------------------------------> +-------------------+
|  Employee Portal  |                                                             |  Database (MySQL) |
|   (Browser SPA)   | <---------------------------------------------------------- | `leave_requests`  |
+-------------------+        2. SSE Real-Time Alert to Admin Topbar / Dashboard   +-------------------+
                                                                                            |
                                                                                            | 3. Admin Reviews
                                                                                            v
+-------------------+        4. Approve / Reject Action                           +-------------------+
|   Admin Portal    | ----------------------------------------------------------> | Unified Approval  |
|   (Browser SPA)   |                                                             | Service & DB Sync |
+-------------------+                                                             +-------------------+
                                                                                            |
                                                                                            v
                                                             5. Sync `employees.csv` (for Profile updates)
                                                             6. Convert Schedule to `OFF (Leave)` (for Leaves)
                                                             7. Dispatch Real-Time SSE Notification to Employee
```