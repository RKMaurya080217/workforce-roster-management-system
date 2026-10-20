================================================================================
          WEEKLY ROSTER MANAGEMENT SYSTEM (WRMS) - CLIENT RELEASE v1.0.1 (Batch 101)
================================================================================

1. OVERVIEW
-----------
Weekly Roster Management System (WRMS) is an enterprise-grade automated workforce
scheduling and roster generation platform built with Spring Boot, Java 17, and
embedded SQLite database persistence for local zero-configuration deployment.

Features:
- Smart Multi-Candidate Roster Generation (Batch 94)
- Automated constraint validation: 12-hour rest, female night protection,
  paired synchronization, development team Mon-Fri General shift scheduling.
- Controlled Finalize & Publish workflow with zero premature notifications.
- Complete Dual-Database Support (Local SQLite / Production Cloud MySQL).


2. PREREQUISITES
----------------
- Operating System: Windows 10/11, Windows Server, macOS, or Linux.
- Java Runtime: Java Development Kit (JDK) or Java Runtime Environment (JRE) 17+.
  Verify by running: java -version


3. HOW TO RUN
-------------
Option A: Double-Click Startup (Recommended on Windows)
  Double-click `run.bat`.
  The script will verify your Java environment, start the server using the local
  SQLite profile, and automatically open your default browser to:
  http://localhost:8080

Option B: Manual Command Line Execution
  Open PowerShell or Command Prompt in this folder and run:
  java -Dspring.profiles.active=local -jar WRMS-Client-1.0.1.jar

  Then open your web browser to:
  http://localhost:8080


4. DEFAULT PRE-CONFIGURED LOGIN CREDENTIALS
--------------------------------------------
A. ADMINISTRATOR ACCOUNT (Full Roster, Candidate, and Approval Access)
   - Username: admin
   - Password: password123
   (Alternative Admin Email: admin@company.com / password123)

B. EMPLOYEE PORTAL ACCOUNTS (Schedule View, Swap Requests, Shift Preferences)
   - EMP001 (Divyansh) : emp001 / password123
   - EMP002 (Prachi)   : emp002 / password123
   - EMP003 (Rajat)    : emp003 / password123
   - EMP004 (Sambhav)  : emp004 / password123
   - EMP005 (Sapna)    : emp005 / password123
   - EMP006 (Shriram)  : emp006 / password123
   - EMP007 (Tushar)   : emp007 / password123


5. DATA STORAGE & PERSISTENCE
------------------------------
- Database File: `data/wrms.db` (SQLite)
  All employees, shift definitions, roster cycles, assignments, and audit trails
  are automatically persisted inside the `data/` folder.
- Server Logs: `logs/`
  Operational logs and health check outputs are maintained in the `logs/` folder.


6. STOPPING THE APPLICATION
----------------------------
In the Command Prompt window running the application, press Ctrl+C or simply
close the window. All data is saved safely in the SQLite database.
================================================================================
