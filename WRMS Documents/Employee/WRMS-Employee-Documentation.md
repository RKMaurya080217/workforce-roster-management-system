# Workforce Roster Management System (WRMS) — Complete Employee Self-Service Guide

```text
Document Version: 2.0.0 (Enterprise Gold Master)
Last Updated: August 28, 2026
Project Version / Build: weekly-roster-management-system-1.0.0.jar
Documentation Status: Full Source-Code Mapped & Functionally Verified (Zero Skip Policy)
Target Audience: Team Members, Shift Personnel, Operational Employees
```

---

## Table of Contents
1. [Employee Portal Overview & Access](#1-employee-portal-overview--access)
2. [Security, Credentials & Data Privacy](#2-security-credentials--data-privacy)
3. [Employee Overview (Personal Dashboard)](#3-employee-overview-personal-dashboard)
4. [My Roster, Shift Badges & Explanations](#4-my-roster-shift-badges--explanations)
5. [Leave Management & Application Workflow](#5-leave-management--application-workflow)
6. [Shift Preferences & Avoid Rules](#6-shift-preferences--avoid-rules)
7. [My Profile & Profile Change Requests](#7-my-profile--profile-change-requests)
8. [Shift Handovers (Create & Acknowledge)](#8-shift-handovers-create--acknowledge)
9. [Notifications & Real-Time Alert Center](#9-notifications--real-time-alert-center)
10. [Personal Activity Log & Audit History](#10-personal-activity-log--audit-history)
11. [Roster Review Window & Voluntary Swaps](#11-roster-review-window--voluntary-swaps)
12. [Holiday Calendar & Non-Working Days](#12-holiday-calendar--non-working-days)
13. [Employee Access Restrictions Matrix](#13-employee-access-restrictions-matrix)

---

## 1. Employee Portal Overview & Access

The **WRMS Employee Portal** gives operational staff direct control over their schedule inspections, leave requests, shift preferences, handover logs, and profile management in a single responsive web interface.

### Default Login Accounts:
| Employee Code | Full Name | Username | Default Password | Role |
| :--- | :--- | :--- | :--- | :--- |
| `EMP001` | Rajat Maurya | `emp001` | `password123` | `ROLE_EMPLOYEE` |
| `EMP002` | Rakesh Verma | `emp002` | `password123` | `ROLE_EMPLOYEE` |
| `EMP003` | Sambhav Jain | `emp003` | `password123` | `ROLE_EMPLOYEE` |
| `EMP004` | Neha Sharma | `emp004` | `password123` | `ROLE_EMPLOYEE` |
| `EMP005` | Amit Patel | `emp005` | `password123` | `ROLE_EMPLOYEE` |
| `EMP006` | Priya Singh | `emp006` | `password123` | `ROLE_EMPLOYEE` |
| `EMP007` | Pooja Sharma | `emp007` | `password123` | `ROLE_EMPLOYEE` |

---

## 2. Security, Credentials & Data Privacy
- **Stateless JWT Security**: Sessions are secured with signed JSON Web Tokens.
- **Strict Data Isolation**: Employees can only view and manage their own assignments, leave records, preferences, and personal handovers.
- **Credential Mirroring**: Any approved profile updates (contact number, email) automatically mirror to `employees.csv`.

---

## 3. Employee Overview (Personal Dashboard)

The Employee Overview is your operational command center upon logging in:
1. **Today's Duty Status Card**:
   - Displays your assigned shift for today (Morning, General, Evening, Night, Weekly OFF, or Leave).
   - Shows active shift timings and current status (e.g. `🟢 ON DUTY`, `⚪ UPCOMING`, `🔵 COMPLETED`).
2. **Upcoming 7-Day Schedule**:
   - Visual breakdown of your upcoming shifts for the current week.
3. **Leave Balance Summary**:
   - Quick counts of casual leaves applied, approved, and remaining balance.
4. **Unread Notifications Widget**:
   - Instant count of unread schedule updates, leave approvals, and handover notes.

---

## 4. My Roster, Shift Badges & Explanations

- **Interactive Weekly View**: Shows your schedule across Monday through Sunday.
- **Shift Legend & Badges**:
  - `M` / Morning (06:00 – 14:30)
  - `G` / General (09:00 – 17:30)
  - `E` / Evening (14:00 – 22:30)
  - `N` / Night (22:00 – 06:30)
  - `OFF` / Weekly Rest Day
  - `LEAVE` / Approved Casual or Sick Leave
- **Shift Explanations**: Click on any duty cell to view why that shift was assigned to you, preference compliance, and rest intervals.

---

## 5. Leave Management & Application Workflow

### How to Apply for Leave:
1. Navigate to **Leave Management**.
2. Click **Apply Leave**.
3. Select **Start Date**, **End Date**, **Leave Type** (Casual / Sick / Privilege), and provide a **Reason**.
4. Click **Submit Application**.

### Lifecycle & Roster Collision Protection:
```text
Employee Submits Leave
        ↓
Status: PENDING
        ↓
Admin Approves Application
        ↓
Status: APPROVED
        ↓
Roster Engine automatically sets Shift to OFF (Leave) on requested dates
        ↓
Employee still receives 1 distinct Weekly OFF day (Total: 1 Leave + 1 OFF + 5 Work Days)
```

---

## 6. Shift Preferences & Avoid Rules

Customize your preferred schedule:
- **Preferred Shifts**: Choose your top choice (Morning, General, Evening).
- **Avoid Shifts**: Specify shifts you wish to avoid (e.g. Avoid Evening or Avoid Night).
- **Preferred Rest Days**: Request specific rest days (Saturday or Sunday).
- **Roster Impact**: The automated scheduling solver prioritizes your preferences while balancing team coverage.

---

## 7. My Profile & Profile Change Requests

- **View Profile**: Inspect your employee ID, code, name, designation, department, contact number, and email.
- **Request Update**: Update your contact number or email address with an explanation.
- **Approval & Sync**: When approved by an Admin, your profile updates across the database, UI, and `employees.csv` mirror file.

---

## 8. Shift Handovers (Create & Acknowledge)

Maintain smooth shift transitions between team members:
1. **Create Handover Note**:
   - Select Handover Date, Shift, and recipient colleague.
   - Enter Summary, Pending Tasks, Completed Tasks, and Priority (`LOW`, `MEDIUM`, `HIGH`, `CRITICAL`).
2. **Incoming Handovers**:
   - Colleagues taking over from you receive an instant real-time notification and can review pending tasks.
3. **Acknowledge Handover**:
   - Click **Acknowledge** and add optional transition remarks.

---

## 9. Notifications & Real-Time Alert Center

- **Live Server-Sent Events (SSE)**: Notifications appear instantly without refreshing your browser.
- **Notification Categories**:
  - 🟠 Tentative Roster Published (Review window open).
  - 🟢 Final Roster Published & Locked.
  - ✅ Leave Application Approved / Rejected.
  - 📝 Profile Change Request Approved.
  - 🔄 Shift Handover Assigned.
- **Actions**: Mark single items as read or click **Mark All as Read**.

---

## 10. Personal Activity Log & Audit History

- View your recent audit trail: logins, leave applications, profile updates, and schedule inspections.

---

## 11. Roster Review Window & Voluntary Swaps

- **Tentative Review Window**: Active between Sunday 9:00 AM and Sunday 4:00 PM IST.
- **Voluntary Swap Requests**: Request a voluntary swap with a colleague during the review window.

---

## 12. Holiday Calendar & Non-Working Days

- View public holidays and company gazetted non-working days for the current calendar year.

---

## 13. Employee Access Restrictions Matrix

| Capability | Allowed for Employee? | Reason / Restriction |
| :--- | :--- | :--- |
| View Own Schedule & Today's Duty | ✅ Yes | Self-service core feature |
| Apply for Casual / Sick Leave | ✅ Yes | Self-service core feature |
| Submit Shift Preferences & Avoid Rules | ✅ Yes | Self-service core feature |
| Create & Acknowledge Shift Handovers | ✅ Yes | Operational shift handover feature |
| Request Phone / Email Profile Changes | ✅ Yes | Subject to Administrator approval |
| Approve / Reject Other Staff Leaves | ❌ No (403 Forbidden) | Administrator permission required |
| Generate / Delete / Publish Roster Cycles | ❌ No (403 Forbidden) | Administrator permission required |
| Edit Other Employees' Assignments | ❌ No (403 Forbidden) | Data privacy & administrator authorization |
| Trigger SMTP Email Diagnostic Tests | ❌ No (403 Forbidden) | Administrator permission required |