# Weekly Roster Management System (WRMS)

Enterprise-grade intelligent weekly employee roster planning, automated shift distribution, leave tracking, profile self-service with approval workflow, version control with instant rollback, and multi-format reporting.

---

## 1. Dual Database Architecture

WRMS supports **two database engines** seamlessly through Spring Profiles:

| Environment | Profile | Database Engine | Connection / Path | Notes |
|---|---|---|---|---|
| **Local Development & Office Submission** | `local` (Default) | **SQLite** | `data/wrms.db` | Zero-configuration. Auto-creates `data/` and database file on first run. Persists across restarts. No MySQL installation required. |
| **Production & Cloud Deployment** | `prod` / `production` | **MySQL 8.0+** | Railway / Managed MySQL | Auto-configured via Railway environment variables or JDBC parameters. |

The application automatically selects the appropriate database dialect and connection pool based on the active Spring profile or environment.

---

## 2. Quick Start / Local Run (SQLite Mode)

### Prerequisites
- **Java 17** or higher (`java -version`)
- Port **8080** available (or override via `PORT` environment variable)

### Option A: One-Click Startup (Windows)
Double-click `run.bat` or run:
```cmd
run.bat
```

### Option B: Standalone Executable JAR
```bash
java -Duser.timezone=Asia/Kolkata -jar target/weekly-roster-management-system-1.0.0.jar
```
*(Or rename/copy to `WRMS.jar` and run `java -jar WRMS.jar`)*

### Option C: Run via Maven
```bash
mvn spring-boot:run
```

Once started:
- **Web UI**: [http://localhost:8080/](http://localhost:8080/)
- **Swagger / OpenAPI Documentation**: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
- **Local Database Location**: `data/wrms.db`

---

## 3. Default Credentials

### Administrator
- **Username**: `admin`
- **Password**: `Admin@123`
- **Role**: `ROLE_ADMIN` (Full workforce configuration, roster generation, unified approvals, overrides, export center, audit logs)

### Employees
- **Usernames**: `emp001`, `emp002`, `emp003`, ..., `emp020`
- **Password**: `password123`
- **Role**: `ROLE_EMPLOYEE` (Personal duty schedule, self-service profile change requests, leave application, shift handover, notifications)

---

## 4. Production Deployment (MySQL Mode)

When deploying to Railway, Docker, or Cloud environments:

1. **Activate Production Profile**:
   Set `SPRING_PROFILES_ACTIVE=prod` or pass standard Railway MySQL variables (`MYSQLHOST`, `MYSQLPORT`, `MYSQLUSER`, `MYSQLPASSWORD`, `MYSQLDATABASE`).
2. **Environment Variables**:
   - `PORT`: HTTP port (defaults to 8080)
   - `MYSQL_URL` / `DATABASE_URL`: Full MySQL JDBC connection URL
   - `JWT_SECRET`: 64+ character random string for token signing
   - `BREVO_API_KEY`: Brevo HTTPS Transactional Email API key (optional, for auto-mailer)
   - `BREVO_SENDER_EMAIL`: Verified sender email in Brevo
3. **Docker Deployment**:
   ```bash
   docker build -t wrms:latest .
   docker run -p 8080:8080 -e SPRING_PROFILES_ACTIVE=prod wrms:latest
   ```

---

## 5. Core Feature Modules

1. **Intelligent Roster Generator**:
   - Deterministic multi-shift allocation (Morning `M1`, `M2`, General `G`, Evening `E`, Night `N`).
   - Hard constraints: 1 mandatory weekly off, night-to-morning rest protection, female employee safety hours, consecutive night limits, approved leave exclusion.
2. **Employee Profile Self-Service & Approval Workflow**:
   - Employees request changes to allowed personal details (Mobile Number, Email, First Name, Last Name, Gender).
   - Protected organizational fields (`employeeCode`, `role`, `status`) remain restricted to HR/Admin.
   - Admin approves &rarr; master record transactionally updates. Admin rejects &rarr; master record unchanged.
3. **Unified Approvals Center**:
   - Centralized review for Profile Change Requests, Leave Applications, and Shift Preferences with instant counts.
4. **Date-Specific & Multi-Shift Preferences**:
   - Custom priority routing for individual dates and overall employee shift preferences.
5. **Roster Version Control & Comparison**:
   - Immutable version snapshots for every generation and override.
   - Side-by-side comparison and 1-click safe rollback.
6. **Multi-Format Export Center**:
   - Generates Excel (`.xlsx`), PDF (`.pdf`), CSV (`.csv`), and PNG Image (`.png`) reports with zero third-party office dependencies.
7. **Shift Handovers & Audit Logging**:
   - Shift-to-shift operational handovers, activity tracking, and comprehensive immutable system audit logs.
8. **Transactional Email & Push Notifications**:
   - Brevo HTTPS REST API transactional email integration.
   - Web Push (FCM) & Server-Sent Events (SSE) live notifications.

---

## 6. Project Testing & Verification

Execute targeted or complete test suites:

```bash
# Run Profile Workflow & Roster Regression Tests
mvn test -Dtest=Batch84ProfileChangeWorkflowTest,Batch83ProfileChangeEndToEndTest

# Run Full Component-by-Component Matrix Audit
node scratch/batch86-matrix-test.js
```
