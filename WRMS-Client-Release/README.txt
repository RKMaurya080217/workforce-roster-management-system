===============================================================================
       WEEKLY ROSTER MANAGEMENT SYSTEM (WRMS) - CLIENT RUNNABLE RELEASE v1.0.2
===============================================================================

RELEASE VERSION: 1.0.2 (BATCH 102 - MANUAL BUILDER & STRICT ASSIGNMENT RELEASE)
RELEASE DATE: 23-OCT-2026
DATABASE MODE: SQLite (Embedded, Local Zero-Configuration, File: data/wrms.db)
JAVA REQUIREMENTS: Java 17 or higher (Adoptium Temurin / OpenJDK / Oracle JDK)

-------------------------------------------------------------------------------
1. QUICK START (ONE-CLICK LAUNCH)
-------------------------------------------------------------------------------
1. Double-click "run.bat" in this directory.
2. The launcher will automatically:
   - Check and verify your Java version (JDK 17+).
   - Ensure local data and logging directories exist.
   - Start the WRMS Spring Boot application using the embedded SQLite database.
   - Launch your default browser to: http://localhost:8080
3. Default Login Credentials:
   - Admin Username: admin
   - Admin Password: Admin@123
   - Employee Login: Use employee code (e.g. emp001, emp002) with default password: password123

-------------------------------------------------------------------------------
2. WHAT IS NEW IN BATCH 102 (v1.0.2)
-------------------------------------------------------------------------------
1. FULL MANUAL ROSTER BUILDER:
   - New dedicated navigation: Admin -> Roster -> Manual Roster Builder (#/manual-roster).
   - 7-day Monday -> Sunday matrix view for all employees.
   - Single-cell interactive shift editing (Morning, General, Evening, Night, Weekly Off).
   - Authoritative Level 1 Assignment: Manual shifts are locked (🔒) and CANNOT be
     overridden by automatic generation, seeds, or balancing algorithms.
   - Bulk Shift Assignment tool: Select employees and days, apply shift in one click.
   - Copy Previous Week tool: Copy previous week's shift pattern into the target week.
   - Fill Unassigned Shifts: Fills empty/unassigned cells without altering manual locks.
   - Validate & Publish workflow with hard conflict detection.

2. RECURRING WEEKLY PREFERENCES (Level 3 Priority):
   - Added recurring weekly shift rules (e.g. "Every Saturday -> Morning Shift").
   - Integrated in Employee Shift Preference Inspector with dedicated Card 3.
   - Conflict prevention: Overlapping active rules for same employee & day are blocked.
   - Full toggle and delete controls.

3. STRICT SHIFT ASSIGNMENT ENFORCEMENT & PRIORITY MODEL:
   - Level 0: Hard Safety / Statutory / Approved Leave Constraints (Flagged as conflict;
     never silently modified).
   - Level 1: Explicit Manual Roster Assignment (Highest scheduling authority).
   - Level 2: One-Time Date Shift Preference.
   - Level 3: Recurring Weekly Shift Preference.
   - Level 4: Multi-Shift Preference Ordering (General > Morning > Evening).
   - Level 5: Automatic Optimization & Balancing Algorithm.

4. ISOLATED NAVIGATION & REGRESSION FIXES (BATCH 101):
   - Roster, Shift Preference, and Edit Employee profile actions are 100% isolated.
   - Employee ID mapping and modal routing verified.
   - SQLite WAL mode concurrency and schema auto-migration verified.

-------------------------------------------------------------------------------
3. TROUBLESHOOTING
-------------------------------------------------------------------------------
- Port in use: If port 8080 is occupied, set a custom port before starting:
    set SERVER_PORT=8081 & run.bat
- Database location: SQLite stores data locally in "data/wrms.db".
- Reset credentials: In emergency, run "run.bat" with ADMIN_PASSWORD=NewPass@123.
===============================================================================
