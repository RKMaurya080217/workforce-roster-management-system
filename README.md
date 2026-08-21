# Weekly Roster Management System

Spring Boot 3 backend for intelligent weekly employee roster generation.

## Stack

- Java 17
- Spring Boot 3
- Maven
- MySQL
- Spring Data JPA
- Spring Security with JWT
- Spring Validation
- Swagger/OpenAPI

## Run

1. Create or allow the app to create MySQL database `weekly_roster_db`.
2. Update `src/main/resources/application.properties` with your MySQL username/password.
3. Start the app:

```bash
mvn spring-boot:run
```

Swagger UI: `http://localhost:8080/swagger-ui.html`

Frontend UI: `http://localhost:8080/`

Default admin:

- Username: `admin`
- Password: `admin@123`

Default employee accounts:

- Username: `emp001` to `emp007`
- Password: `password123`

## Frontend

The frontend is included inside Spring Boot under `src/main/resources/static`.

No Node.js, npm, React, or separate frontend server is required. Start the Spring Boot backend and open:

```text
http://localhost:8080/
```

Admin can generate rosters, manage employees, approve or reject leaves, view dashboards, and override shifts.

Employees can log in, view their own roster, apply leave, and view leave history.

## Roster Rules

The roster generator uses active employees from the database and never depends on a fixed employee count. It gives each employee one weekly off per 7-day cycle, keeps every configured shift covered where staff availability allows, enforces female shift restrictions, continues shift rotation from prior assignments, protects night-to-morning rest, limits consecutive night assignments, and accounts for approved leave separately from weekly off.

If the current workforce cannot legally cover every required shift, the API returns a validation error instead of creating an invalid roster.

## Core APIs

- `POST /api/auth/login`
- `GET /api/auth/me`
- `GET /api/employees`
- `GET /api/employees/active`
- `POST /api/employees`
- `PUT /api/employees/{id}`
- `DELETE /api/employees/{id}`
- `GET /api/shifts`
- `POST /api/rosters/generate`
- `GET /api/rosters`
- `GET /api/rosters/cycle/{id}`
- `PUT /api/rosters/{id}/shift`
- `PUT /api/rosters/{id}/off`
- `POST /api/rosters/overrides`
- `POST /api/leaves`
- `GET /api/leaves/my/{employeeId}`
- `GET /api/leaves/pending`
- `PUT /api/leaves/{id}/approve`
- `PUT /api/leaves/{id}/reject`
- `GET /api/dashboard`

## Files

- Database schema: `src/main/resources/schema.sql`
- Sample data: `src/main/resources/data.sql`
- Postman collection: `postman/weekly-roster-management-system.postman_collection.json`
