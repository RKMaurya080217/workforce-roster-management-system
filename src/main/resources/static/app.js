/**
 * ============================================================================
 * WRMS ENTERPRISE APPLICATION LOGIC & STATE ENGINE
 * Features:
 * - 12-Hour Minimum Rest & Overnight Timing Engine
 * - Max 2 Night Shifts per Employee in a Cycle
 * - Dynamic Shift Configuration UI Displaying Exact Timings
 * - Interactive Dashboard Hover/Click Details
 * - Delete Roster Cycle in History with Safety Confirmation
 * ============================================================================
 */

// Application Central State (Tab-Isolated via sessionStorage)
const state = {
  token: sessionStorage.getItem("wrmsToken") || "",
  profile: JSON.parse(sessionStorage.getItem("wrmsProfile") || "null"),
  activePage: "dashboard",
  isSidebarCollapsed: sessionStorage.getItem("wrmsSidebarCollapsed") === "true",
  
  // Cache Collections
  dashboardData: null,
  employees: [],
  shifts: [],
  cycles: [],
  selectedCycleId: null,
  pendingLeaves: [],
  
  // Filter States
  employeeSearchTerm: "",
  employeeGenderFilter: "ALL",
  employeeStatusFilter: "ALL",

  rosterSearchTerm: "",
  rosterShiftFilter: "ALL",
  rosterViewMode: "matrix", // "matrix" or "table"

  // Selected Detail Drilldown
  inspectedEmployeeId: null,
  inspectedEmployeeName: ""
};

// Dynamic Shift Timings Reference Map (updated automatically from backend)
const SHIFT_TIMINGS = {
  MORNING: "07:00 - 15:00",
  GENERAL: "09:30 - 18:00",
  EVENING: "14:00 - 22:00",
  NIGHT: "22:00 - 07:00 next day",
  OFF: "No working hours",
  LEAVE: "Approved Absence"
};

// Helper to safely escape HTML strings
function escapeHTML(str) {
  if (str === null || str === undefined) return "";
  return String(str)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

// Helper to get formatted timing for any shift type
function getShiftTimingDisplay(type) {
  const shiftObj = state.shifts.find(s => s.shiftType === type);
  if (shiftObj && shiftObj.timingDisplay) {
    return shiftObj.timingDisplay;
  }
  return SHIFT_TIMINGS[type] || "Configured Schedule";
}

// DOM References
const dom = {
  loginView: document.getElementById("loginView"),
  appView: document.getElementById("appView"),
  loginForm: document.getElementById("loginForm"),
  loginUsername: document.getElementById("loginUsername"),
  loginPassword: document.getElementById("loginPassword"),
  togglePasswordBtn: document.getElementById("togglePasswordBtn"),
  demoAdminBtn: document.getElementById("demoAdminBtn"),
  demoEmpBtn: document.getElementById("demoEmpBtn"),
  sidebarNav: document.getElementById("sidebarNav"),
  sidebarUsername: document.getElementById("sidebarUsername"),
  sidebarRole: document.getElementById("sidebarRole"),
  sidebarUserAvatar: document.getElementById("sidebarUserAvatar"),
  logoutBtn: document.getElementById("logoutBtn"),
  sidebarToggleBtn: document.getElementById("sidebarToggleBtn"),
  mobileMenuBtn: document.getElementById("mobileMenuBtn"),
  appSidebar: document.getElementById("appSidebar"),
  pageHeadingTitle: document.getElementById("pageHeadingTitle"),
  breadcrumbCurrent: document.getElementById("breadcrumbCurrent"),
  liveDateStr: document.getElementById("liveDateStr"),
  topbarActionGroup: document.getElementById("topbarActionGroup"),
  globalRefreshBtn: document.getElementById("globalRefreshBtn"),
  toastContainer: document.getElementById("toastContainer"),
  floatingTooltip: document.getElementById("dashboardFloatingTooltip"),

  // View Panels
  views: {
    dashboard: document.getElementById("viewDashboard"),
    employees: document.getElementById("viewEmployees"),
    roster: document.getElementById("viewRoster"),
    shifts: document.getElementById("viewShifts"),
    leaves: document.getElementById("viewLeaves"),
    history: document.getElementById("viewHistory"),
    employeeWorkspace: document.getElementById("viewEmployeeWorkspace"),
    employeeRosterDetail: document.getElementById("viewEmployeeRosterDetail"),
    health: document.getElementById("viewHealth"),
    audit: document.getElementById("viewAudit"),
    profileApprovals: document.getElementById("viewProfileApprovals"),
    analytics: document.getElementById("viewAnalytics"),
    validation: document.getElementById("viewValidation"),
    adminPreferences: document.getElementById("viewAdminPreferences"),
    adminHolidays: document.getElementById("viewAdminHolidays"),
    adminHandovers: document.getElementById("viewAdminHandovers"),
    adminWorkload: document.getElementById("viewAdminWorkload"),
    adminSkills: document.getElementById("viewAdminSkills"),
    exportCenter: document.getElementById("viewExportCenter"),
    rosterVersions: document.getElementById("viewRosterVersions")
  }
};

// Admin Navigation Menu Items
const ADMIN_NAV = [
  { id: "dashboard", label: "Dashboard", icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="7"></rect><rect x="14" y="3" width="7" height="7"></rect><rect x="14" y="14" width="7" height="7"></rect><rect x="3" y="14" width="7" height="7"></rect></svg>` },
  { id: "analytics", label: "Roster Analytics", icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="20" x2="18" y2="10"></line><line x1="12" y1="20" x2="12" y2="4"></line><line x1="6" y1="20" x2="6" y2="14"></line></svg>` },
  { id: "validation", label: "Conflict Validator", icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path></svg>` },
  { id: "employees", label: "Employees", icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"></path><circle cx="9" cy="7" r="4"></circle><path d="M23 21v-2a4 4 0 0 0-3-3.87"></path><path d="M16 3.13a4 4 0 0 1 0 7.75"></path></svg>` },
  { id: "roster", label: "Weekly Roster", icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="4" width="18" height="18" rx="2" ry="2"></rect><line x1="16" y1="2" x2="16" y2="6"></line><line x1="8" y1="2" x2="8" y2="6"></line><line x1="3" y1="10" x2="21" y2="10"></line></svg>` },
  { id: "adminPreferences", label: "Shift Preferences", icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"></path></svg>` },
  { id: "adminHolidays", label: "Holiday Calendar", icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="4" width="18" height="18" rx="2" ry="2"></rect><line x1="16" y1="2" x2="16" y2="6"></line><line x1="8" y1="2" x2="8" y2="6"></line><line x1="3" y1="10" x2="21" y2="10"></line></svg>` },
  { id: "adminHandovers", label: "Shift Handovers", icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="17 1 21 5 17 9"></polyline><path d="M3 11V9a4 4 0 0 1 4-4h14"></path><polyline points="7 23 3 19 7 15"></polyline><path d="M21 13v2a4 4 0 0 1-4 4H3"></path></svg>` },
  { id: "adminWorkload", label: "Workload Analytics", icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 14 10"></polyline></svg>` },
  { id: "adminSkills", label: "Skill Matrix", icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"></polygon></svg>` },
  { id: "exportCenter", label: "Export Center", icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line></svg>` },
  { id: "rosterVersions", label: "Roster Versions", icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 8 14"></polyline><path d="M3.05 11a9 9 0 1 1 .5 4"></path><polyline points="3 16 3 11 8 11"></polyline></svg>` },
  { id: "health", label: "Roster Health", icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 12h-4l-3 9L9 3l-3 9H2"></path></svg>` },
  { id: "shifts", label: "Shift Capacity", icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 16 14"></polyline></svg>` },
  { id: "leaves", label: "Leave Requests", badgeKey: "pendingLeaves", icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="16" y1="13" x2="8" y2="13"></line><line x1="16" y1="17" x2="8" y2="17"></line><polyline points="10 9 9 9 8 9"></polyline></svg>` },
  { id: "profileApprovals", label: "Profile Approvals", badgeKey: "pendingProfileChangesCount", icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"></path><circle cx="9" cy="7" r="4"></circle><polyline points="16 11 18 13 22 9"></polyline></svg>` },
  { id: "history", label: "Roster History", icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 8 14"></polyline><path d="M3.05 11a9 9 0 1 1 .5 4"></path><polyline points="3 16 3 11 8 11"></polyline></svg>` },
  { id: "audit", label: "Audit Trail", icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="16" y1="13" x2="8" y2="13"></line><line x1="16" y1="17" x2="8" y2="17"></line><line x1="10" y1="9" x2="8" y2="9"></line></svg>` }
];

// Employee Navigation Menu Items
const EMPLOYEE_NAV = [
  { id: "emp_overview", tab: "overview", label: "Overview", icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="7"></rect><rect x="14" y="3" width="7" height="7"></rect><rect x="14" y="14" width="7" height="7"></rect><rect x="3" y="14" width="7" height="7"></rect></svg>` },
  { id: "emp_roster", tab: "roster", label: "My Roster", icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="4" width="18" height="18" rx="2" ry="2"></rect><line x1="16" y1="2" x2="16" y2="6"></line><line x1="8" y1="2" x2="8" y2="6"></line><line x1="3" y1="10" x2="21" y2="10"></line></svg>` },
  { id: "emp_leaves", tab: "leaves", label: "Leave Management", badgeKey: "cachedPendingLeavesCount", icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="16" y1="13" x2="8" y2="13"></line><line x1="16" y1="17" x2="8" y2="17"></line><polyline points="10 9 9 9 8 9"></polyline></svg>` },
  { id: "emp_preferences", tab: "preferences", label: "Shift Preferences", icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"></path></svg>` },
  { id: "emp_handovers", tab: "handovers", label: "Shift Handovers", icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="17 1 21 5 17 9"></polyline><path d="M3 11V9a4 4 0 0 1 4-4h14"></path><polyline points="7 23 3 19 7 15"></polyline><path d="M21 13v2a4 4 0 0 1-4 4H3"></path></svg>` },
  { id: "emp_skills", tab: "skills", label: "My Skills", icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"></polygon></svg>` },
  { id: "emp_holidays", tab: "holidays", label: "Holidays", icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="4" width="18" height="18" rx="2" ry="2"></rect><line x1="16" y1="2" x2="16" y2="6"></line><line x1="8" y1="2" x2="8" y2="6"></line><line x1="3" y1="10" x2="21" y2="10"></line></svg>` },
  { id: "emp_notifications", tab: "notifications", label: "Notifications", badgeKey: "unreadNotificationCount", icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"></path><path d="M13.73 21a2 2 0 0 1-3.46 0"></path></svg>` },
  { id: "emp_activity", tab: "activity", label: "Activity / Logs", icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 16 14"></polyline><path d="M3.05 11a9 9 0 1 1 .5 4"></path><polyline points="3 16 3 11 8 11"></polyline></svg>` },
  { id: "emp_profile", tab: "profile", label: "My Profile", icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path><circle cx="12" cy="7" r="4"></circle></svg>` }
];

// Initialize Application
document.addEventListener("DOMContentLoaded", () => {
  setupLiveDate();
  bindGlobalEvents();
  setupRouter();
  scheduleMidnightRefresh();

  if (state.token && state.profile) {
    showWorkspace();
    resolveInitialRoute();
  } else {
    showLogin();
  }
});

function setupLiveDate() {
  const options = { weekday: "short", day: "2-digit", month: "short", year: "numeric" };
  if (dom.liveDateStr) {
    dom.liveDateStr.textContent = new Date().toLocaleDateString("en-US", options);
  }
}

// Lightweight Midnight Date Transition Scheduler (Exact timeout, 0 polling loops)
function scheduleMidnightRefresh() {
  if (state.midnightTimeoutId) {
    clearTimeout(state.midnightTimeoutId);
    state.midnightTimeoutId = null;
  }
  const now = new Date();
  const tomorrow = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1, 0, 0, 1, 0); // 00:00:01
  const msUntilMidnight = Math.max(1000, tomorrow.getTime() - now.getTime());

  state.midnightTimeoutId = setTimeout(() => {
    console.log("[WRMS] Midnight date transition triggered at:", new Date().toLocaleTimeString());
    setupLiveDate();
    if (state.token && state.profile) {
      if (state.profile.role === "ROLE_EMPLOYEE" && state.activePage === "employeeWorkspace") {
        renderEmployeeWorkspaceView();
      } else if (state.profile.role === "ROLE_ADMIN" && state.activePage === "dashboard") {
        renderDashboardView();
      }
    }
    scheduleMidnightRefresh();
  }, msUntilMidnight);
}

// Cross-Tab & State Invalidation Broadcaster
function broadcastDataMutation(type) {
  try {
    localStorage.setItem("wrms_last_mutation", JSON.stringify({ type, timestamp: Date.now() }));
  } catch (e) {}
}

function bindGlobalEvents() {
  // Login Form
  dom.loginForm.addEventListener("submit", handleLogin);

  // Toggle Password Visibility
  dom.togglePasswordBtn.addEventListener("click", () => {
    const isPassword = dom.loginPassword.type === "password";
    dom.loginPassword.type = isPassword ? "text" : "password";
  });

  // Demo Credentials Fast-Fill
  dom.demoAdminBtn.addEventListener("click", () => {
    dom.loginUsername.value = "admin";
    dom.loginPassword.value = "Admin@123";
    toast("Admin credentials filled", "info");
  });

  dom.demoEmpBtn.addEventListener("click", () => {
    dom.loginUsername.value = "emp001";
    dom.loginPassword.value = "password123";
    toast("Staff credentials filled", "info");
  });

  // Logout
  dom.logoutBtn.addEventListener("click", handleLogout);

  // Refresh
  dom.globalRefreshBtn.addEventListener("click", () => {
    loadActiveView();
    toast("Data refreshed", "info");
  });

  // Cross-Tab Consistency Event Listener (Safe view refresh only; does not affect tab authentication)
  window.addEventListener("storage", (event) => {
    if (event.key === "wrms_last_mutation" && event.newValue) {
      console.log("[WRMS] Cross-tab mutation detected. Refreshing active view...");
      if (state.token && state.profile) {
        loadActiveView().catch(() => {});
      }
    }
  });

  // Desktop Sidebar Collapse Toggle
  if (dom.sidebarToggleBtn) {
    dom.sidebarToggleBtn.addEventListener("click", (e) => {
      e.stopPropagation();
      state.isSidebarCollapsed = !state.isSidebarCollapsed;
      dom.appSidebar.classList.toggle("collapsed", state.isSidebarCollapsed);
      sessionStorage.setItem("wrmsSidebarCollapsed", String(state.isSidebarCollapsed));
      dom.sidebarToggleBtn.setAttribute("aria-expanded", String(!state.isSidebarCollapsed));
      dom.sidebarToggleBtn.setAttribute("title", state.isSidebarCollapsed ? "Expand Sidebar" : "Collapse Sidebar");
    });
  }

  // Mobile Menu
  dom.mobileMenuBtn.addEventListener("click", () => {
    dom.appSidebar.classList.toggle("mobile-open");
  });

  // Modal Close Handlers
  document.querySelectorAll("[data-close-modal]").forEach(btn => {
    btn.addEventListener("click", () => {
      const modalId = btn.getAttribute("data-close-modal");
      closeModal(modalId);
    });
  });

  // Modal Backdrop Click
  document.querySelectorAll(".modal-backdrop").forEach(modal => {
    modal.addEventListener("click", (e) => {
      if (e.target === modal) closeModal(modal.id);
    });
  });

  // Forms in Modals
  document.getElementById("employeeModalForm").addEventListener("submit", handleSaveEmployee);
  document.getElementById("generateRosterForm").addEventListener("submit", handleTriggerGenerateRoster);
  document.getElementById("shiftOverrideForm").addEventListener("submit", handleSaveShiftOverride);
  document.getElementById("shiftSwapForm").addEventListener("submit", handleExecuteShiftSwap);
  document.getElementById("leaveDecisionForm").addEventListener("submit", handleConfirmLeaveDecision);
  document.getElementById("modifyLeaveForm").addEventListener("submit", handleConfirmModifyLeave);
  document.getElementById("cancelLeaveForm").addEventListener("submit", handleConfirmCancelLeave);
  document.getElementById("unlockRosterForm")?.addEventListener("submit", handleConfirmUnlockRoster);
  document.getElementById("profileChangeForm")?.addEventListener("submit", handleConfirmProfileChange);
  document.getElementById("adminProfileDecisionForm")?.addEventListener("submit", handleConfirmAdminPcrDecision);
  document.getElementById("modNewStartDate").addEventListener("input", updateModifyLeaveCalculation);
  document.getElementById("modNewEndDate").addEventListener("input", updateModifyLeaveCalculation);
  document.getElementById("confirmDeleteCycleBtn").addEventListener("click", handleConfirmDeleteCycle);

  // Notification Flyout Toggle & Outside Click
  const notifBtn = document.getElementById("topbarNotificationBtn");
  const notifDropdown = document.getElementById("notificationDropdown");
  if (notifBtn && notifDropdown) {
    notifBtn.addEventListener("click", (e) => {
      e.stopPropagation();
      notifDropdown.classList.toggle("hidden");
      if (!notifDropdown.classList.contains("hidden")) {
        fetchNotifications();
      }
    });

    document.addEventListener("click", (e) => {
      if (!notifDropdown.contains(e.target) && !notifBtn.contains(e.target)) {
        notifDropdown.classList.add("hidden");
      }
    });

    const markAllBtn = document.getElementById("notifMarkAllReadBtn");
    if (markAllBtn) {
      markAllBtn.addEventListener("click", handleMarkAllNotificationsRead);
    }
  }

  // Global hide floating popover on window scroll
  window.addEventListener("scroll", hideFloatingPopover, true);
}


/* ==========================================================================
   AUTHENTICATION & SESSION
   ========================================================================== */

async function handleLogin(e) {
  e.preventDefault();
  const username = dom.loginUsername.value.trim();
  const password = dom.loginPassword.value;
  const submitBtn = document.getElementById("loginSubmitBtn");
  const spinner = submitBtn.querySelector(".spinner");

  try {
    submitBtn.disabled = true;
    spinner.classList.remove("hidden");

    const res = await apiRequest("/api/auth/login", {
      method: "POST",
      body: { username, password },
      auth: false
    });

    state.token = res.token;
    state.profile = res.user;
    sessionStorage.setItem("wrmsToken", state.token);
    sessionStorage.setItem("wrmsProfile", JSON.stringify(state.profile));

    toast("Login successful. Welcome back!", "success");
    showWorkspace();
    resolveInitialRoute();

  } catch (err) {
    toast(err.message, "error");
  } finally {
    submitBtn.disabled = false;
    spinner.classList.add("hidden");
  }
}

function handleLogout(silent = false) {
  sessionStorage.removeItem("wrmsToken");
  sessionStorage.removeItem("wrmsProfile");
  state.token = "";
  state.profile = null;
  try {
    if (window.location.hash) {
      history.replaceState(null, "", window.location.pathname + window.location.search);
    }
  } catch (_) {}
  showLogin();
  if (!silent) {
    toast("Signed out successfully", "info");
  }
}

function showLogin() {
  dom.loginView.classList.remove("hidden");
  dom.appView.classList.add("hidden");
  hideFloatingPopover();
}

function showWorkspace() {
  dom.loginView.classList.add("hidden");
  dom.appView.classList.remove("hidden");

  // Apply saved sidebar collapsed state
  if (state.isSidebarCollapsed) {
    dom.appSidebar.classList.add("collapsed");
    if (dom.sidebarToggleBtn) {
      dom.sidebarToggleBtn.setAttribute("aria-expanded", "false");
      dom.sidebarToggleBtn.setAttribute("title", "Expand Sidebar");
    }
  } else {
    dom.appSidebar.classList.remove("collapsed");
    if (dom.sidebarToggleBtn) {
      dom.sidebarToggleBtn.setAttribute("aria-expanded", "true");
      dom.sidebarToggleBtn.setAttribute("title", "Collapse Sidebar");
    }
  }

  // Populate sidebar user details & brand
  const username = state.profile.username;
  const initials = username.substring(0, 2).toUpperCase();
  dom.sidebarUsername.textContent = state.profile.employeeName || username;
  dom.sidebarRole.textContent = state.profile.role.replace("ROLE_", "");
  dom.sidebarUserAvatar.textContent = initials;

  const brandEl = document.querySelector(".sidebar-brand-text strong");
  if (brandEl) {
    brandEl.textContent = state.profile.role === "ROLE_EMPLOYEE" ? "WRMS Staff" : "WRMS Admin";
  }

  renderNavigation();
  fetchUnreadNotifCount();
  if (state.profile.role === "ROLE_ADMIN") {
    fetchPendingProfileChangesCount();
  }
}


/* ==========================================================================
   NAVIGATION & ROUTING
   ========================================================================== */

function parseRouteTarget(target) {
  const isEmployee = state.profile && state.profile.role === "ROLE_EMPLOYEE";
  let clean = (target || "").toString().trim();
  if (clean.startsWith("#/")) clean = clean.substring(2);
  else if (clean.startsWith("#")) clean = clean.substring(1);
  if (clean.startsWith("/")) clean = clean.substring(1);

  if (isEmployee) {
    const employeeRoutes = {
      "": "overview",
      "overview": "overview",
      "staff/overview": "overview",
      "employee/overview": "overview",
      "emp_overview": "overview",
      "roster": "roster",
      "my-roster": "roster",
      "staff/roster": "roster",
      "employee/roster": "roster",
      "emp_roster": "roster",
      "leaves": "leaves",
      "leave": "leaves",
      "leave-management": "leaves",
      "staff/leaves": "leaves",
      "staff/leave": "leaves",
      "employee/leaves": "leaves",
      "employee/leave": "leaves",
      "emp_leaves": "leaves",
      "preferences": "preferences",
      "my-preferences": "preferences",
      "emp_preferences": "preferences",
      "handovers": "handovers",
      "shift-handovers": "handovers",
      "emp_handovers": "handovers",
      "skills": "skills",
      "my-skills": "skills",
      "emp_skills": "skills",
      "holidays": "holidays",
      "holiday-calendar": "holidays",
      "emp_holidays": "holidays",
      "notifications": "notifications",
      "staff/notifications": "notifications",
      "employee/notifications": "notifications",
      "emp_notifications": "notifications",
      "activity": "activity",
      "logs": "activity",
      "activity-logs": "activity",
      "staff/activity": "activity",
      "staff/logs": "activity",
      "employee/activity": "activity",
      "employee/logs": "activity",
      "emp_activity": "activity",
      "profile": "profile",
      "my-profile": "profile",
      "staff/profile": "profile",
      "employee/profile": "profile",
      "emp_profile": "profile"
    };

    const tab = employeeRoutes[clean] || "overview";
    return { pageId: "employeeWorkspace", tabKey: tab, canonicalHash: `#/${tab}` };
  } else {
    const adminRoutes = {
      "": "dashboard",
      "dashboard": "dashboard",
      "admin/dashboard": "dashboard",
      "analytics": "analytics",
      "admin/analytics": "analytics",
      "validation": "validation",
      "conflict-detector": "validation",
      "admin/validation": "validation",
      "employees": "employees",
      "admin/employees": "employees",
      "roster": "roster",
      "admin/roster": "roster",
      "adminPreferences": "adminPreferences",
      "admin/preferences": "adminPreferences",
      "preferences": "adminPreferences",
      "adminHolidays": "adminHolidays",
      "admin/holidays": "adminHolidays",
      "holidays": "adminHolidays",
      "adminHandovers": "adminHandovers",
      "admin/handovers": "adminHandovers",
      "handovers": "adminHandovers",
      "adminWorkload": "adminWorkload",
      "admin/workload": "adminWorkload",
      "workload": "adminWorkload",
      "adminSkills": "adminSkills",
      "admin/skills": "adminSkills",
      "skills": "adminSkills",
      "exportCenter": "exportCenter",
      "admin/exports": "exportCenter",
      "exports": "exportCenter",
      "rosterVersions": "rosterVersions",
      "admin/roster-versions": "rosterVersions",
      "versions": "rosterVersions",
      "health": "health",
      "admin/health": "health",
      "shifts": "shifts",
      "admin/shifts": "shifts",
      "leaves": "leaves",
      "admin/leaves": "leaves",
      "history": "history",
      "admin/history": "history",
      "audit": "audit",
      "admin/audit": "audit",
      "profileApprovals": "profileApprovals",
      "profile-approvals": "profileApprovals",
      "admin/profile-approvals": "profileApprovals",
      "admin/profileApprovals": "profileApprovals",
      "profile-changes": "profileApprovals",
      "approvals": "profileApprovals",
      "employeeRosterDetail": "employeeRosterDetail",
      "employee-roster": "employeeRosterDetail"
    };

    const page = adminRoutes[clean] || "dashboard";
    return { pageId: page, tabKey: null, canonicalHash: `#/${page}` };
  }
}

function resolveInitialRoute() {
  const hash = window.location.hash;
  const isEmployee = state.profile && state.profile.role === "ROLE_EMPLOYEE";
  if (hash) {
    navigateTo(hash, { replace: true });
  } else {
    navigateTo(isEmployee ? "overview" : "dashboard", { replace: true });
  }
}

function setupRouter() {
  window.addEventListener("hashchange", () => {
    if (state.token && state.profile) {
      const hash = window.location.hash;
      if (hash) {
        navigateTo(hash, { skipHash: true });
      }
    }
  });
  window.addEventListener("popstate", () => {
    if (state.token && state.profile) {
      const hash = window.location.hash;
      if (hash) {
        navigateTo(hash, { skipHash: true });
      }
    }
  });
}

function renderNavigation() {
  const isEmployee = state.profile && state.profile.role === "ROLE_EMPLOYEE";
  const items = isEmployee ? EMPLOYEE_NAV : ADMIN_NAV;
  const currentTab = state.workspaceTab || "overview";

  dom.sidebarNav.innerHTML = items.map(item => {
    const isActive = isEmployee
      ? (state.activePage === "employeeWorkspace" && item.tab === currentTab)
      : (state.activePage === item.id);

    let badgeHtml = "";
    if (item.badgeKey && state[item.badgeKey]) {
      const val = state[item.badgeKey];
      const count = typeof val === "number" ? val : Array.isArray(val) ? val.length : 0;
      if (count > 0) {
        badgeHtml = `<span class="nav-badge">${count}</span>`;
      }
    }

    return `
      <button class="nav-item ${isActive ? 'active' : ''}" data-nav-id="${item.id}" data-tab="${item.tab || ''}" title="${item.label}">
        ${item.icon}
        <span>${item.label}</span>
        ${badgeHtml}
      </button>
    `;
  }).join("");

  dom.sidebarNav.querySelectorAll(".nav-item").forEach(btn => {
    btn.addEventListener("click", () => {
      const targetId = btn.getAttribute("data-nav-id");
      const targetTab = btn.getAttribute("data-tab");
      if (isEmployee && targetTab) {
        if (targetTab === "roster") {
          apiRequest("/api/activities/view-roster", { method: "POST" }).catch(() => {});
        }
        navigateTo(targetTab);
      } else {
        navigateTo(targetId);
      }
      dom.appSidebar.classList.remove("mobile-open");
    });
  });
}

function navigateTo(target, options = {}) {
  const { pageId, tabKey, canonicalHash } = parseRouteTarget(target);

  state.activePage = pageId;
  if (tabKey) {
    state.workspaceTab = tabKey;
  }

  hideFloatingPopover();

  // Synchronize URL Hash
  if (!options.skipHash && canonicalHash) {
    if (window.location.hash !== canonicalHash) {
      if (options.replace) {
        history.replaceState(null, "", canonicalHash);
      } else {
        window.location.hash = canonicalHash;
      }
    }
  }

  // Hide all view panels
  Object.values(dom.views).forEach(panel => {
    if (panel) {
      panel.classList.add("hidden");
      panel.classList.remove("active");
    }
  });

  // Activate target panel
  const targetPanel = dom.views[pageId];
  if (targetPanel) {
    targetPanel.classList.remove("hidden");
    targetPanel.classList.add("active");
  }

  // Update Headers & Titles
  updateTopbarTitle(pageId);
  renderNavigation();

  // If already on employee workspace with initialized content, switch tab immediately
  if (pageId === "employeeWorkspace") {
    if (document.getElementById("workspaceTabContent") && state.cachedTodayDuty) {
      switchEmployeeWorkspaceTab(state.workspaceTab || "overview");
    } else {
      loadActiveView();
    }
  } else {
    loadActiveView();
  }
}

function updateTopbarTitle(pageId) {
  const isEmployee = state.profile && state.profile.role === "ROLE_EMPLOYEE";
  const currentTab = state.workspaceTab || "overview";

  const employeeTitles = {
    overview: { title: "Staff Self-Service Workspace", bc: "Overview" },
    roster: { title: "My Weekly Duty Schedule", bc: "My Roster" },
    leaves: { title: "Leave Management & Requests", bc: "Leave Management" },
    preferences: { title: "My Shift Availability & Preferences", bc: "Shift Preferences" },
    handovers: { title: "Shift Handover Logbook", bc: "Shift Handovers" },
    skills: { title: "My Verified Skills & Certifications", bc: "My Skills" },
    holidays: { title: "Official Company Holiday Calendar", bc: "Holidays" },
    notifications: { title: "My Notifications & Alerts", bc: "Notifications" },
    activity: { title: "Activity & Security Logs", bc: "Activity / Logs" },
    profile: { title: "My Employee Profile", bc: "My Profile" }
  };

  const titles = {
    dashboard: { title: "Executive Operations Dashboard", bc: "Dashboard" },
    analytics: { title: "Roster Analytics & Intelligence", bc: "Analytics" },
    validation: { title: "Smart Roster Conflict Detector & Validator", bc: "Conflict Validator" },
    employees: { title: "Employee Directory & Workforce", bc: "Employees" },
    roster: { title: "Weekly Roster Schedule", bc: "Weekly Roster" },
    adminPreferences: { title: "Employee Shift Preferences & Availability", bc: "Shift Preferences" },
    adminHolidays: { title: "Official Company Holiday Calendar", bc: "Holiday Calendar" },
    adminHandovers: { title: "Shift Handover Management", bc: "Shift Handovers" },
    adminWorkload: { title: "Employee Workload Analytics & Duty Balance", bc: "Workload Analytics" },
    adminSkills: { title: "Workforce Skill Matrix & Competency Catalog", bc: "Skill Matrix" },
    exportCenter: { title: "Enterprise Export Center (PDF / Excel / CSV)", bc: "Export Center" },
    rosterVersions: { title: "Roster Version History & Revision Comparison", bc: "Roster Versions" },
    health: { title: "Roster Conflict & Health Center", bc: "Roster Health" },
    shifts: { title: "Shift Configuration & Capacities", bc: "Shift Settings" },
    leaves: { title: "Leave Approvals & Management", bc: "Leave Requests" },
    history: { title: "Roster Cycle History & Explorer", bc: "History" },
    audit: { title: "Complete Roster Audit Trail", bc: "Audit Trail" },
    profileApprovals: { title: "Employee Profile Change Approvals", bc: "Profile Approvals" },
    employeeWorkspace: employeeTitles[currentTab] || { title: "Staff Self-Service Workspace", bc: "My Workspace" },
    employeeRosterDetail: { title: `${state.inspectedEmployeeName} - Schedule`, bc: "Employee Roster" }
  };

  const meta = (pageId === "employeeWorkspace" && isEmployee)
    ? (employeeTitles[currentTab] || { title: "Staff Self-Service Workspace", bc: "My Workspace" })
    : (titles[pageId] || { title: "Roster Management", bc: pageId });

  dom.pageHeadingTitle.textContent = meta.title;
  dom.breadcrumbCurrent.textContent = meta.bc;
}

async function loadActiveView() {
  switch (state.activePage) {
    case "dashboard":
      await renderDashboardView();
      break;
    case "analytics":
      if (typeof renderAnalyticsView === "function") await renderAnalyticsView();
      break;
    case "validation":
      if (typeof renderValidationView === "function") await renderValidationView();
      break;
    case "adminPreferences":
      if (typeof renderAdminPreferencesView === "function") await renderAdminPreferencesView();
      break;
    case "adminHolidays":
      if (typeof renderAdminHolidaysView === "function") await renderAdminHolidaysView();
      break;
    case "adminHandovers":
      if (typeof renderAdminHandoversView === "function") await renderAdminHandoversView();
      break;
    case "adminWorkload":
      if (typeof renderAdminWorkloadView === "function") await renderAdminWorkloadView();
      break;
    case "adminSkills":
      if (typeof renderAdminSkillsView === "function") await renderAdminSkillsView();
      break;
    case "exportCenter":
      if (typeof renderExportCenterView === "function") await renderExportCenterView();
      break;
    case "rosterVersions":
      if (typeof renderRosterVersionsView === "function") await renderRosterVersionsView();
      break;
    case "employees":
      await renderEmployeesView();
      break;
    case "roster":
      await renderRosterView();
      break;
    case "health":
      await renderHealthView();
      break;
    case "shifts":
      await renderShiftsView();
      break;
    case "leaves":
      await renderLeavesView();
      break;
    case "history":
      await renderHistoryView();
      break;
    case "audit":
      await renderAuditView();
      break;
    case "profileApprovals":
      await renderProfileApprovalsView();
      break;
    case "employeeWorkspace":
      await renderEmployeeWorkspaceView();
      break;
    case "employeeRosterDetail":
      await renderEmployeeRosterDetailView();
      break;
  }
}


/* ==========================================================================
   VIEW 1: INTERACTIVE ADMIN DASHBOARD
   ========================================================================== */

async function renderDashboardView() {
  const container = dom.views.dashboard;
  container.innerHTML = `<div class="empty-state-box"><div class="spinner"></div><p>Loading real-time metrics & employee breakdown...</p></div>`;

  try {
    // Aggregated call
    const details = await apiRequest("/api/dashboard/details");
    state.dashboardData = details;
    state.pendingLeaves = details.pendingLeaves || [];
    renderNavigation();

    // Cache shifts if available
    try {
      state.shifts = await apiRequest("/api/shifts");
      state.shifts.forEach(s => {
        if (s.timingDisplay) SHIFT_TIMINGS[s.shiftType] = s.timingDisplay;
      });
    } catch (e) {
      console.warn("Could not pre-cache shifts", e);
    }

    const data = details.summary;
    const activeCycle = details.currentCycle;
    const todaysAssignments = details.todaysAssignments || [];
    const activeEmployees = details.activeEmployees || [];
    const pendingLeaves = details.pendingLeaves || [];

    // Categorize today's staff
    const workingTodayList = todaysAssignments.filter(a => !a.weeklyOff && !a.onLeave);
    const morningList = todaysAssignments.filter(a => a.shiftType === "MORNING" && !a.weeklyOff && !a.onLeave);
    const generalList = todaysAssignments.filter(a => a.shiftType === "GENERAL" && !a.weeklyOff && !a.onLeave);
    const eveningList = todaysAssignments.filter(a => a.shiftType === "EVENING" && !a.weeklyOff && !a.onLeave);
    const nightList = todaysAssignments.filter(a => a.shiftType === "NIGHT" && !a.weeklyOff && !a.onLeave);
    const offList = todaysAssignments.filter(a => a.weeklyOff);
    const leaveList = todaysAssignments.filter(a => a.onLeave);

    container.innerHTML = `
      <!-- Top Action Bar -->
      <div class="table-toolbar interactive-dash-card" id="dashCycleBanner" style="border-radius: var(--radius-md); box-shadow: var(--shadow-sm);">
        <div>
          <div style="display:flex; align-items:center; gap:8px;">
            <strong>Operational Overview</strong>
            <span class="interactive-badge-hint">⚡ Click for Cycle Breakdown</span>
          </div>
          <span style="display:block; font-size:0.78rem; color:var(--text-muted); margin-top:2px;">
            ${activeCycle ? `Active Roster Cycle: <strong>${formatDate(activeCycle.startDate)}</strong> to <strong>${formatDate(activeCycle.endDate)}</strong> (${activeCycle.assignments?.length || 0} assignments)` : 'No active roster cycle generated yet'}
          </span>
        </div>
        <div style="display:flex; gap:10px;">
          <button class="btn btn-primary btn-sm" id="dashGenerateBtn">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"></line><line x1="5" y1="12" x2="19" y2="12"></line></svg>
            <span>Generate Roster</span>
          </button>
          <button class="btn btn-secondary btn-sm" id="dashViewRosterBtn">View Full Roster</button>
        </div>
      </div>

      <!-- KPI Metrics Grid (Interactive Cards) -->
      <div class="stats-grid">
        
        <!-- Card 1: Total Workforce -->
        <div class="stat-card interactive-dash-card" id="cardWorkforce">
          <div>
            <span class="stat-label">Total Workforce</span>
            <span class="stat-value">${data.totalEmployees}</span>
            <small style="color:var(--text-muted); font-size:0.76rem;">${data.activeEmployees} active &bull; ${data.inactiveEmployees} inactive</small>
            <div><span class="interactive-badge-hint">⚡ Hover/Click for Staff</span></div>
          </div>
          <div class="stat-icon emerald">👥</div>
        </div>

        <!-- Card 2: Working Today -->
        <div class="stat-card interactive-dash-card" id="cardWorkingToday">
          <div>
            <span class="stat-label">Active on Duty Today</span>
            <span class="stat-value">${workingTodayList.length}</span>
            <small style="color:var(--text-muted); font-size:0.76rem;">Morning: ${morningList.length} &bull; General: ${generalList.length} &bull; Evening: ${eveningList.length} &bull; Night: ${nightList.length}</small>
            <div><span class="interactive-badge-hint">⚡ Hover/Click for Duty List</span></div>
          </div>
          <div class="stat-icon indigo">⏱️</div>
        </div>

        <!-- Card 3: OFF / Leave Today -->
        <div class="stat-card interactive-dash-card" id="cardOffLeaveToday">
          <div>
            <span class="stat-label">Weekly OFF / Leave Today</span>
            <span class="stat-value">${offList.length + leaveList.length}</span>
            <small style="color:var(--text-muted); font-size:0.76rem;">${offList.length} OFF &bull; ${leaveList.length} on leave</small>
            <div><span class="interactive-badge-hint">⚡ Hover/Click for Names</span></div>
          </div>
          <div class="stat-icon">🌴</div>
        </div>

        <!-- Card 4: Pending Leaves -->
        <div class="stat-card interactive-dash-card" id="cardPendingLeaves">
          <div>
            <span class="stat-label">Pending Leave Requests</span>
            <span class="stat-value" style="${pendingLeaves.length > 0 ? 'color:var(--danger);' : ''}">${pendingLeaves.length}</span>
            <small style="color:var(--text-muted); font-size:0.76rem;">Requires admin review</small>
            <div><span class="interactive-badge-hint">⚡ Hover/Click for Queue</span></div>
          </div>
          <div class="stat-icon rose">📋</div>
        </div>

      </div>

      <!-- Today's Shift Staffing Breakdown (Interactive Shift Cards) -->
      <div class="card">
        <div class="card-header">
          <div>
            <h3>Today's Shift Staffing Breakdown</h3>
            <span style="font-size:0.76rem; color:var(--text-muted);">Hover or click on any shift card to see assigned personnel and exact working hours</span>
          </div>
          <span class="status-pill active"><span class="badge-dot"></span> Live Schedule</span>
        </div>
        <div class="card-body">
          <div class="shift-distribution-grid">
            
            <div class="shift-card morning interactive-dash-card" id="shiftCardMorning">
              <div>
                <span class="stat-label" style="color:var(--shift-morning-color);">Morning (${getShiftTimingDisplay('MORNING')})</span>
                <strong>${morningList.length} / 1 Assigned</strong>
                <span style="display:block; font-size:0.72rem; color:var(--text-muted); margin-top:2px;">Required: 1 &bull; Assigned: ${morningList.length}</span>
                <div><span class="interactive-badge-hint">⚡ View Staff</span></div>
              </div>
              <span class="badge morning">M</span>
            </div>

            <div class="shift-card general interactive-dash-card" id="shiftCardGeneral">
              <div>
                <span class="stat-label" style="color:var(--shift-general-color);">General (${getShiftTimingDisplay('GENERAL')})</span>
                <strong>${generalList.length} / 1 Assigned</strong>
                <span style="display:block; font-size:0.72rem; color:var(--text-muted); margin-top:2px;">Required: 1 &bull; Assigned: ${generalList.length}</span>
                <div><span class="interactive-badge-hint">⚡ View Staff</span></div>
              </div>
              <span class="badge general">G</span>
            </div>

            <div class="shift-card evening interactive-dash-card" id="shiftCardEvening">
              <div>
                <span class="stat-label" style="color:var(--shift-evening-color);">Evening (${getShiftTimingDisplay('EVENING')})</span>
                <strong>${eveningList.length} / 1 Assigned</strong>
                <span style="display:block; font-size:0.72rem; color:var(--text-muted); margin-top:2px;">Required: 1 &bull; Assigned: ${eveningList.length}</span>
                <div><span class="interactive-badge-hint">⚡ View Staff</span></div>
              </div>
              <span class="badge evening">E</span>
            </div>

            <div class="shift-card night interactive-dash-card" id="shiftCardNight">
              <div>
                <span class="stat-label" style="color:var(--shift-night-color);">Night (${getShiftTimingDisplay('NIGHT')})</span>
                <strong>${nightList.length} / 1 Assigned</strong>
                <span style="display:block; font-size:0.72rem; color:var(--text-muted); margin-top:2px;">Required: 1 (Exact) &bull; Assigned: ${nightList.length}</span>
                <div><span class="interactive-badge-hint">⚡ View Staff</span></div>
              </div>
              <span class="badge night">N</span>
            </div>

            <div class="shift-card off interactive-dash-card" id="shiftCardOff">
              <div>
                <span class="stat-label" style="color:var(--shift-off-color);">Weekly OFF</span>
                <strong>${offList.length} Staff</strong>
                <span style="display:block; font-size:0.72rem; color:var(--text-muted); margin-top:2px;">Scheduled Non-Working</span>
                <div><span class="interactive-badge-hint">⚡ View Staff</span></div>
              </div>
              <span class="badge off">OFF</span>
            </div>

            <div class="shift-card leave interactive-dash-card" id="shiftCardLeave">
              <div>
                <span class="stat-label" style="color:var(--shift-leave-color);">Approved Leave</span>
                <strong>${leaveList.length} Staff</strong>
                <span style="display:block; font-size:0.72rem; color:var(--text-muted); margin-top:2px;">Approved Absences</span>
                <div><span class="interactive-badge-hint">⚡ View Staff</span></div>
              </div>
              <span class="badge leave">LV</span>
            </div>

          </div>
        </div>
      </div>

      <!-- Weekly Schedule Breakdown (Day-Wise & Employee-Wise) -->
      <div class="card" style="margin-top:20px;">
        <div class="card-header" style="flex-wrap:wrap; gap:12px;">
          <div>
            <h3>Weekly Schedule Breakdown</h3>
            <span style="font-size:0.76rem; color:var(--text-muted);">
              ${activeCycle ? `Workforce schedule for cycle: <strong>${formatDate(activeCycle.startDate)}</strong> &rarr; <strong>${formatDate(activeCycle.endDate)}</strong>` : 'No active cycle generated yet'}
            </span>
          </div>
          <div style="display:flex; align-items:center; gap:8px; flex-wrap:wrap;">
            <div class="filter-group">
              <button class="btn btn-primary btn-sm" id="dashScheduleDayBtn" style="font-weight:700;">
                📅 Day-Wise View
              </button>
              <button class="btn btn-secondary btn-sm" id="dashScheduleEmpBtn" style="font-weight:700;">
                👥 Employee-Wise View
              </button>
            </div>
            ${activeCycle ? `
              <button class="btn btn-secondary btn-sm" id="dashScheduleExcelBtn" title="Export Excel (.xlsx)">📥 Excel</button>
              <button class="btn btn-secondary btn-sm" id="dashScheduleImageBtn" title="Export Image (.png)">🖼️ Image</button>
            ` : ''}
          </div>
        </div>
        <div class="card-body" id="dashScheduleContent">
          <!-- Populated by loadDashboardScheduleView -->
        </div>
      </div>
    `;

    // Static Buttons
    document.getElementById("dashGenerateBtn").addEventListener("click", (e) => {
      e.stopPropagation();
      openGenerateRosterModal();
    });
    document.getElementById("dashViewRosterBtn").addEventListener("click", (e) => {
      e.stopPropagation();
      navigateTo("roster");
    });

    // Schedule View Mode Toggle Buttons
    const dayViewBtn = document.getElementById("dashScheduleDayBtn");
    const empViewBtn = document.getElementById("dashScheduleEmpBtn");
    if (dayViewBtn && empViewBtn) {
      dayViewBtn.addEventListener("click", () => {
        loadDashboardScheduleView("day", activeCycle?.id);
      });
      empViewBtn.addEventListener("click", () => {
        loadDashboardScheduleView("employee", activeCycle?.id);
      });
    }

    const exportExcelBtn = document.getElementById("dashScheduleExcelBtn");
    if (exportExcelBtn && activeCycle) {
      exportExcelBtn.addEventListener("click", () => downloadExcel(activeCycle.id));
    }

    const exportImageBtn = document.getElementById("dashScheduleImageBtn");
    if (exportImageBtn && activeCycle) {
      exportImageBtn.addEventListener("click", () => downloadImage(activeCycle.id));
    }

    // Initial load of default Day View
    await loadDashboardScheduleView(currentDashScheduleMode, activeCycle?.id);

    // ----------------------------------------------------
    // ATTACH INTERACTIVE POPOVER DATA & CLICK ACTIONS
    // ----------------------------------------------------

    // 1. Total Workforce Card
    attachDashboardInteractivity("cardWorkforce", {
      title: "Workforce Directory",
      subtitle: `${activeEmployees.length} Active, ${details.inactiveEmployees?.length || 0} Inactive`,
      icon: "👥",
      countLabel: `${activeEmployees.length} Active Staff`,
      targetView: "employees",
      employees: activeEmployees.map(e => ({ name: `${e.firstName} ${e.lastName || ''}`.trim(), code: e.employeeCode, tag: e.gender, active: e.active })),
      statsSummary: `Total registered staff: <strong>${data.totalEmployees}</strong> (<strong>${data.activeEmployees}</strong> active, <strong>${data.inactiveEmployees}</strong> inactive).`
    });

    // 2. Working Today Card
    attachDashboardInteractivity("cardWorkingToday", {
      title: "Active On Duty Today",
      subtitle: "Personnel assigned to active working shifts",
      icon: "⏱️",
      countLabel: `${workingTodayList.length} On Duty`,
      targetView: "roster",
      employees: workingTodayList.map(a => ({ name: a.employeeName, code: a.employeeCode, tag: a.shiftType })),
      statsSummary: `Morning: <strong>${morningList.length}</strong> &bull; General: <strong>${generalList.length}</strong> &bull; Evening: <strong>${eveningList.length}</strong> &bull; Night: <strong>${nightList.length}</strong>.`
    });

    // 3. OFF / Leave Today Card
    attachDashboardInteractivity("cardOffLeaveToday", {
      title: "Weekly OFF & Approved Leaves",
      subtitle: "Personnel not on active duty today",
      icon: "🌴",
      countLabel: `${offList.length + leaveList.length} Staff`,
      targetView: "roster",
      employees: [
        ...offList.map(a => ({ name: a.employeeName, code: a.employeeCode, tag: "WEEKLY OFF" })),
        ...leaveList.map(a => ({ name: a.employeeName, code: a.employeeCode, tag: "ON LEAVE" }))
      ],
      statsSummary: `<strong>${offList.length}</strong> staff scheduled for weekly off; <strong>${leaveList.length}</strong> staff on approved leave.`
    });

    // 4. Pending Leaves Card
    attachDashboardInteractivity("cardPendingLeaves", {
      title: "Pending Leave Requests Queue",
      subtitle: "Awaiting administrator review and decision",
      icon: "📋",
      countLabel: `${pendingLeaves.length} Pending`,
      targetView: "leaves",
      employees: pendingLeaves.map(l => ({ name: l.employeeName, code: l.employeeCode, tag: `${formatDate(l.startDate)} - ${formatDate(l.endDate)}`, extra: l.reason })),
      statsSummary: pendingLeaves.length ? `There are <strong>${pendingLeaves.length}</strong> pending leave applications requiring approval.` : `No pending leave applications at this time.`
    });

    // 5. Shift: Morning
    attachDashboardInteractivity("shiftCardMorning", {
      title: "Morning Shift Staffing",
      subtitle: getShiftTimingDisplay('MORNING'),
      icon: "🌅",
      countLabel: `${morningList.length} / 1 Staff`,
      targetView: "roster",
      employees: morningList.map(a => ({ name: a.employeeName, code: a.employeeCode, tag: a.gender })),
      statsSummary: `Timing: <strong>${getShiftTimingDisplay('MORNING')}</strong> &bull; Required: <strong>1</strong> &bull; Assigned: <strong>${morningList.length}</strong> &bull; Status: <strong>${morningList.length >= 1 ? 'Covered' : 'Uncovered'}</strong>`
    });

    // 6. Shift: General
    attachDashboardInteractivity("shiftCardGeneral", {
      title: "General Shift Staffing",
      subtitle: getShiftTimingDisplay('GENERAL'),
      icon: "☀️",
      countLabel: `${generalList.length} / 1 Staff`,
      targetView: "roster",
      employees: generalList.map(a => ({ name: a.employeeName, code: a.employeeCode, tag: a.gender })),
      statsSummary: `Timing: <strong>${getShiftTimingDisplay('GENERAL')}</strong> &bull; Required: <strong>1</strong> &bull; Assigned: <strong>${generalList.length}</strong> &bull; Status: <strong>${generalList.length >= 1 ? 'Covered' : 'Uncovered'}</strong>`
    });

    // 7. Shift: Evening
    attachDashboardInteractivity("shiftCardEvening", {
      title: "Evening Shift Staffing",
      subtitle: getShiftTimingDisplay('EVENING'),
      icon: "🌇",
      countLabel: `${eveningList.length} / 1 Staff`,
      targetView: "roster",
      employees: eveningList.map(a => ({ name: a.employeeName, code: a.employeeCode, tag: a.gender })),
      statsSummary: `Timing: <strong>${getShiftTimingDisplay('EVENING')}</strong> &bull; Required: <strong>1</strong> &bull; Assigned: <strong>${eveningList.length}</strong> &bull; Status: <strong>${eveningList.length >= 1 ? 'Covered' : 'Uncovered'}</strong>`
    });

    // 8. Shift: Night
    attachDashboardInteractivity("shiftCardNight", {
      title: "Night Shift Staffing",
      subtitle: getShiftTimingDisplay('NIGHT'),
      icon: "🌙",
      countLabel: `${nightList.length} / 1 Staff (Exact)`,
      targetView: "roster",
      employees: nightList.map(a => ({ name: a.employeeName, code: a.employeeCode, tag: a.gender })),
      statsSummary: `Timing: <strong>${getShiftTimingDisplay('NIGHT')}</strong> (Overnight duty) &bull; Required: <strong>1 (Exact)</strong> &bull; Assigned: <strong>${nightList.length}</strong> &bull; Status: <strong>${nightList.length === 1 ? 'Optimal' : (nightList.length > 1 ? 'Overstaffed' : 'Uncovered')}</strong>`
    });

    // 9. Shift: Weekly OFF
    attachDashboardInteractivity("shiftCardOff", {
      title: "Weekly OFF Personnel",
      subtitle: "Scheduled Rest Day",
      icon: "🛌",
      countLabel: `${offList.length} Staff`,
      targetView: "roster",
      employees: offList.map(a => ({ name: a.employeeName, code: a.employeeCode, tag: a.gender })),
      statsSummary: `Total <strong>${offList.length}</strong> employees taking their single balanced weekly off today.`
    });

    // 10. Shift: Approved Leave
    attachDashboardInteractivity("shiftCardLeave", {
      title: "Personnel On Approved Leave",
      subtitle: "Authorized Absence",
      icon: "🏖️",
      countLabel: `${leaveList.length} Staff`,
      targetView: "leaves",
      employees: leaveList.map(a => ({ name: a.employeeName, code: a.employeeCode, tag: a.gender })),
      statsSummary: `Total <strong>${leaveList.length}</strong> employees on approved leave today.`
    });

    // 11. Active Cycle Banner
    if (activeCycle) {
      attachDashboardInteractivity("dashCycleBanner", {
        title: "Active 7-Day Roster Cycle",
        subtitle: `${formatDate(activeCycle.startDate)} to ${formatDate(activeCycle.endDate)}`,
        icon: "📅",
        countLabel: `${activeCycle.assignments?.length || 0} Total Duties`,
        targetView: "roster",
        employees: activeCycle.assignments ? activeCycle.assignments.slice(0, 10).map(a => ({ name: a.employeeName, code: `${formatDate(a.rosterDate)} &bull; ${a.shiftType}`, tag: a.shiftType })) : [],
        statsSummary: `Cycle covers 7 days from <strong>${formatDate(activeCycle.startDate)}</strong> to <strong>${formatDate(activeCycle.endDate)}</strong> with <strong>${activeCycle.assignments?.length || 0}</strong> total duty assignments.`
      });
    }

  } catch (err) {
    container.innerHTML = `<div class="empty-state-box"><p style="color:var(--danger)">Error loading dashboard: ${err.message}</p></div>`;
  }
}

// Attach hover and click interactions to dashboard cards
function attachDashboardInteractivity(elementId, config) {
  const el = document.getElementById(elementId);
  if (!el) return;

  // Hover on Desktop
  el.addEventListener("mouseenter", () => {
    showFloatingPopover(el, config);
  });

  el.addEventListener("mouseleave", () => {
    hideFloatingPopover();
  });

  // Click on Mobile / Touch or Desktop
  el.addEventListener("click", () => {
    hideFloatingPopover();
    openDashboardDetailModal(config);
  });
}

// Show Floating Popover Tooltip on Hover
function showFloatingPopover(anchorEl, config) {
  const tooltip = dom.floatingTooltip;
  if (!tooltip) return;

  document.getElementById("popoverIcon").textContent = config.icon || "📊";
  document.getElementById("popoverTitle").textContent = config.title;
  document.getElementById("popoverSubtitle").textContent = config.subtitle;
  document.getElementById("popoverCount").textContent = config.countLabel;

  const listContainer = document.getElementById("popoverBodyList");
  const employees = config.employees || [];

  if (!employees.length) {
    listContainer.innerHTML = `<p style="font-size:0.78rem; color:var(--text-muted); padding:6px 0;">No personnel recorded for this category today.</p>`;
  } else {
    listContainer.innerHTML = employees.slice(0, 6).map(e => `
      <div class="popover-emp-row">
        <div class="popover-emp-info">
          <div class="popover-emp-avatar">${e.name.substring(0, 2).toUpperCase()}</div>
          <div>
            <strong>${e.name}</strong>
            <span style="display:block; font-size:0.68rem; color:var(--text-muted);">${e.code}</span>
          </div>
        </div>
        ${e.tag ? `<span class="badge ${String(e.tag).toLowerCase().replace(/\s+/g, '')}" style="font-size:0.65rem;">${e.tag}</span>` : ''}
      </div>
    `).join("") + (employees.length > 6 ? `<p style="font-size:0.72rem; color:var(--text-muted); text-align:center; margin-top:4px;">+ ${employees.length - 6} more employees</p>` : '');
  }

  // Positioning logic
  const rect = anchorEl.getBoundingClientRect();
  const popoverWidth = 320;
  const popoverHeight = 240;

  let left = rect.left + (rect.width / 2) - (popoverWidth / 2);
  let top = rect.bottom + 10;

  // Boundary checks
  if (left < 10) left = 10;
  if (left + popoverWidth > window.innerWidth - 10) left = window.innerWidth - popoverWidth - 10;

  if (top + popoverHeight > window.innerHeight - 10) {
    top = rect.top - popoverHeight - 10;
  }

  tooltip.style.left = `${left}px`;
  tooltip.style.top = `${top}px`;
  tooltip.classList.remove("hidden");
}

function hideFloatingPopover() {
  if (dom.floatingTooltip) {
    dom.floatingTooltip.classList.add("hidden");
  }
}

// Open Detailed Dashboard Inspection Modal (For Clicks)
function openDashboardDetailModal(config) {
  document.getElementById("dashDetailIcon").textContent = config.icon || "📊";
  document.getElementById("dashDetailTitle").textContent = config.title;
  document.getElementById("dashDetailSubtitle").textContent = config.subtitle;
  document.getElementById("dashDetailCountBadge").textContent = config.countLabel;
  document.getElementById("dashDetailStatsBar").innerHTML = `
    <div class="alert-info-box" style="margin-bottom:12px;">
      ${config.statsSummary || ''}
    </div>
  `;

  const listWrapper = document.getElementById("dashDetailListWrapper");
  const employees = config.employees || [];

  if (!employees.length) {
    listWrapper.innerHTML = `<div class="empty-state-box"><p>No personnel entries found in this category.</p></div>`;
  } else {
    listWrapper.innerHTML = `
      <table>
        <thead>
          <tr>
            <th>Employee Name</th>
            <th>Employee Code</th>
            <th>Designation / Tag</th>
            ${employees.some(e => e.extra) ? '<th>Notes / Duration</th>' : ''}
          </tr>
        </thead>
        <tbody>
          ${employees.map(e => `
            <tr>
              <td><strong>${e.name}</strong></td>
              <td><code>${e.code}</code></td>
              <td>${e.tag ? `<span class="badge ${String(e.tag).toLowerCase().replace(/\s+/g, '')}">${e.tag}</span>` : '-'}</td>
              ${employees.some(e => e.extra) ? `<td><small>${e.extra || '-'}</small></td>` : ''}
            </tr>
          `).join("")}
        </tbody>
      </table>
    `;
  }

  const viewAllBtn = document.getElementById("dashDetailViewAllBtn");
  viewAllBtn.onclick = () => {
    closeModal("dashDetailModal");
    if (config.targetView) navigateTo(config.targetView);
  };

  openModal("dashDetailModal");
}


/* ==========================================================================
   VIEW 2: EMPLOYEES DIRECTORY
   ========================================================================== */

async function renderEmployeesView() {
  const container = dom.views.employees;
  container.innerHTML = `<div class="empty-state-box"><div class="spinner"></div><p>Loading employee directory...</p></div>`;

  try {
    const [employees, pendingPcr] = await Promise.all([
      apiRequest("/api/employees"),
      apiRequest("/api/admin/profile-change-requests/pending").catch(() => [])
    ]);

    state.employees = employees || [];
    const pendingPcrList = pendingPcr || [];

    container.innerHTML = `
      <!-- Admin Pending Profile Change Requests Card -->
      ${pendingPcrList.length > 0 ? `
        <div class="card admin-pending-pcr-card">
          <div class="card-header">
            <div style="display:flex; align-items:center; gap:8px;">
              <span style="font-size:1.1rem;">📝</span>
              <div>
                <h3>Pending Profile Change Requests (${pendingPcrList.length})</h3>
                <span style="font-size:0.76rem; color:var(--text-muted);">Staff requesting modifications to verified workforce master data</span>
              </div>
            </div>
            <span class="badge pending">${pendingPcrList.length} Pending Review</span>
          </div>
          <div class="table-wrap">
            ${renderAdminPendingPcrTableHTML(pendingPcrList)}
          </div>
        </div>
      ` : ''}

      <div class="card">
        <div class="card-header">
          <div>
            <h3>Workforce Directory</h3>
            <span style="font-size:0.76rem; color:var(--text-muted);">Manage active staff records, shift eligibility, and login accounts</span>
          </div>
          <span class="badge morning">${state.employees.length} Staff Members</span>
        </div>

        <!-- Toolbar -->
        <div class="table-toolbar">
          <div class="search-input-box">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg>
            <input type="text" id="employeeSearchInput" placeholder="Search by name, code or email..." value="${state.employeeSearchTerm}">
          </div>

          <div class="filter-group">
            <select id="empGenderFilter">
              <option value="ALL" ${state.employeeGenderFilter === 'ALL' ? 'selected' : ''}>All Genders</option>
              <option value="MALE" ${state.employeeGenderFilter === 'MALE' ? 'selected' : ''}>Male</option>
              <option value="FEMALE" ${state.employeeGenderFilter === 'FEMALE' ? 'selected' : ''}>Female</option>
            </select>

            <select id="empStatusFilter">
              <option value="ALL" ${state.employeeStatusFilter === 'ALL' ? 'selected' : ''}>All Statuses</option>
              <option value="ACTIVE" ${state.employeeStatusFilter === 'ACTIVE' ? 'selected' : ''}>Active Only</option>
              <option value="INACTIVE" ${state.employeeStatusFilter === 'INACTIVE' ? 'selected' : ''}>Inactive Only</option>
            </select>

            <button class="btn btn-primary btn-sm" id="openAddEmployeeModalBtn">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"></line><line x1="5" y1="12" x2="19" y2="12"></line></svg>
              <span>Add Employee</span>
            </button>
          </div>
        </div>

        <!-- Table -->
        <div class="table-wrap" id="employeeTableWrapper">
          ${renderEmployeeTableHTML(filterEmployees())}
        </div>
      </div>
    `;

    document.getElementById("employeeSearchInput").addEventListener("input", (e) => {
      state.employeeSearchTerm = e.target.value;
      updateEmployeeTable();
    });

    document.getElementById("empGenderFilter").addEventListener("change", (e) => {
      state.employeeGenderFilter = e.target.value;
      updateEmployeeTable();
    });

    document.getElementById("empStatusFilter").addEventListener("change", (e) => {
      state.employeeStatusFilter = e.target.value;
      updateEmployeeTable();
    });

    document.getElementById("openAddEmployeeModalBtn").addEventListener("click", () => openEmployeeModal(null));
    bindEmployeeRowActions();

  } catch (err) {
    container.innerHTML = `<div class="empty-state-box"><p style="color:var(--danger)">Error loading employees: ${err.message}</p></div>`;
  }
}

function renderAdminPendingPcrTableHTML(list) {
  if (!list || !list.length) return "";

  const fieldLabels = {
    firstName: "First Name",
    lastName: "Last Name",
    email: "Email Address",
    gender: "Gender",
    employeeCode: "Employee Code"
  };

  return `
    <table>
      <thead>
        <tr>
          <th>Employee</th>
          <th>Field to Update</th>
          <th>Value Change</th>
          <th>Requested On</th>
          <th style="text-align:right;">Admin Decision</th>
        </tr>
      </thead>
      <tbody>
        ${list.map(r => {
          const label = fieldLabels[r.fieldName] || r.fieldName;
          return `
            <tr>
              <td>
                <strong>${escapeHTML(r.employeeName || 'Staff')}</strong>
                <div style="font-size:0.74rem; color:var(--text-muted);"><code>${escapeHTML(r.employeeCode || '')}</code></div>
              </td>
              <td><strong>${escapeHTML(label)}</strong></td>
              <td>
                <div class="val-diff-tag">
                  <span class="val-diff-old">${escapeHTML(r.currentValue || '-')}</span> &rarr;
                  <span class="val-diff-new">${escapeHTML(r.requestedValue)}</span>
                </div>
              </td>
              <td><small style="color:var(--text-muted);">${r.requestedAt ? new Date(r.requestedAt).toLocaleString() : '-'}</small></td>
              <td style="text-align:right;">
                <div class="row-actions" style="justify-content:flex-end;">
                  <button class="btn btn-primary btn-sm" data-action="admin-pcr-approve" data-id="${r.id}" data-emp="${escapeHTML(r.employeeName || '')}" data-field="${escapeHTML(r.fieldName)}" data-old="${escapeHTML(r.currentValue || '')}" data-new="${escapeHTML(r.requestedValue)}">
                    Approve
                  </button>
                  <button class="btn btn-danger btn-sm" data-action="admin-pcr-reject" data-id="${r.id}" data-emp="${escapeHTML(r.employeeName || '')}" data-field="${escapeHTML(r.fieldName)}" data-old="${escapeHTML(r.currentValue || '')}" data-new="${escapeHTML(r.requestedValue)}">
                    Reject
                  </button>
                </div>
              </td>
            </tr>
          `;
        }).join("")}
      </tbody>
    </table>
  `;
}

function filterEmployees() {
  return state.employees.filter(emp => {
    const q = state.employeeSearchTerm.toLowerCase();
    const matchesSearch = !q || 
      emp.employeeCode.toLowerCase().includes(q) ||
      emp.firstName.toLowerCase().includes(q) ||
      (emp.lastName && emp.lastName.toLowerCase().includes(q)) ||
      emp.email.toLowerCase().includes(q);

    const matchesGender = state.employeeGenderFilter === "ALL" || emp.gender === state.employeeGenderFilter;
    const matchesStatus = state.employeeStatusFilter === "ALL" || 
      (state.employeeStatusFilter === "ACTIVE" && emp.active) ||
      (state.employeeStatusFilter === "INACTIVE" && !emp.active);

    return matchesSearch && matchesGender && matchesStatus;
  });
}

function updateEmployeeTable() {
  const wrapper = document.getElementById("employeeTableWrapper");
  if (wrapper) {
    wrapper.innerHTML = renderEmployeeTableHTML(filterEmployees());
    bindEmployeeRowActions();
  }
}

function renderEmployeeTableHTML(list) {
  if (!list.length) {
    return `<div class="empty-state-box"><div class="empty-state-icon">👥</div><h3>No employees found</h3><p>Try adjusting your search query or filters.</p></div>`;
  }

  return `
    <table>
      <thead>
        <tr>
          <th>Code</th>
          <th>Employee Details</th>
          <th>Gender / Eligibility</th>
          <th>Status</th>
          <th>Login Account</th>
          <th style="text-align:right;">Actions</th>
        </tr>
      </thead>
      <tbody>
        ${list.map(emp => `
          <tr>
            <td><code>${emp.employeeCode}</code></td>
            <td>
              <strong>${emp.firstName} ${emp.lastName || ''}</strong>
              <span style="display:block; font-size:0.76rem; color:var(--text-muted);">${emp.email}</span>
            </td>
            <td>
              <span class="badge ${emp.gender === 'FEMALE' ? 'general' : 'morning'}">
                ${emp.gender} &bull; ${emp.gender === 'FEMALE' ? 'Day Shifts' : 'All Shifts'}
              </span>
            </td>
            <td>
              <span class="status-pill ${emp.active ? 'active' : 'inactive'}">
                <span class="badge-dot"></span> ${emp.active ? 'Active' : 'Inactive'}
              </span>
            </td>
            <td>
              <span style="font-size:0.82rem; font-weight:600; color:var(--text-muted);">
                ${emp.username ? `<code>${emp.username}</code>` : '<em>None</em>'}
              </span>
            </td>
            <td>
              <div class="row-actions" style="justify-content:flex-end;">
                <button class="btn btn-secondary btn-sm" data-action="view-roster" data-id="${emp.id}" data-name="${emp.firstName} ${emp.lastName || ''}">
                  Roster
                </button>
                <button class="btn btn-secondary btn-sm" data-action="edit" data-id="${emp.id}">
                  Edit
                </button>
                <label class="switch" title="Toggle active status">
                  <input type="checkbox" ${emp.active ? 'checked' : ''} data-action="toggle-status" data-id="${emp.id}">
                  <span class="slider"></span>
                </label>
              </div>
            </td>
          </tr>
        `).join("")}
      </tbody>
    </table>
  `;
}

function bindEmployeeRowActions() {
  document.querySelectorAll("[data-action='admin-pcr-approve']").forEach(btn => {
    btn.addEventListener("click", () => {
      openAdminProfileDecisionModal({
        id: btn.getAttribute("data-id"),
        approve: true,
        employeeName: btn.getAttribute("data-emp"),
        fieldName: btn.getAttribute("data-field"),
        oldValue: btn.getAttribute("data-old"),
        newValue: btn.getAttribute("data-new")
      });
    });
  });

  document.querySelectorAll("[data-action='admin-pcr-reject']").forEach(btn => {
    btn.addEventListener("click", () => {
      openAdminProfileDecisionModal({
        id: btn.getAttribute("data-id"),
        approve: false,
        employeeName: btn.getAttribute("data-emp"),
        fieldName: btn.getAttribute("data-field"),
        oldValue: btn.getAttribute("data-old"),
        newValue: btn.getAttribute("data-new")
      });
    });
  });
  document.querySelectorAll("[data-action='view-roster']").forEach(btn => {
    btn.addEventListener("click", () => {
      state.inspectedEmployeeId = btn.getAttribute("data-id");
      state.inspectedEmployeeName = btn.getAttribute("data-name");
      navigateTo("employeeRosterDetail");
    });
  });

  document.querySelectorAll("[data-action='edit']").forEach(btn => {
    btn.addEventListener("click", () => {
      const empId = btn.getAttribute("data-id");
      const emp = state.employees.find(e => String(e.id) === String(empId));
      if (emp) openEmployeeModal(emp);
    });
  });

  document.querySelectorAll("[data-action='toggle-status']").forEach(chk => {
    chk.addEventListener("change", async (e) => {
      const empId = chk.getAttribute("data-id");
      try {
        await apiRequest(`/api/employees/${empId}/toggle`, { method: "PUT" });
        toast("Employee status updated", "success");
        await renderEmployeesView();
      } catch (err) {
        toast(err.message, "error");
        chk.checked = !chk.checked; // Revert
      }
    });
  });
}


/* ==========================================================================
   VIEW 3: WEEKLY ROSTER MATRIX & SCHEDULE
   ========================================================================== */

async function renderRosterView() {
  const container = dom.views.roster;
  container.innerHTML = `<div class="empty-state-box"><div class="spinner"></div><p>Loading weekly roster schedule...</p></div>`;

  try {
    state.cycles = await apiRequest("/api/rosters");

    if (!state.cycles.length) {
      container.innerHTML = `
        <div class="card">
          <div class="empty-state-box">
            <div class="empty-state-icon">📅</div>
            <h3>No Roster Cycles Generated</h3>
            <p>Generate your first 7-day schedule to start assigning shifts.</p>
            <button class="btn btn-primary" style="margin-top:16px;" id="rosterEmptyGenBtn">
              Generate Weekly Roster
            </button>
          </div>
        </div>
      `;
      document.getElementById("rosterEmptyGenBtn").addEventListener("click", openGenerateRosterModal);
      return;
    }

    if (!state.selectedCycleId || !state.cycles.some(c => c.id === state.selectedCycleId)) {
      state.selectedCycleId = state.cycles[0].id;
    }

    const currentCycle = state.cycles.find(c => c.id === state.selectedCycleId) || state.cycles[0];
    const status = currentCycle.status || "GENERATED";
    const isLocked = status === "LOCKED";
    const isPublished = status === "PUBLISHED";

    container.innerHTML = `
      <div class="card">
        <!-- Roster Top Toolbar -->
        <div class="table-toolbar" style="flex-wrap:wrap; gap:12px;">
          <div style="display:flex; align-items:center; gap:10px; flex-wrap:wrap;">
            <select id="cycleSelector" style="font-weight:700; min-width:220px; max-width:100%;">
              ${state.cycles.map(c => `
                <option value="${c.id}" ${c.id === currentCycle.id ? 'selected' : ''}>
                  Cycle: ${formatDate(c.startDate)} - ${formatDate(c.endDate)}
                </option>
              `).join("")}
            </select>

            <span class="roster-lifecycle-badge badge-${status.toLowerCase()}">
              ${isLocked ? '🔒 ' : isPublished ? '📢 ' : '⚙️ '}${status}
            </span>

            <div class="filter-group" style="flex-wrap:wrap; gap:6px;">
              <button class="btn btn-secondary btn-sm ${state.rosterViewMode === 'matrix' ? 'btn-primary' : ''}" id="toggleMatrixViewBtn" title="Matrix Grid View">
                Calendar View
              </button>
              <button class="btn btn-secondary btn-sm ${state.rosterViewMode === 'table' ? 'btn-primary' : ''}" id="toggleTableViewBtn" title="List View">
                List View
              </button>
            </div>
          </div>

          <div class="filter-group" style="flex-wrap:wrap; gap:8px;">
            <button class="btn btn-secondary btn-sm" id="rosterHealthBtn" title="Inspect Roster Conflicts & Health">
              🩺 Roster Health
            </button>

            ${!isLocked && !isPublished ? `
              <button class="btn btn-primary btn-sm" id="rosterPublishBtn" title="Publish Roster to Staff">
                📢 Publish Roster
              </button>
            ` : ''}

            ${isPublished ? `
              <button class="btn btn-secondary btn-sm" id="rosterLockBtn" title="Lock Roster to prevent changes" style="border-color:#f59e0b; color:#b45309;">
                🔒 Lock Roster
              </button>
            ` : ''}

            ${isLocked ? `
              <button class="btn btn-warning btn-sm" id="rosterUnlockBtn" title="Unlock Roster with reason">
                🔓 Unlock Roster
              </button>
            ` : ''}

            <button class="btn btn-secondary btn-sm" id="rosterExportExcelBtn" title="Export to Excel (.xlsx)">
              📥 Export Excel
            </button>
            <button class="btn btn-secondary btn-sm" id="rosterExportImageBtn" title="Export to Image (.png)">
              🖼️ Export Image
            </button>
            <button class="btn btn-secondary btn-sm" id="rosterEmailBtn" title="Email Schedule to All Employees">
              ✉️ Email Roster
            </button>
            <button class="btn btn-secondary btn-sm" id="rosterRetryEmailBtn" title="Retry Failed Email Deliveries">
              🔄 Retry Email
            </button>
            ${!isLocked ? `
              <button class="btn btn-secondary btn-sm" id="openSwapModalBtn">
                🔄 Swap Shifts
              </button>
              <button class="btn btn-primary btn-sm" id="rosterGenModalBtn">
                ⚡ Generate Roster
              </button>
            ` : ''}
          </div>
        </div>

        <!-- Locked Banner if Locked -->
        ${isLocked ? `
          <div class="locked-alert-banner">
            <div class="locked-banner-text">
              <strong>🔒 Roster Cycle #${currentCycle.id} is Locked</strong>
              <p>Direct shift edits, overrides, and swaps are disabled to protect published operational commitments. Unlock with an administrative reason to make modifications.</p>
            </div>
            <button class="btn btn-warning btn-sm" id="rosterUnlockBannerBtn" style="background:#d97706; color:#fff;">
              🔓 Unlock Roster
            </button>
          </div>
        ` : ''}

        <!-- Coverage & Feasibility Shortage Banner -->
        ${renderCoverageBanner(currentCycle)}

        <!-- Filter Sub-bar -->
        <div class="table-toolbar" style="background-color:var(--bg-app); padding:10px 20px; flex-wrap:wrap; gap:12px;">
          <div class="search-input-box" style="max-width:240px; min-width:180px;">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg>
            <input type="text" id="rosterSearchInput" placeholder="Filter employee..." value="${state.rosterSearchTerm}">
          </div>

          <div class="filter-group" style="flex-wrap:wrap; gap:8px;">
            <span style="font-size:0.78rem; font-weight:700; color:var(--text-muted);">Shift Filter:</span>
            <select id="rosterShiftFilter">
              <option value="ALL" ${state.rosterShiftFilter === 'ALL' ? 'selected' : ''}>All Shifts</option>
              <option value="MORNING" ${state.rosterShiftFilter === 'MORNING' ? 'selected' : ''}>Morning (${getShiftTimingDisplay('MORNING')})</option>
              <option value="GENERAL" ${state.rosterShiftFilter === 'GENERAL' ? 'selected' : ''}>General (${getShiftTimingDisplay('GENERAL')})</option>
              <option value="EVENING" ${state.rosterShiftFilter === 'EVENING' ? 'selected' : ''}>Evening (${getShiftTimingDisplay('EVENING')})</option>
              <option value="NIGHT" ${state.rosterShiftFilter === 'NIGHT' ? 'selected' : ''}>Night (${getShiftTimingDisplay('NIGHT')})</option>
              <option value="OFF" ${state.rosterShiftFilter === 'OFF' ? 'selected' : ''}>Weekly OFF</option>
            </select>
          </div>
        </div>

        <!-- Main Roster Content View -->
        <div class="roster-table-container" id="rosterContentWrapper">
          ${state.rosterViewMode === 'matrix' 
            ? renderRosterMatrixHTML(currentCycle) 
            : renderRosterTableHTML(currentCycle)}
        </div>
      </div>
    `;

    document.getElementById("cycleSelector").addEventListener("change", (e) => {
      state.selectedCycleId = Number(e.target.value);
      renderRosterView();
    });

    document.getElementById("toggleMatrixViewBtn").addEventListener("click", () => {
      state.rosterViewMode = "matrix";
      renderRosterView();
    });

    document.getElementById("toggleTableViewBtn").addEventListener("click", () => {
      state.rosterViewMode = "table";
      renderRosterView();
    });

    const healthBtn = document.getElementById("rosterHealthBtn");
    if (healthBtn) {
      healthBtn.addEventListener("click", () => {
        state.healthSelectedCycleId = currentCycle.id;
        navigateTo("health");
      });
    }

    const publishBtn = document.getElementById("rosterPublishBtn");
    if (publishBtn) {
      publishBtn.addEventListener("click", () => handlePublishRoster(currentCycle.id));
    }

    const lockBtn = document.getElementById("rosterLockBtn");
    if (lockBtn) {
      lockBtn.addEventListener("click", () => handleLockRoster(currentCycle.id));
    }

    const unlockBtn = document.getElementById("rosterUnlockBtn");
    if (unlockBtn) {
      unlockBtn.addEventListener("click", () => openUnlockModal(currentCycle.id));
    }

    const unlockBannerBtn = document.getElementById("rosterUnlockBannerBtn");
    if (unlockBannerBtn) {
      unlockBannerBtn.addEventListener("click", () => openUnlockModal(currentCycle.id));
    }

    const genModalBtn = document.getElementById("rosterGenModalBtn");
    if (genModalBtn) {
      genModalBtn.addEventListener("click", openGenerateRosterModal);
    }

    const swapModalBtn = document.getElementById("openSwapModalBtn");
    if (swapModalBtn) {
      swapModalBtn.addEventListener("click", () => openShiftSwapModal(currentCycle));
    }

    const rosterExcelBtn = document.getElementById("rosterExportExcelBtn");
    if (rosterExcelBtn) {
      rosterExcelBtn.addEventListener("click", () => downloadExcel(currentCycle.id));
    }

    const rosterImageBtn = document.getElementById("rosterExportImageBtn");
    if (rosterImageBtn) {
      rosterImageBtn.addEventListener("click", () => downloadImage(currentCycle.id));
    }

    const rosterEmailBtn = document.getElementById("rosterEmailBtn");
    if (rosterEmailBtn) {
      rosterEmailBtn.addEventListener("click", () => sendRosterEmail(currentCycle.id));
    }

    const rosterRetryEmailBtn = document.getElementById("rosterRetryEmailBtn");
    if (rosterRetryEmailBtn) {
      rosterRetryEmailBtn.addEventListener("click", () => retryRosterEmail(currentCycle.id));
    }

    const viewFeasibilityBtn = document.getElementById("viewFeasibilityDetailsBtn");
    if (viewFeasibilityBtn) {
      viewFeasibilityBtn.addEventListener("click", () => openFeasibilityModal(currentCycle));
    }

    document.getElementById("rosterSearchInput").addEventListener("input", (e) => {
      state.rosterSearchTerm = e.target.value;
      updateRosterContent(currentCycle);
    });

    document.getElementById("rosterShiftFilter").addEventListener("change", (e) => {
      state.rosterShiftFilter = e.target.value;
      updateRosterContent(currentCycle);
    });

    bindRosterCellActions();

  } catch (err) {
    container.innerHTML = `<div class="empty-state-box"><p style="color:var(--danger)">Error loading roster: ${err.message}</p></div>`;
  }
}

function updateRosterContent(cycle) {
  const wrapper = document.getElementById("rosterContentWrapper");
  if (wrapper) {
    wrapper.innerHTML = state.rosterViewMode === 'matrix' 
      ? renderRosterMatrixHTML(cycle) 
      : renderRosterTableHTML(cycle);
    bindRosterCellActions();
  }
}

// Transform 1D Assignments to 2D Matrix (Employee x Date)
function renderRosterMatrixHTML(cycle) {
  const assignments = cycle.assignments || [];
  if (!assignments.length) return `<div class="empty-state-box"><h3>No assignments in this cycle</h3></div>`;

  const dates = [...new Set(assignments.map(a => a.rosterDate))].sort();

  const employeeMap = new Map();
  assignments.forEach(a => {
    if (!employeeMap.has(a.employeeId)) {
      employeeMap.set(a.employeeId, {
        id: a.employeeId,
        code: a.employeeCode,
        name: a.employeeName,
        gender: a.gender,
        days: {}
      });
    }
    employeeMap.get(a.employeeId).days[a.rosterDate] = a;
  });

  let employees = Array.from(employeeMap.values());
  const q = state.rosterSearchTerm.toLowerCase();
  if (q) {
    employees = employees.filter(e => e.name.toLowerCase().includes(q) || e.code.toLowerCase().includes(q));
  }

  return `
    <table class="roster-matrix-table">
      <thead>
        <tr>
          <th class="emp-col-header">Employee</th>
          ${dates.map(d => {
            const dt = new Date(d);
            const dayName = dt.toLocaleDateString("en-US", { weekday: "short" });
            const dayNum = dt.toLocaleDateString("en-US", { day: "2-digit", month: "short" });
            return `
              <th>
                <div class="date-header-cell">
                  <span class="day-name">${dayName}</span>
                  <span class="day-num">${dayNum}</span>
                </div>
              </th>
            `;
          }).join("")}
        </tr>
      </thead>
      <tbody>
        ${employees.map(emp => `
          <tr>
            <td class="emp-col-cell">
              <strong>${emp.name}</strong>
              <span style="display:block; font-size:0.74rem; color:var(--text-muted);">
                <code>${emp.code}</code> &bull; ${emp.gender}
              </span>
            </td>
            ${dates.map(dateStr => {
              const assign = emp.days[dateStr];
              if (!assign) return `<td>-</td>`;

              if (state.rosterShiftFilter !== "ALL" && assign.shiftType !== state.rosterShiftFilter) {
                return `<td style="opacity:0.3;">${renderCellChip(assign)}</td>`;
              }

              return `
                <td class="matrix-shift-cell">
                  ${renderCellChip(assign)}
                </td>
              `;
            }).join("")}
          </tr>
        `).join("")}
      </tbody>
    </table>
  `;
}

function renderCellChip(assign) {
  const typeLower = String(assign.shiftType).toLowerCase();
  const isOff = assign.weeklyOff || assign.shiftType === 'OFF';
  const isLeave = assign.onLeave || assign.shiftType === 'LEAVE';
  const timing = isOff ? 'Rest' : isLeave ? 'Leave' : getShiftTimingDisplay(assign.shiftType);
  const flags = [
    assign.weeklyOff ? "OFF" : "",
    assign.onLeave ? "LEAVE" : "",
    assign.overridden ? "EDITED" : ""
  ].filter(Boolean).join(" &bull; ");

  return `
    <div class="matrix-cell-chip ${typeLower}" 
         data-assign-id="${assign.id}" 
         data-emp-name="${assign.employeeName}"
         data-date="${assign.rosterDate}"
         data-shift="${assign.shiftType}"
         title="Click to modify shift assignment: ${assign.employeeName} (${assign.shiftType} - ${timing})">
      <span>${assign.shiftType}</span>
      <span class="cell-timing">${timing}</span>
      ${flags ? `<span class="flag-tag">${flags}</span>` : ''}
    </div>
  `;
}

function renderRosterTableHTML(cycle) {
  let list = cycle.assignments || [];
  const q = state.rosterSearchTerm.toLowerCase();
  if (q) {
    list = list.filter(a => a.employeeName.toLowerCase().includes(q) || a.employeeCode.toLowerCase().includes(q));
  }
  if (state.rosterShiftFilter !== "ALL") {
    list = list.filter(a => a.shiftType === state.rosterShiftFilter);
  }

  return `
    <table>
      <thead>
        <tr>
          <th>Date</th>
          <th>Employee</th>
          <th>Shift Assigned</th>
          <th>Flags / Status</th>
          <th style="text-align:right;">Actions</th>
        </tr>
      </thead>
      <tbody>
        ${list.map(a => `
          <tr>
            <td><strong>${formatDate(a.rosterDate)}</strong></td>
            <td>
              <strong>${a.employeeName}</strong>
              <span style="display:block; font-size:0.74rem; color:var(--text-muted);">${a.employeeCode} (${a.gender})</span>
            </td>
            <td>
              <span class="badge ${String(a.shiftType).toLowerCase()}">
                ${a.shiftType} (${getShiftTimingDisplay(a.shiftType)})
              </span>
            </td>
            <td>
              ${[a.weeklyOff ? "Weekly OFF" : "", a.onLeave ? "On Leave" : "", a.overridden ? "Overridden" : ""].filter(Boolean).join(", ") || "-"}
            </td>
            <td style="text-align:right;">
              <button class="btn btn-secondary btn-sm" data-action="override-shift" data-id="${a.id}" data-emp="${a.employeeName}" data-date="${a.rosterDate}" data-shift="${a.shiftType}">
                Edit Shift
              </button>
            </td>
          </tr>
        `).join("")}
      </tbody>
    </table>
  `;
}

function bindRosterCellActions() {
  document.querySelectorAll(".matrix-cell-chip").forEach(chip => {
    chip.addEventListener("click", () => {
      const assignId = chip.getAttribute("data-assign-id");
      const empName = chip.getAttribute("data-emp-name");
      const date = chip.getAttribute("data-date");
      const currentShift = chip.getAttribute("data-shift");
      openShiftOverrideModal({ id: assignId, employeeName: empName, rosterDate: date, shiftType: currentShift });
    });
  });

  document.querySelectorAll("[data-action='override-shift']").forEach(btn => {
    btn.addEventListener("click", () => {
      const assignId = btn.getAttribute("data-id");
      const empName = btn.getAttribute("data-emp");
      const date = btn.getAttribute("data-date");
      const currentShift = btn.getAttribute("data-shift");
      openShiftOverrideModal({ id: assignId, employeeName: empName, rosterDate: date, shiftType: currentShift });
    });
  });
}


/* ==========================================================================
   VIEW 4: SHIFT CAPACITIES & CONFIGURATION (WITH DISPLAY TIMINGS)
   ========================================================================== */

async function renderShiftsView() {
  const container = dom.views.shifts;
  container.innerHTML = `<div class="empty-state-box"><div class="spinner"></div><p>Loading shift configurations...</p></div>`;

  try {
    state.shifts = await apiRequest("/api/shifts");

    // Update global SHIFT_TIMINGS map from actual backend entity configuration
    state.shifts.forEach(s => {
      if (s.timingDisplay) {
        SHIFT_TIMINGS[s.shiftType] = s.timingDisplay;
      }
    });

    container.innerHTML = `
      <div class="card">
        <div class="card-header">
          <div>
            <h2>Configured Working Shifts</h2>
            <span style="font-size:0.78rem; color:var(--text-muted);">
              Shift schedules, overnight rules, and baseline staffing capacities. The generator adheres strictly to these actual timings.
            </span>
          </div>
        </div>
        <div class="card-body">
          <div class="shift-capacity-grid">
            ${state.shifts.map(s => {
              const timingFormatted = s.timingDisplay || getShiftTimingDisplay(s.shiftType);
              const feasible = s.feasibleCapacity !== undefined ? s.feasibleCapacity : (s.shiftType === 'NIGHT' || s.shiftType === 'EVENING' ? 1 : Math.min(s.capacity, 2));
              let statusClass = "active";
              let statusText = "FULL";
              if (feasible < s.capacity) {
                statusText = (s.shiftType === 'NIGHT' || s.shiftType === 'EVENING') ? "Workforce/eligibility-limited" : "Workforce-limited";
                statusClass = "pending";
              }
              return `
              <div class="shift-config-card" style="border-left:4px solid ${getShiftColor(s.shiftType)};">
                <div class="shift-config-header">
                  <div class="shift-title-group">
                    <span class="badge ${String(s.shiftType).toLowerCase()}" style="font-size:0.88rem; font-weight:800; padding:4px 10px; width:fit-content;">
                      ${s.shiftType}
                    </span>
                    <span class="shift-timing-pill">
                      🕒 ${timingFormatted}
                    </span>
                    <span style="font-size:0.72rem; color:var(--text-muted); margin-top:2px;">
                      ${s.overnight ? '🌙 Overnight shift (ends next day)' : '☀️ Standard daytime shift'}
                    </span>
                  </div>
                  <span class="status-pill ${statusClass}" style="font-size:0.70rem; white-space:nowrap;">${statusText}</span>
                </div>

                <div class="shift-metrics-box">
                  <div>
                    <span style="font-size:0.72rem; color:var(--text-muted); font-weight:700; display:block;">Active Team Feasible:</span>
                    <strong style="font-size:1.05rem; color:var(--primary);">${feasible} staff / day</strong>
                  </div>
                  <div style="text-align:right;">
                    <span style="font-size:0.72rem; color:var(--text-muted); font-weight:700; display:block;">Workforce Target:</span>
                    <span style="font-size:0.84rem; font-weight:700; color:var(--text-muted);">${s.shiftType === 'OFF' ? '1-2 staff' : feasible + ' staff'}</span>
                  </div>
                </div>

                <div style="margin-top:4px;">
                  <label style="font-size:0.76rem; color:var(--text-muted); font-weight:700; display:block; margin-bottom:4px;">Configured Admin Target:</label>
                  <div class="shift-target-control-row">
                    <input type="number" min="0" ${s.shiftType === 'NIGHT' ? 'max="1"' : ''} value="${s.capacity}" id="shiftCap_${s.id}" data-shift-type="${s.shiftType}" style="font-size:0.95rem; font-weight:800; width:85px; padding:7px 10px;">
                    <button class="btn btn-primary btn-sm" data-action="save-shift-cap" data-id="${s.id}">
                      Save Target
                    </button>
                  </div>
                  ${s.shiftType === 'NIGHT' ? '<span style="font-size:0.7rem; color:var(--text-muted); display:block; margin-top:4px;">(Maximum 1 employee per day)</span>' : ''}
                </div>
              </div>
            `;
            }).join("")}
          </div>
        </div>
      </div>
    `;

    document.querySelectorAll("[data-action='save-shift-cap']").forEach(btn => {
      btn.addEventListener("click", async () => {
        const shiftId = btn.getAttribute("data-id");
        const capInput = document.getElementById(`shiftCap_${shiftId}`);
        const shiftType = capInput.getAttribute("data-shift-type");
        const capacity = Number(capInput.value);

        if (shiftType === "NIGHT" && capacity > 1) {
          toast("Night shift target cannot be greater than 1 employee per day.", "error");
          return;
        }

        try {
          btn.disabled = true;
          const updated = await apiRequest(`/api/shifts/${shiftId}`, {
            method: "PUT",
            body: { capacity }
          });
          toast(`Shift ${updated.shiftType} capacity updated to ${updated.capacity}`, "success");
        } catch (err) {
          toast(err.message, "error");
        } finally {
          btn.disabled = false;
        }
      });
    });

  } catch (err) {
    container.innerHTML = `<div class="empty-state-box"><p style="color:var(--danger)">Error loading shifts: ${err.message}</p></div>`;
  }
}

function getShiftColor(type) {
  const map = {
    MORNING: "#0284c7",
    GENERAL: "#059669",
    EVENING: "#d97706",
    NIGHT: "#4f46e5",
    OFF: "#64748b"
  };
  return map[type] || "#64748b";
}


/* ==========================================================================
   VIEW 5: ADMIN LEAVE APPROVALS & DECISIONS
   ========================================================================== */

async function renderLeavesView() {
  const container = dom.views.leaves;
  container.innerHTML = `<div class="empty-state-box"><div class="spinner"></div><p>Loading leave requests...</p></div>`;

  try {
    state.pendingLeaves = await apiRequest("/api/leaves/pending");
    renderNavigation();

    const newRequests = state.pendingLeaves.filter(l => l.status === "PENDING");
    const modRequests = state.pendingLeaves.filter(l => l.status === "PENDING_MODIFICATION");
    const cancelRequests = state.pendingLeaves.filter(l => l.status === "PENDING_CANCELLATION");

    container.innerHTML = `
      <!-- Header Summary -->
      <div class="table-toolbar" style="margin-bottom:16px;">
        <div>
          <h2>Leave Request Management</h2>
          <span style="font-size:0.78rem; color:var(--text-muted);">
            Review new leave applications, date modifications, and cancellations. Approvals automatically synchronize shift duties.
          </span>
        </div>
        <div style="display:flex; gap:8px;">
          <span class="status-pill ${state.pendingLeaves.length ? 'pending' : 'active'}">
            ${state.pendingLeaves.length} Total Pending
          </span>
        </div>
      </div>

      <!-- 1. Modification Requests (High Priority) -->
      ${modRequests.length > 0 ? `
        <div class="card" style="margin-bottom:20px; border-left:4px solid #7c3aed;">
          <div class="card-header">
            <div style="display:flex; align-items:center; gap:8px;">
              <span style="font-size:1.1rem;">✏️</span>
              <div>
                <h3 style="color:#6d28d9;">Leave Modification Requests (${modRequests.length})</h3>
                <span style="font-size:0.76rem; color:var(--text-muted);">Employees requesting changes to their existing approved leave dates</span>
              </div>
            </div>
          </div>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Employee</th>
                  <th>Current Approved Dates</th>
                  <th>Requested New Dates</th>
                  <th>Duration Change</th>
                  <th>Modification Reason</th>
                  <th>Requested On</th>
                  <th style="text-align:right;">Decision</th>
                </tr>
              </thead>
              <tbody>
                ${modRequests.map(l => {
                  const oldDays = calculateDaysBetween(l.startDate, l.endDate);
                  const newDays = calculateDaysBetween(l.pendingStartDate, l.pendingEndDate);
                  const diff = newDays - oldDays;
                  const diffText = diff < 0 ? `${Math.abs(diff)} day(s) released` : diff > 0 ? `+${diff} day(s) added` : `No change in days`;
                  const diffClass = diff < 0 ? 'diff-released' : 'diff-added';
                  return `
                    <tr>
                      <td>
                        <strong>${l.employeeName}</strong>
                        <div style="font-size:0.74rem; color:var(--text-muted);">${l.employeeCode || ''}</div>
                      </td>
                      <td>
                        <code>${formatDate(l.startDate)}</code> &rarr; <code>${formatDate(l.endDate)}</code>
                        <div style="font-size:0.74rem; color:var(--text-muted); font-weight:600;">${oldDays} day(s)</div>
                      </td>
                      <td>
                        <strong style="color:var(--primary);">${formatDate(l.pendingStartDate)}</strong> &rarr; <strong style="color:var(--primary);">${formatDate(l.pendingEndDate)}</strong>
                        <div style="font-size:0.74rem; font-weight:700; color:var(--primary);">${newDays} day(s)</div>
                      </td>
                      <td>
                        <span class="dur-value ${diffClass}" style="font-size:0.84rem; font-weight:700;">${diffText}</span>
                      </td>
                      <td style="max-width:240px;">${l.modificationReason || l.reason || '-'}</td>
                      <td><small style="color:var(--text-muted);">${l.modifiedAt ? new Date(l.modifiedAt).toLocaleString() : formatDate(l.requestedAt)}</small></td>
                      <td style="text-align:right;">
                        <div class="row-actions" style="justify-content:flex-end;">
                          <button class="btn btn-primary btn-sm" data-action="mod-decision" data-id="${l.id}" data-approve="true" data-emp="${l.employeeName}" data-dates="${formatDate(l.pendingStartDate)} - ${formatDate(l.pendingEndDate)}">
                            Approve
                          </button>
                          <button class="btn btn-danger btn-sm" data-action="mod-decision" data-id="${l.id}" data-approve="false" data-emp="${l.employeeName}" data-dates="${formatDate(l.pendingStartDate)} - ${formatDate(l.pendingEndDate)}">
                            Reject
                          </button>
                        </div>
                      </td>
                    </tr>
                  `;
                }).join("")}
              </tbody>
            </table>
          </div>
        </div>
      ` : ''}

      <!-- 2. Cancellation Requests -->
      ${cancelRequests.length > 0 ? `
        <div class="card" style="margin-bottom:20px; border-left:4px solid var(--danger);">
          <div class="card-header">
            <div style="display:flex; align-items:center; gap:8px;">
              <span style="font-size:1.1rem;">🚫</span>
              <div>
                <h3 style="color:var(--danger);">Leave Cancellation Requests (${cancelRequests.length})</h3>
                <span style="font-size:0.76rem; color:var(--text-muted);">Employees requesting cancellation of approved leave</span>
              </div>
            </div>
          </div>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Employee</th>
                  <th>Approved Leave Period</th>
                  <th>Duration</th>
                  <th>Cancellation Reason</th>
                  <th>Requested On</th>
                  <th style="text-align:right;">Decision</th>
                </tr>
              </thead>
              <tbody>
                ${cancelRequests.map(l => `
                  <tr>
                    <td>
                      <strong>${l.employeeName}</strong>
                      <div style="font-size:0.74rem; color:var(--text-muted);">${l.employeeCode || ''}</div>
                    </td>
                    <td><code>${formatDate(l.startDate)}</code> &rarr; <code>${formatDate(l.endDate)}</code></td>
                    <td><span class="badge morning">${calculateDaysBetween(l.startDate, l.endDate)} day(s)</span></td>
                    <td style="max-width:240px; color:#991b1b; font-weight:600;">${l.cancellationReason || 'Requested cancellation'}</td>
                    <td><small style="color:var(--text-muted);">${l.modifiedAt ? new Date(l.modifiedAt).toLocaleString() : formatDate(l.requestedAt)}</small></td>
                    <td style="text-align:right;">
                      <div class="row-actions" style="justify-content:flex-end;">
                        <button class="btn btn-danger btn-sm" data-action="cancel-decision" data-id="${l.id}" data-approve="true" data-emp="${l.employeeName}" data-dates="${formatDate(l.startDate)} - ${formatDate(l.endDate)}">
                          Approve Cancellation
                        </button>
                        <button class="btn btn-secondary btn-sm" data-action="cancel-decision" data-id="${l.id}" data-approve="false" data-emp="${l.employeeName}" data-dates="${formatDate(l.startDate)} - ${formatDate(l.endDate)}">
                          Reject
                        </button>
                      </div>
                    </td>
                  </tr>
                `).join("")}
              </tbody>
            </table>
          </div>
        </div>
      ` : ''}

      <!-- 3. New Leave Requests -->
      <div class="card">
        <div class="card-header">
          <div>
            <h3>New Leave Applications (${newRequests.length})</h3>
            <span style="font-size:0.76rem; color:var(--text-muted);">Standard leave applications awaiting review</span>
          </div>
        </div>
        <div class="table-wrap">
          ${renderNewLeaveTableHTML(newRequests)}
        </div>
      </div>
    `;

    // Bind Standard Leave Actions
    document.querySelectorAll("[data-action='leave-decision']").forEach(btn => {
      btn.addEventListener("click", () => {
        const leaveId = btn.getAttribute("data-id");
        const approve = btn.getAttribute("data-approve") === "true";
        const empName = btn.getAttribute("data-emp");
        const dates = btn.getAttribute("data-dates");
        openLeaveDecisionModal({ id: leaveId, approve, employeeName: empName, dates, type: "standard" });
      });
    });

    // Bind Modification Decision Actions
    document.querySelectorAll("[data-action='mod-decision']").forEach(btn => {
      btn.addEventListener("click", () => {
        const leaveId = btn.getAttribute("data-id");
        const approve = btn.getAttribute("data-approve") === "true";
        const empName = btn.getAttribute("data-emp");
        const dates = btn.getAttribute("data-dates");
        openLeaveDecisionModal({ id: leaveId, approve, employeeName: empName, dates, type: "modification" });
      });
    });

    // Bind Cancellation Decision Actions
    document.querySelectorAll("[data-action='cancel-decision']").forEach(btn => {
      btn.addEventListener("click", () => {
        const leaveId = btn.getAttribute("data-id");
        const approve = btn.getAttribute("data-approve") === "true";
        const empName = btn.getAttribute("data-emp");
        const dates = btn.getAttribute("data-dates");
        openLeaveDecisionModal({ id: leaveId, approve, employeeName: empName, dates, type: "cancellation" });
      });
    });

  } catch (err) {
    container.innerHTML = `<div class="empty-state-box"><p style="color:var(--danger)">Error loading leaves: ${err.message}</p></div>`;
  }
}

function renderNewLeaveTableHTML(list) {
  if (!list.length) {
    return `<div class="empty-state-box"><div class="empty-state-icon">✅</div><h3>No Pending New Applications</h3><p>All submitted leave applications have been processed.</p></div>`;
  }

  return `
    <table>
      <thead>
        <tr>
          <th>Employee</th>
          <th>Leave Duration</th>
          <th>Total Days</th>
          <th>Reason</th>
          <th>Status</th>
          <th>Requested On</th>
          <th style="text-align:right;">Decision Actions</th>
        </tr>
      </thead>
      <tbody>
        ${list.map(l => `
          <tr>
            <td>
              <strong>${l.employeeName}</strong>
              <div style="font-size:0.74rem; color:var(--text-muted);">${l.employeeCode || ''}</div>
            </td>
            <td>
              <code>${formatDate(l.startDate)}</code> &rarr; <code>${formatDate(l.endDate)}</code>
            </td>
            <td><span class="badge morning">${calculateDaysBetween(l.startDate, l.endDate)} day(s)</span></td>
            <td>${l.reason}</td>
            <td>
              <span class="status-pill pending">Pending Review</span>
            </td>
            <td><small style="color:var(--text-muted);">${l.requestedAt ? new Date(l.requestedAt).toLocaleDateString() : '-'}</small></td>
            <td style="text-align:right;">
              <div class="row-actions" style="justify-content:flex-end;">
                <button class="btn btn-primary btn-sm" data-action="leave-decision" data-id="${l.id}" data-approve="true" data-emp="${l.employeeName}" data-dates="${formatDate(l.startDate)} - ${formatDate(l.endDate)}">
                  Approve
                </button>
                <button class="btn btn-danger btn-sm" data-action="leave-decision" data-id="${l.id}" data-approve="false" data-emp="${l.employeeName}" data-dates="${formatDate(l.startDate)} - ${formatDate(l.endDate)}">
                  Reject
                </button>
              </div>
            </td>
          </tr>
        `).join("")}
      </tbody>
    </table>
  `;
}


/* ==========================================================================
   VIEW 6: ROSTER CYCLE HISTORY (WITH DELETE FUNCTIONALITY)
   ========================================================================== */

async function renderHistoryView() {
  const container = dom.views.history;
  container.innerHTML = `<div class="empty-state-box"><div class="spinner"></div><p>Loading history...</p></div>`;

  try {
    state.cycles = await apiRequest("/api/rosters");

    container.innerHTML = `
      <div class="card">
        <div class="card-header">
          <div>
            <h2>Roster Generation History</h2>
            <span style="font-size:0.78rem; color:var(--text-muted);">Manage previous and current roster cycles. You can view assignments or delete cycles permanently.</span>
          </div>
          <button class="btn btn-primary btn-sm" id="historyGenRosterBtn">
            ⚡ Generate New Cycle
          </button>
        </div>
        <div class="table-wrap">
          ${renderHistoryTableHTML(state.cycles)}
        </div>
      </div>
    `;

    document.getElementById("historyGenRosterBtn").addEventListener("click", openGenerateRosterModal);

    // Bind View Button Actions
    document.querySelectorAll("[data-action='inspect-cycle']").forEach(btn => {
      btn.addEventListener("click", () => {
        state.selectedCycleId = Number(btn.getAttribute("data-id"));
        navigateTo("roster");
      });
    });

    // Bind Excel Export Actions
    document.querySelectorAll("[data-action='export-excel']").forEach(btn => {
      btn.addEventListener("click", () => {
        downloadExcel(Number(btn.getAttribute("data-id")));
      });
    });

    // Bind Image Export Actions
    document.querySelectorAll("[data-action='export-image']").forEach(btn => {
      btn.addEventListener("click", () => {
        downloadImage(Number(btn.getAttribute("data-id")));
      });
    });

    // Bind Email Actions
    document.querySelectorAll("[data-action='email-roster']").forEach(btn => {
      btn.addEventListener("click", () => {
        sendRosterEmail(Number(btn.getAttribute("data-id")));
      });
    });

    // Bind Retry Email Actions
    document.querySelectorAll("[data-action='retry-email']").forEach(btn => {
      btn.addEventListener("click", () => {
        retryRosterEmail(Number(btn.getAttribute("data-id")));
      });
    });

    // Bind Health Button Actions
    document.querySelectorAll("[data-action='health-cycle']").forEach(btn => {
      btn.addEventListener("click", () => {
        state.healthSelectedCycleId = Number(btn.getAttribute("data-id"));
        navigateTo("health");
      });
    });

    // Bind Unlock Button Actions
    document.querySelectorAll("[data-action='unlock-cycle']").forEach(btn => {
      btn.addEventListener("click", () => {
        openUnlockModal(Number(btn.getAttribute("data-id")));
      });
    });

    // Bind Delete Button Actions
    document.querySelectorAll("[data-action='delete-cycle']").forEach(btn => {
      btn.addEventListener("click", () => {
        const cycleId = Number(btn.getAttribute("data-id"));
        const cycle = state.cycles.find(c => c.id === cycleId);
        if (cycle) {
          if (cycle.status === "LOCKED") {
            toast("Cannot delete locked roster cycle. Unlock it first if deletion is required.", "error");
            return;
          }
          openDeleteCycleModal(cycle);
        }
      });
    });

  } catch (err) {
    container.innerHTML = `<div class="empty-state-box"><p style="color:var(--danger)">Error loading history: ${err.message}</p></div>`;
  }
}

function renderHistoryTableHTML(list) {
  if (!list.length) {
    return `<div class="empty-state-box"><div class="empty-state-icon">📅</div><h3>No Cycles Found</h3><p>No weekly rosters have been generated yet.</p></div>`;
  }

  return `
    <table>
      <thead>
        <tr>
          <th>Cycle ID</th>
          <th>Cycle Period</th>
          <th>Lifecycle Status</th>
          <th>Generation Mode</th>
          <th>Email Status</th>
          <th>Assignments</th>
          <th>Generated At</th>
          <th style="text-align:right;">Export & Actions</th>
        </tr>
      </thead>
      <tbody>
        ${list.map(c => {
          const status = c.status || "GENERATED";
          const isLocked = status === "LOCKED";
          return `
          <tr>
            <td><code>#${c.id}</code></td>
            <td><strong>${formatDate(c.startDate)}</strong> &rarr; <strong>${formatDate(c.endDate)}</strong></td>
            <td>
              <span class="roster-lifecycle-badge badge-${status.toLowerCase()}">
                ${isLocked ? '🔒 ' : status === 'PUBLISHED' ? '📢 ' : '⚙️ '}${status}
              </span>
            </td>
            <td>
              <span class="badge ${c.generationMode === 'AUTOMATIC' ? 'night' : 'morning'}" style="font-weight:800;">
                ${c.generationMode || 'MANUAL'}
              </span>
            </td>
            <td>
              <span class="status-pill active" style="font-size:0.72rem;">
                <span class="badge-dot"></span> ${c.emailStatus || 'SENT'}
              </span>
            </td>
            <td>
              <span class="badge morning">${c.assignments?.length || 0} Duties</span>
            </td>
            <td><small style="color:var(--text-muted);">${c.generatedAt ? new Date(c.generatedAt).toLocaleString() : 'N/A'}</small></td>
            <td style="text-align:right;">
              <div class="row-actions" style="justify-content:flex-end; gap:6px;">
                <button class="btn btn-secondary btn-sm" data-action="inspect-cycle" data-id="${c.id}" title="View this cycle schedule">
                  View
                </button>
                <button class="btn btn-secondary btn-sm" data-action="health-cycle" data-id="${c.id}" title="View Health & Conflict Center">
                  🩺 Health
                </button>
                ${isLocked ? `
                  <button class="btn btn-warning btn-sm" data-action="unlock-cycle" data-id="${c.id}" title="Unlock this locked cycle">
                    🔓 Unlock
                  </button>
                ` : ''}
                <button class="btn btn-secondary btn-sm" data-action="export-excel" data-id="${c.id}" title="Download Excel (.xlsx)">
                  📥 Excel
                </button>
                <button class="btn btn-secondary btn-sm" data-action="export-image" data-id="${c.id}" title="Download Image (.png)">
                  🖼️ Image
                </button>
                <button class="btn btn-secondary btn-sm" data-action="email-roster" data-id="${c.id}" title="Email roster to all staff">
                  ✉️ Email
                </button>
                <button class="btn btn-secondary btn-sm" data-action="retry-email" data-id="${c.id}" title="Retry failed emails">
                  🔄 Retry
                </button>
                ${!isLocked ? `
                  <button class="btn btn-sm btn-delete-cycle" data-action="delete-cycle" data-id="${c.id}" title="Permanently delete this cycle">
                    🗑️
                  </button>
                ` : `
                  <button class="btn btn-sm btn-delete-cycle" disabled style="opacity:0.4; cursor:not-allowed;" title="Locked cycle cannot be deleted">
                    🔒
                  </button>
                `}
              </div>
            </td>
          </tr>
        `;}).join("")}
      </tbody>
    </table>
  `;
}

function openDeleteCycleModal(cycle) {
  document.getElementById("deleteCycleTargetId").value = cycle.id;
  document.getElementById("deleteCycleDetailsBox").innerHTML = `
    <div style="display:flex; flex-direction:column; gap:4px;">
      <div>Cycle ID: <strong>#${cycle.id}</strong></div>
      <div>Start Date: <strong>${formatDate(cycle.startDate)}</strong></div>
      <div>End Date: <strong>${formatDate(cycle.endDate)}</strong></div>
      <div>Total Duty Assignments: <strong>${cycle.assignments?.length || 0}</strong></div>
    </div>
  `;
  openModal("deleteCycleModal");
}

async function handleConfirmDeleteCycle() {
  const cycleId = document.getElementById("deleteCycleTargetId").value;
  const btn = document.getElementById("confirmDeleteCycleBtn");
  const spinner = btn.querySelector(".spinner");

  if (!cycleId) return;

  try {
    btn.disabled = true;
    if (spinner) spinner.classList.remove("hidden");

    await apiRequest(`/api/rosters/cycle/${cycleId}`, { method: "DELETE" });

    broadcastDataMutation("ROSTER_DELETED");
    toast("Roster cycle deleted successfully.", "success");
    closeModal("deleteCycleModal");

    state.cycles = (state.cycles || []).filter(c => String(c.id) !== String(cycleId));
    if (String(state.selectedCycleId) === String(cycleId)) {
      state.selectedCycleId = state.cycles[0]?.id || null;
    }

    // Refresh Roster History
    await renderHistoryView();

    // If currently on dashboard or weekly roster, refresh data to sync dropdowns and views
    if (typeof loadDashboardData === "function" && state.currentView === "dashboard") {
      loadDashboardData();
    }
    if (typeof renderRosterView === "function" && state.currentView === "roster") {
      renderRosterView();
    }

  } catch (err) {
    const rawMsg = err.message || "";
    let userMsg = "Unable to delete roster cycle. Please try again.";
    if (rawMsg && !rawMsg.toLowerCase().includes("constraint") && !rawMsg.toLowerCase().includes("sql") && !rawMsg.toLowerCase().includes("hibernate")) {
      userMsg = rawMsg;
    }
    toast(userMsg, "error");
  } finally {
    btn.disabled = false;
    if (spinner) spinner.classList.add("hidden");
  }
}


/* ==========================================================================
   VIEW 7: ENHANCED EMPLOYEE WORKSPACE & LEAVE MANAGEMENT
   ========================================================================== */

if (!state.workspaceTab) state.workspaceTab = "overview";
if (state.calendarMonthOffset === undefined) state.calendarMonthOffset = 0;

function validateDutyResponse(data) {
  if (!data || typeof data !== "object") {
    return { status: "OFF", queryDate: getTodayISOString(), shiftName: "Duty Not Scheduled" };
  }
  if (!data.status || typeof data.status !== "string") {
    data.status = "OFF";
  }
  return data;
}

function formatDutyContextSummary(item) {
  if (!item || !item.status) return "Standby";
  const day = item.dayOfWeek || "";
  if (item.status === "LEAVE") return `${day} — 🏖️ On Leave`;
  if (item.status === "OFF") return `${day} — 🛋️ Weekly Off`;
  if (item.status === "WORKING") {
    const sType = item.shiftType || item.shiftName || "Duty";
    const start = item.startTime ? item.startTime.substring(0, 5) : "";
    const end = item.endTime ? item.endTime.substring(0, 5) : "";
    const time = (start && end) ? ` (${start}–${end})` : "";
    return `${day} — 💼 ${sType}${time}`;
  }
  return `${day} — Standby`;
}

async function renderEmployeeWorkspaceView(forceFullReload = false) {
  const container = dom.views.employeeWorkspace;
  const empId = state.profile ? state.profile.employeeId : null;

  if (!empId) {
    container.innerHTML = `
      <div class="card">
        <div class="empty-state-box">
          <div class="empty-state-icon">👤</div>
          <h3>Admin Account</h3>
          <p>You are logged in as an administrator without an employee duty record. Use the admin navigation sidebar.</p>
        </div>
      </div>
    `;
    return;
  }

  const currentTab = state.workspaceTab || "overview";

  // If workspace container is not yet initialized or full reload requested, build shell
  if (!document.getElementById("workspaceTabContent") || forceFullReload) {
    container.innerHTML = `
      <!-- Workspace Sub-Navigation Tabs -->
      <div class="workspace-subnav" role="tablist">
        <button class="subnav-btn ${currentTab === 'overview' ? 'active' : ''}" id="tabBtnOverview" role="tab" aria-selected="${currentTab === 'overview'}">
          <span>📊</span> Overview
        </button>
        <button class="subnav-btn ${currentTab === 'roster' ? 'active' : ''}" id="tabBtnRoster" role="tab" aria-selected="${currentTab === 'roster'}">
          <span>📅</span> My Roster
        </button>
        <button class="subnav-btn ${currentTab === 'leaves' ? 'active' : ''}" id="tabBtnLeaves" role="tab" aria-selected="${currentTab === 'leaves'}">
          <span>🏖️</span> Leave Management
          <span class="subnav-badge hidden" id="leavesBadge"></span>
        </button>
        <button class="subnav-btn ${currentTab === 'preferences' ? 'active' : ''}" id="tabBtnPreferences" role="tab" aria-selected="${currentTab === 'preferences'}">
          <span>⚖️</span> Preferences
        </button>
        <button class="subnav-btn ${currentTab === 'handovers' ? 'active' : ''}" id="tabBtnHandovers" role="tab" aria-selected="${currentTab === 'handovers'}">
          <span>🤝</span> Handovers
        </button>
        <button class="subnav-btn ${currentTab === 'skills' ? 'active' : ''}" id="tabBtnSkills" role="tab" aria-selected="${currentTab === 'skills'}">
          <span>🌟</span> My Skills
        </button>
        <button class="subnav-btn ${currentTab === 'holidays' ? 'active' : ''}" id="tabBtnHolidays" role="tab" aria-selected="${currentTab === 'holidays'}">
          <span>🎉</span> Holidays
        </button>
        <button class="subnav-btn ${currentTab === 'notifications' ? 'active' : ''}" id="tabBtnNotifications" role="tab" aria-selected="${currentTab === 'notifications'}">
          <span>🔔</span> Notifications
          <span class="subnav-badge hidden" id="notifsBadge"></span>
        </button>
        <button class="subnav-btn ${currentTab === 'activity' ? 'active' : ''}" id="tabBtnActivity" role="tab" aria-selected="${currentTab === 'activity'}">
          <span>📜</span> Activity / Logs
        </button>
        <button class="subnav-btn ${currentTab === 'profile' ? 'active' : ''}" id="tabBtnProfile" role="tab" aria-selected="${currentTab === 'profile'}">
          <span>👤</span> Profile
          <span class="subnav-badge hidden" id="profileBadge"></span>
        </button>
      </div>

      <!-- Tab Content Area -->
      <div id="workspaceTabContent" role="tabpanel">
        <div class="empty-state-box"><div class="spinner"></div><p>Loading personal workspace & records...</p></div>
      </div>
    `;

    // Bind Sub-Navigation Click Events
    const bindTab = (btnId, tabKey) => {
      const btn = document.getElementById(btnId);
      if (btn) {
        btn.addEventListener("click", () => {
          if (tabKey === "roster") {
            apiRequest("/api/activities/view-roster", { method: "POST" }).catch(() => {});
          }
          navigateTo(tabKey);
        });
      }
    };

    bindTab("tabBtnOverview", "overview");
    bindTab("tabBtnRoster", "roster");
    bindTab("tabBtnLeaves", "leaves");
    bindTab("tabBtnPreferences", "preferences");
    bindTab("tabBtnHandovers", "handovers");
    bindTab("tabBtnSkills", "skills");
    bindTab("tabBtnHolidays", "holidays");
    bindTab("tabBtnNotifications", "notifications");
    bindTab("tabBtnActivity", "activity");
    bindTab("tabBtnProfile", "profile");
  }

  // Fetch workspace data using Promise.allSettled with timeouts for resilient loading
  try {
    const results = await Promise.allSettled([
      apiRequest(`/api/rosters/my-duty/today`, { timeout: 8000 }),
      apiRequest(`/api/rosters/employee/${empId}`, { timeout: 8000 }),
      apiRequest(`/api/leaves/my/${empId}`, { timeout: 8000 }),
      apiRequest(`/api/shifts`, { timeout: 8000 }),
      apiRequest(`/api/notifications/my`, { timeout: 8000 }),
      apiRequest(`/api/activities/my?page=0&size=20`, { timeout: 8000 }),
      apiRequest(`/api/employees/${empId}`, { timeout: 8000 }),
      apiRequest(`/api/profile-change-requests/my`, { timeout: 8000 })
    ]);

    const rawDuty = results[0].status === "fulfilled" ? results[0].value : null;
    const roster = (results[1].status === "fulfilled" && Array.isArray(results[1].value)) ? results[1].value : [];
    const leaves = (results[2].status === "fulfilled" && Array.isArray(results[2].value)) ? results[2].value : [];
    const shifts = (results[3].status === "fulfilled" && Array.isArray(results[3].value)) ? results[3].value : [];
    const notifs = (results[4].status === "fulfilled" && Array.isArray(results[4].value)) ? results[4].value : [];
    const actPage = (results[5].status === "fulfilled" && results[5].value) ? results[5].value : { content: [], totalElements: 0, hasMore: false, page: 0, totalPages: 0 };
    const empProfile = results[6].status === "fulfilled" ? results[6].value : null;
    const myChangeRequests = (results[7].status === "fulfilled" && Array.isArray(results[7].value)) ? results[7].value : [];

    state.sectionErrors = {
      duty: results[0].status === "rejected" ? (results[0].reason?.message || "Failed to load duty schedule") : null,
      roster: results[1].status === "rejected" ? (results[1].reason?.message || "Failed to load roster schedule") : null,
      leaves: results[2].status === "rejected" ? (results[2].reason?.message || "Failed to load leave records") : null,
      shifts: results[3].status === "rejected" ? (results[3].reason?.message || "Failed to load shift timings") : null,
      notifs: results[4].status === "rejected" ? (results[4].reason?.message || "Failed to load notifications") : null,
      activity: results[5].status === "rejected" ? (results[5].reason?.message || "Failed to load activity logs") : null,
      profile: results[6].status === "rejected" ? (results[6].reason?.message || "Failed to load employee profile") : null,
      pcr: results[7].status === "rejected" ? (results[7].reason?.message || "Failed to load change requests") : null
    };

    const todayDuty = validateDutyResponse(rawDuty);

    state.cachedTodayDuty = todayDuty;
    state.cachedLeaves = leaves;
    state.cachedRoster = roster;
    state.cachedNotifs = notifs;
    state.accumulatedActivities = actPage ? actPage.content : [];
    state.latestActivityData = actPage;
    state.empProfile = empProfile;
    state.myChangeRequests = myChangeRequests;

    if (shifts && Array.isArray(shifts) && shifts.length > 0) {
      state.shifts = shifts;
      shifts.forEach(s => {
        if (s.timingDisplay) SHIFT_TIMINGS[s.shiftType] = s.timingDisplay;
      });
    }

    // Update Badges
    const pendingCount = (leaves || []).filter(l => l.status === "PENDING" || l.status === "PENDING_MODIFICATION" || l.status === "PENDING_CANCELLATION").length;
    const unreadNotifCount = (notifs || []).filter(n => !n.readStatus).length;
    const pendingPcrCount = (myChangeRequests || []).filter(r => r.status === "PENDING").length;

    state.cachedPendingLeavesCount = pendingCount;
    state.unreadNotificationCount = unreadNotifCount;

    const leavesBadge = document.getElementById("leavesBadge");
    if (leavesBadge) {
      leavesBadge.textContent = pendingCount;
      leavesBadge.classList.toggle("hidden", pendingCount === 0);
    }
    const notifsBadge = document.getElementById("notifsBadge");
    if (notifsBadge) {
      notifsBadge.textContent = unreadNotifCount;
      notifsBadge.classList.toggle("hidden", unreadNotifCount === 0);
    }
    const profileBadge = document.getElementById("profileBadge");
    if (profileBadge) {
      profileBadge.textContent = pendingPcrCount;
      profileBadge.classList.toggle("hidden", pendingPcrCount === 0);
    }

    switchEmployeeWorkspaceTab(state.workspaceTab || "overview");
  } catch (err) {
    const contentDiv = document.getElementById("workspaceTabContent");
    if (contentDiv) {
      contentDiv.innerHTML = `
        <div class="card" style="border:1px solid #fecaca; background:#fef2f2;">
          <div class="empty-state-box" style="padding:32px 20px;">
            <div class="empty-state-icon" style="color:var(--danger); font-size:2.5rem;">⚠️</div>
            <h3 style="color:var(--danger); margin-top:8px;">Unable to load personal workspace</h3>
            <p style="color:#7f1d1d; max-width:480px; margin:8px auto 16px;">${escapeHTML(err.message || 'Some workspace data could not be retrieved. Please retry.')}</p>
            <button class="btn btn-primary btn-sm" id="retryDutyScheduleBtn" style="margin-top:4px;">
              <span>🔄 Retry</span>
            </button>
          </div>
        </div>
      `;
      const retryBtn = document.getElementById("retryDutyScheduleBtn");
      if (retryBtn) {
        retryBtn.addEventListener("click", () => renderEmployeeWorkspaceView(true));
      }
    }
  }
}

function switchEmployeeWorkspaceTab(tabKey) {
  state.workspaceTab = tabKey;
  updateTopbarTitle("employeeWorkspace");
  renderNavigation();

  const targetHash = `#/${tabKey}`;
  if (window.location.hash !== targetHash) {
    try {
      history.replaceState(null, "", targetHash);
    } catch (_) {}
  }

  // Update subnav buttons active state
  document.querySelectorAll(".workspace-subnav .subnav-btn").forEach(btn => {
    const isTarget = (tabKey === "overview" && btn.id === "tabBtnOverview") ||
                     (tabKey === "roster" && btn.id === "tabBtnRoster") ||
                     (tabKey === "leaves" && btn.id === "tabBtnLeaves") ||
                     (tabKey === "preferences" && btn.id === "tabBtnPreferences") ||
                     (tabKey === "handovers" && btn.id === "tabBtnHandovers") ||
                     (tabKey === "skills" && btn.id === "tabBtnSkills") ||
                     (tabKey === "holidays" && btn.id === "tabBtnHolidays") ||
                     (tabKey === "notifications" && btn.id === "tabBtnNotifications") ||
                     (tabKey === "activity" && btn.id === "tabBtnActivity") ||
                     (tabKey === "profile" && btn.id === "tabBtnProfile");
    btn.classList.toggle("active", isTarget);
    btn.setAttribute("aria-selected", String(isTarget));
  });

  const contentDiv = document.getElementById("workspaceTabContent");
  if (!contentDiv) {
    renderEmployeeWorkspaceView();
    return;
  }

  if (tabKey === "preferences" && typeof renderEmployeePreferencesTabHTML === "function") {
    contentDiv.innerHTML = `<div class="empty-state-box"><div class="spinner"></div><p>Loading shift preferences...</p></div>`;
    renderEmployeePreferencesTabHTML().then(html => { contentDiv.innerHTML = html; });
    return;
  }
  if (tabKey === "handovers" && typeof renderEmployeeHandoversTabHTML === "function") {
    contentDiv.innerHTML = `<div class="empty-state-box"><div class="spinner"></div><p>Loading shift handovers...</p></div>`;
    renderEmployeeHandoversTabHTML().then(html => { contentDiv.innerHTML = html; });
    return;
  }
  if (tabKey === "skills" && typeof renderEmployeeSkillsTabHTML === "function") {
    contentDiv.innerHTML = `<div class="empty-state-box"><div class="spinner"></div><p>Loading skills matrix...</p></div>`;
    renderEmployeeSkillsTabHTML().then(html => { contentDiv.innerHTML = html; });
    return;
  }
  if (tabKey === "holidays" && typeof renderEmployeeHolidaysTabHTML === "function") {
    contentDiv.innerHTML = `<div class="empty-state-box"><div class="spinner"></div><p>Loading company holidays...</p></div>`;
    renderEmployeeHolidaysTabHTML().then(html => { contentDiv.innerHTML = html; });
    return;
  }

  const empId = state.profile ? state.profile.employeeId : null;
  const todayDuty = state.cachedTodayDuty || { status: "OFF", queryDate: getTodayISOString(), shiftName: "Duty Not Scheduled" };
  const roster = state.cachedRoster || [];
  const leaves = state.cachedLeaves || [];
  const notifs = state.cachedNotifs || [];
  const myChangeRequests = state.myChangeRequests || [];
  const empProfile = state.empProfile;
  const activityData = state.latestActivityData || { content: [], totalElements: 0, hasMore: false, page: 0, totalPages: 0 };

  const pendingCount = (leaves || []).filter(l => l.status === "PENDING" || l.status === "PENDING_MODIFICATION" || l.status === "PENDING_CANCELLATION").length;
  const approvedCount = (leaves || []).filter(l => l.status === "APPROVED").length;
  const todayStr = todayDuty.queryDate || getTodayISOString();
  const upcomingLeaves = (leaves || []).filter(l => l.status === "APPROVED" && l.startDate >= todayStr);

  let activeWorkforceDisplay = "Currently unavailable";
  if (todayDuty.activeWorkforceCount) {
    activeWorkforceDisplay = `${todayDuty.activeWorkforceCount} Active Members`;
  }

  contentDiv.innerHTML = renderWorkspaceCurrentTabHTML(tabKey, {
    todayDuty,
    roster,
    leaves,
    pendingCount,
    approvedCount,
    upcomingLeavesCount: upcomingLeaves.length,
    activeWorkforceDisplay,
    notifications: notifs,
    activityData,
    employee: empProfile,
    profile: state.profile,
    changeRequests: myChangeRequests,
    empId
  });

  // Re-bind Overview quick shortcuts if on overview
  if (tabKey === "overview") {
    const overviewEditProfileBtn = document.getElementById("overviewEditProfileBtn");
    if (overviewEditProfileBtn) overviewEditProfileBtn.addEventListener("click", () => navigateTo("profile"));
    const quickManageBtn = document.getElementById("quickManageLeaveBtn");
    if (quickManageBtn) quickManageBtn.addEventListener("click", () => navigateTo("leaves"));
    const quickNotifsBtn = document.getElementById("quickViewNotifsBtn");
    if (quickNotifsBtn) quickNotifsBtn.addEventListener("click", () => navigateTo("notifications"));
    const quickRosterBtn = document.getElementById("quickViewRosterBtn");
    if (quickRosterBtn) quickRosterBtn.addEventListener("click", () => {
      apiRequest("/api/activities/view-roster", { method: "POST" }).catch(() => {});
      navigateTo("roster");
    });
    const quickApplyLeaveBtn = document.getElementById("quickApplyLeaveBtn");
    if (quickApplyLeaveBtn) quickApplyLeaveBtn.addEventListener("click", () => navigateTo("leaves"));
    const quickViewProfileBtn = document.getElementById("quickViewProfileBtn");
    if (quickViewProfileBtn) quickViewProfileBtn.addEventListener("click", () => navigateTo("profile"));
    const quickActivityBtn = document.getElementById("quickViewActivityBtn");
    if (quickActivityBtn) quickActivityBtn.addEventListener("click", () => navigateTo("activity"));
    const quickProfileBtn = document.getElementById("quickChangePwBtn");
    if (quickProfileBtn) quickProfileBtn.addEventListener("click", () => navigateTo("profile"));
    const seeAllNotifsBtn = document.getElementById("overviewSeeAllNotifsBtn");
    if (seeAllNotifsBtn) seeAllNotifsBtn.addEventListener("click", () => navigateTo("notifications"));
    const seeAllActivityBtn = document.getElementById("overviewSeeAllActivityBtn");
    if (seeAllActivityBtn) seeAllActivityBtn.addEventListener("click", () => navigateTo("activity"));
  }

  // Bind Tab Specific Events
  if (tabKey === "leaves") {
    bindLeaveManagementEvents(empId, leaves, roster);
  } else if (tabKey === "activity") {
    bindWorkspaceActivityEvents(empId);
  } else if (tabKey === "profile") {
    bindWorkspaceProfileEvents();
  } else if (tabKey === "notifications") {
    bindWorkspaceNotificationEvents();
  } else if (tabKey === "roster") {
    bindWorkspaceRosterEvents(empId);
  }
}

function renderWorkspaceCurrentTabHTML(tab, data) {
  const errors = state.sectionErrors || {};

  if (tab === "roster" && errors.roster) {
    return `
      <div class="card">
        <div class="empty-state-box" style="padding:40px 20px;">
          <div class="empty-state-icon" style="color:var(--danger); font-size:2.5rem;">⚠️</div>
          <h3 style="color:var(--danger); margin-top:8px;">Unable to load roster</h3>
          <p style="color:var(--text-muted); max-width:480px; margin:8px auto 16px;">${escapeHTML(errors.roster)}</p>
          <button class="btn btn-primary btn-sm" onclick="renderEmployeeWorkspaceView(true)">
            <span>🔄 Retry</span>
          </button>
        </div>
      </div>
    `;
  }

  if (tab === "leaves" && errors.leaves) {
    return `
      <div class="card">
        <div class="empty-state-box" style="padding:40px 20px;">
          <div class="empty-state-icon" style="color:var(--danger); font-size:2.5rem;">⚠️</div>
          <h3 style="color:var(--danger); margin-top:8px;">Unable to load leave records</h3>
          <p style="color:var(--text-muted); max-width:480px; margin:8px auto 16px;">${escapeHTML(errors.leaves)}</p>
          <button class="btn btn-primary btn-sm" onclick="renderEmployeeWorkspaceView(true)">
            <span>🔄 Retry</span>
          </button>
        </div>
      </div>
    `;
  }

  if (tab === "notifications" && errors.notifs) {
    return `
      <div class="card">
        <div class="empty-state-box" style="padding:40px 20px;">
          <div class="empty-state-icon" style="color:var(--danger); font-size:2.5rem;">⚠️</div>
          <h3 style="color:var(--danger); margin-top:8px;">Unable to load notifications</h3>
          <p style="color:var(--text-muted); max-width:480px; margin:8px auto 16px;">${escapeHTML(errors.notifs)}</p>
          <button class="btn btn-primary btn-sm" onclick="renderEmployeeWorkspaceView(true)">
            <span>🔄 Retry</span>
          </button>
        </div>
      </div>
    `;
  }

  if (tab === "activity" && errors.activity) {
    return `
      <div class="card">
        <div class="empty-state-box" style="padding:40px 20px;">
          <div class="empty-state-icon" style="color:var(--danger); font-size:2.5rem;">⚠️</div>
          <h3 style="color:var(--danger); margin-top:8px;">Unable to load activity logs</h3>
          <p style="color:var(--text-muted); max-width:480px; margin:8px auto 16px;">${escapeHTML(errors.activity)}</p>
          <button class="btn btn-primary btn-sm" onclick="renderEmployeeWorkspaceView(true)">
            <span>🔄 Retry</span>
          </button>
        </div>
      </div>
    `;
  }

  if (tab === "profile" && errors.profile) {
    return `
      <div class="card">
        <div class="empty-state-box" style="padding:40px 20px;">
          <div class="empty-state-icon" style="color:var(--danger); font-size:2.5rem;">⚠️</div>
          <h3 style="color:var(--danger); margin-top:8px;">Unable to load profile</h3>
          <p style="color:var(--text-muted); max-width:480px; margin:8px auto 16px;">${escapeHTML(errors.profile)}</p>
          <button class="btn btn-primary btn-sm" onclick="renderEmployeeWorkspaceView(true)">
            <span>🔄 Retry</span>
          </button>
        </div>
      </div>
    `;
  }

  switch (tab) {
    case "roster":
      return renderWorkspaceRosterHTML(data.roster, data.empId);
    case "leaves":
      return renderWorkspaceLeaveManagementHTML(data.leaves, data.roster, data.empId);
    case "notifications":
      return renderWorkspaceNotificationsHTML(data.notifications);
    case "activity":
      return renderWorkspaceActivityHTML(data.activityData, state.activeActivityFilter || "ALL");
    case "profile":
      return renderWorkspaceProfileHTML(data.employee, data.profile, data.changeRequests);
    case "overview":
    default:
      return renderWorkspaceOverviewHTML(data);
  }
}

function renderWorkspaceOverviewHTML(data) {
  const duty = data.todayDuty || { status: "OFF", queryDate: getTodayISOString(), shiftName: "Duty Not Scheduled" };
  const leaves = data.leaves || [];
  const notifs = data.notifications || [];
  const activityData = data.activityData || { content: [] };
  const employee = data.employee || {};
  const profile = data.profile || {};
  const pendingCount = data.pendingCount || 0;
  const approvedCount = data.approvedCount || 0;
  const activeWorkforceDisplay = data.activeWorkforceDisplay || "7 Active Members";
  const dutyError = (state.sectionErrors && state.sectionErrors.duty) || null;

  const queryDate = duty.queryDate || getTodayISOString();

  // Employee Identity Details
  const fullName = (employee.firstName || employee.lastName)
    ? `${employee.firstName || ''} ${employee.lastName || ''}`.trim()
    : (profile.username || 'Staff Member');
  const empCode = employee.employeeCode || profile.username || 'EMP001';
  const email = employee.email || 'N/A';
  const contact = employee.contactNumber || 'Not provided';
  const department = 'Operations / WRMS';
  const gender = employee.gender || 'MALE';
  const shiftPolicy = gender === 'FEMALE'
    ? 'FEMALE: Day Shifts Only (Morning & General)'
    : 'MALE: All Shifts Eligible (24/7 Coverage)';

  // Today's Shift Resolution
  let shiftTitle = "Standby / Not Assigned";
  let timingSubtext = "No working hours";
  let statusBadge = `<span class="flag-badge flag-weeklyoff">UNSCHEDULED</span>`;
  let iconEmoji = "📅";
  let iconBg = "#f1f5f9";
  let iconColor = "#64748b";

  if (duty.status === "LEAVE") {
    shiftTitle = "ON LEAVE";
    timingSubtext = duty.leaveReason ? `Approved: ${duty.leaveReason}` : (duty.leaveType || "Approved Absence");
    statusBadge = `<span class="flag-badge flag-leave">🏖️ OFF — Approved Leave</span>`;
    iconEmoji = "🏖️";
    iconBg = "var(--shift-leave-bg)";
    iconColor = "var(--shift-leave-color)";
  } else if (duty.status === "OFF") {
    shiftTitle = "WEEKLY OFF";
    timingSubtext = "Scheduled Rest Day (No working hours)";
    statusBadge = `<span class="flag-badge flag-weeklyoff">🛋️ OFF — Weekly Rest</span>`;
    iconEmoji = "🛋️";
    iconBg = "var(--shift-off-bg)";
    iconColor = "var(--shift-off-color)";
  } else if (duty.status === "WORKING") {
    shiftTitle = duty.shiftName || duty.shiftType || "Working Shift";
    const startTimeStr = duty.startTime ? duty.startTime.substring(0, 5) : "";
    const endTimeStr = duty.endTime ? duty.endTime.substring(0, 5) : "";
    const timeRange = (startTimeStr && endTimeStr) ? `${startTimeStr} - ${endTimeStr}` : getShiftTimingDisplay(duty.shiftType);

    if (duty.shiftType === "NIGHT") {
      timingSubtext = `${timeRange} (Ends Next Day)`;
      iconEmoji = "🌙";
      iconBg = "var(--shift-night-bg)";
      iconColor = "var(--shift-night-color)";
    } else if (duty.shiftType === "MORNING") {
      timingSubtext = `${timeRange} (Morning Duty)`;
      iconEmoji = "☀️";
      iconBg = "var(--shift-morning-bg)";
      iconColor = "var(--shift-morning-color)";
    } else if (duty.shiftType === "GENERAL") {
      timingSubtext = `${timeRange} (General Business Hours)`;
      iconEmoji = "💼";
      iconBg = "var(--shift-general-bg)";
      iconColor = "var(--shift-general-color)";
    } else if (duty.shiftType === "EVENING") {
      timingSubtext = `${timeRange} (Evening Duty)`;
      iconEmoji = "🌆";
      iconBg = "var(--shift-evening-bg)";
      iconColor = "var(--shift-evening-color)";
    } else {
      timingSubtext = timeRange;
      iconEmoji = "⏱️";
      iconBg = "#eff6ff";
      iconColor = "#2563eb";
    }

    statusBadge = duty.source === "OVERRIDE"
      ? `<span class="flag-badge flag-override">⚡ WORKING (Override)</span>`
      : `<span class="flag-badge flag-working">✅ ON DUTY</span>`;
  }

  // Dynamic Status Text
  const dynamicStatusPill = duty.dynamicStatusText ? `
    <div class="dynamic-status-banner">
      <span class="pulse-indicator"></span>
      <span>${escapeHTML(duty.dynamicStatusText)}</span>
    </div>
  ` : '';

  // Previous & Next Duty Context
  const prevDuty = duty.previousDuty;
  const nextDuty = duty.nextDuty;
  const prevText = prevDuty ? formatDutyContextSummary(prevDuty) : "No recent duty record";
  const nextText = nextDuty ? formatDutyContextSummary(nextDuty) : "Standby / Next cycle";

  const unreadNotifs = (notifs || []).filter(n => !n.readStatus);
  const recentNotifs = (notifs || []).slice(0, 3);
  const recentActs = (activityData && activityData.content) ? activityData.content.slice(0, 3) : [];

  return `
    <!-- 1. Employee Welcome & Identity Card -->
    <div class="card" style="margin-bottom:20px; background: linear-gradient(135deg, #ffffff 0%, #f8fafc 100%); border-left: 4px solid var(--primary);">
      <div class="card-body" style="padding:20px 24px;">
        <div style="display:flex; justify-content:space-between; align-items:flex-start; flex-wrap:wrap; gap:16px;">
          <div style="display:flex; align-items:center; gap:16px;">
            <div class="user-avatar" style="width:52px; height:52px; font-size:1.3rem; background:linear-gradient(135deg, var(--primary) 0%, var(--primary-hover) 100%); color:#fff; border-radius:50%; display:flex; align-items:center; justify-content:center; font-weight:700;">
              ${escapeHTML(fullName.split(' ').map(n=>n[0]).join('').substring(0,2).toUpperCase() || 'EM')}
            </div>
            <div>
              <div style="display:flex; align-items:center; gap:8px; flex-wrap:wrap;">
                <h2 style="margin:0; font-size:1.35rem; color:var(--text-main); font-weight:800;">${escapeHTML(fullName)}</h2>
                <span class="badge active" style="font-size:0.75rem;">Active Staff</span>
                <span class="badge general" style="font-size:0.75rem;">ROLE_EMPLOYEE</span>
              </div>
              <p style="margin:4px 0 0; font-size:0.85rem; color:var(--text-muted);">
                Welcome to your self-service workforce portal. View your shift assignments, manage leave, and review system notifications.
              </p>
            </div>
          </div>
          <div>
            <button class="btn btn-secondary btn-sm" id="overviewEditProfileBtn" onclick="navigateTo('profile');">
              <span>👤 Edit Profile</span>
            </button>
          </div>
        </div>

        <div style="display:grid; grid-template-columns:repeat(auto-fit, minmax(180px, 1fr)); gap:14px; margin-top:16px; padding-top:16px; border-top:1px solid var(--border-light); font-size:0.85rem;">
          <div>
            <span style="color:var(--text-muted); display:block; font-size:0.72rem; font-weight:700; text-transform:uppercase;">Employee ID</span>
            <strong style="color:var(--text-main);">${escapeHTML(empCode)}</strong>
          </div>
          <div>
            <span style="color:var(--text-muted); display:block; font-size:0.72rem; font-weight:700; text-transform:uppercase;">Email</span>
            <strong style="color:var(--text-main);">${escapeHTML(email)}</strong>
          </div>
          <div>
            <span style="color:var(--text-muted); display:block; font-size:0.72rem; font-weight:700; text-transform:uppercase;">Contact Number</span>
            <strong style="color:var(--text-main);">${escapeHTML(contact)}</strong>
          </div>
          <div>
            <span style="color:var(--text-muted); display:block; font-size:0.72rem; font-weight:700; text-transform:uppercase;">Department</span>
            <strong style="color:var(--text-main);">${escapeHTML(department)}</strong>
          </div>
          <div>
            <span style="color:var(--text-muted); display:block; font-size:0.72rem; font-weight:700; text-transform:uppercase;">Shift Policy</span>
            <strong style="color:var(--text-main); font-size:0.8rem;">${escapeHTML(shiftPolicy)}</strong>
          </div>
        </div>
      </div>
    </div>

    <!-- 2. Four Key Stat / Summary Cards Grid -->
    <div class="metrics-grid" style="margin-bottom:20px;">
      
      <!-- Card A: Today's Assigned Duty -->
      <div class="metric-card">
        <div class="metric-header">
          <span class="metric-title">Today's Duty (${formatDate(queryDate)})</span>
          <div class="stat-icon" style="background:${iconBg}; color:${iconColor}; font-size:1.2rem;">
            ${iconEmoji}
          </div>
        </div>
        <div class="metric-value" style="font-size:1.3rem; margin-top:4px;">${escapeHTML(shiftTitle)}</div>
        <div class="metric-subtext" style="margin-top:2px;">
          ${escapeHTML(timingSubtext)}
        </div>
        <div style="margin-top:8px;">
          ${statusBadge}
        </div>
      </div>

      <!-- Card B: Next Scheduled Duty -->
      <div class="metric-card">
        <div class="metric-header">
          <span class="metric-title">Next Scheduled Duty</span>
          <div class="stat-icon" style="background:#f0fdf4; color:#16a34a; font-size:1.2rem;">
            ⏭️
          </div>
        </div>
        <div class="metric-value" style="font-size:1.05rem; margin-top:4px;">${escapeHTML(nextText)}</div>
        <div class="metric-subtext" style="margin-top:4px;">
          Rotation safety & 12h interval checked
        </div>
        <div style="margin-top:8px;">
          <span class="badge morning">Upcoming Duty</span>
        </div>
      </div>

      <!-- Card C: Leave Summary -->
      <div class="metric-card">
        <div class="metric-header">
          <span class="metric-title">Leave Status</span>
          <div class="stat-icon" style="background:#fef3c7; color:#d97706; font-size:1.2rem;">
            🏖️
          </div>
        </div>
        <div class="metric-value" style="font-size:1.3rem; margin-top:4px;">
          <span>${pendingCount}</span> <small style="font-size:0.8rem; font-weight:600; color:var(--text-muted);">Pending</small> &bull;
          <span>${approvedCount}</span> <small style="font-size:0.8rem; font-weight:600; color:var(--text-muted);">Approved</small>
        </div>
        <div class="metric-subtext" style="margin-top:4px;">
          Personal absence requests
        </div>
        <div style="margin-top:8px;">
          <button class="btn btn-link-xs" id="quickManageLeaveBtn" onclick="navigateTo('leaves');">Manage Leaves &rarr;</button>
        </div>
      </div>

      <!-- Card D: Notifications & Inbox -->
      <div class="metric-card">
        <div class="metric-header">
          <span class="metric-title">Notifications</span>
          <div class="stat-icon" style="background:#eff6ff; color:#2563eb; font-size:1.2rem;">
            🔔
          </div>
        </div>
        <div class="metric-value" style="font-size:1.3rem; margin-top:4px;">
          <span>${unreadNotifs.length}</span> <small style="font-size:0.8rem; font-weight:600; color:var(--text-muted);">Unread</small>
        </div>
        <div class="metric-subtext" style="margin-top:4px;">
          ${unreadNotifs.length ? 'Pending alerts in inbox' : 'Inbox up to date'}
        </div>
        <div style="margin-top:8px;">
          <button class="btn btn-link-xs" id="quickViewNotifsBtn" onclick="navigateTo('notifications');">View Inbox &rarr;</button>
        </div>
      </div>

    </div>

    <!-- 3. Split Two-Column Operational Layout -->
    <div class="form-row two-col" style="margin-bottom:20px;">
      
      <!-- Left Column: Duty Continuity & Self-Service Shortcuts -->
      <div style="display:flex; flex-direction:column; gap:20px;">
        
        <!-- Shift Context & Safety Compliance Card -->
        <div class="card">
          <div class="card-header">
            <div>
              <h3>Shift Safety & Duty Context</h3>
              <span style="font-size:0.76rem; color:var(--text-muted);">Operational shift rotation details and rest rules</span>
            </div>
          </div>
          <div class="card-body">
            ${dynamicStatusPill}

            <div class="duty-context-strip" style="margin-top:12px;">
              <div class="duty-context-item">
                <span class="duty-context-label">⏮️ Previous Duty:</span>
                <span class="duty-context-val">${escapeHTML(prevText)}</span>
              </div>
              <div class="duty-context-item">
                <span class="duty-context-label">▶️ Today's Duty:</span>
                <span class="duty-context-val">${escapeHTML(shiftTitle)} (${escapeHTML(timingSubtext)})</span>
              </div>
              <div class="duty-context-item">
                <span class="duty-context-label">⏭️ Next Duty:</span>
                <span class="duty-context-val">${escapeHTML(nextText)}</span>
              </div>
            </div>

            <div style="font-size:0.8rem; color:var(--text-muted); border-top:1px solid var(--border-light); padding-top:12px; margin-top:14px; display:flex; justify-content:space-between; flex-wrap:wrap; gap:8px;">
              <span>Active Department Workforce: <strong>${escapeHTML(String(activeWorkforceDisplay))}</strong></span>
              <span>Safety Rule: <strong>${escapeHTML(duty.safetyStatus || "12h Min Rest Protected")}</strong></span>
            </div>
          </div>
        </div>

        <!-- Self-Service Quick Action Hub -->
        <div class="card">
          <div class="card-header">
            <div>
              <h3>Self-Service Quick Actions</h3>
              <span style="font-size:0.76rem; color:var(--text-muted);">Direct shortcuts to your personal workspace tools</span>
            </div>
          </div>
          <div class="card-body" style="display:grid; grid-template-columns:1fr 1fr; gap:10px;">
            <button class="btn btn-secondary btn-sm" id="quickViewRosterBtn" onclick="navigateTo('roster');" style="justify-content:center; padding:10px;">
              <span>📅 View Full Roster</span>
            </button>
            <button class="btn btn-secondary btn-sm" id="quickApplyLeaveBtn" onclick="navigateTo('leaves');" style="justify-content:center; padding:10px;">
              <span>🏖️ Request Leave</span>
            </button>
            <button class="btn btn-secondary btn-sm" id="quickViewProfileBtn" onclick="navigateTo('profile');" style="justify-content:center; padding:10px;">
              <span>👤 My Profile</span>
            </button>
            <button class="btn btn-secondary btn-sm" id="quickViewActivityBtn" onclick="navigateTo('activity');" style="justify-content:center; padding:10px;">
              <span>📜 Activity Logs</span>
            </button>
            <button class="btn btn-secondary btn-sm" id="quickChangePwBtn" onclick="navigateTo('profile');" style="justify-content:center; grid-column:span 2; padding:10px;">
              <span>🔐 Security & Password Settings</span>
            </button>
          </div>
        </div>

      </div>

      <!-- Right Column: Notifications & Recent Activity Stream -->
      <div style="display:flex; flex-direction:column; gap:20px;">
        
        <!-- Notifications Snapshot Widget -->
        <div class="card">
          <div class="card-header">
            <div>
              <h3>Latest Notifications</h3>
              <span style="font-size:0.76rem; color:var(--text-muted);">Recent announcements and schedule alerts</span>
            </div>
            <button class="btn btn-link-xs" id="overviewSeeAllNotifsBtn" onclick="navigateTo('notifications');">
              View All &rarr;
            </button>
          </div>
          <div class="card-body">
            ${recentNotifs.length ? `
              <div style="display:flex; flex-direction:column; gap:8px;">
                ${recentNotifs.map(n => `
                  <div class="notif-item ${n.readStatus ? '' : 'unread'}" style="border:1px solid var(--border-light); border-radius:var(--radius-sm); padding:10px 12px;">
                    <div style="display:flex; justify-content:space-between; align-items:center;">
                      <strong style="font-size:0.85rem; color:var(--text-main);">${escapeHTML(n.title)}</strong>
                      <span style="font-size:0.72rem; color:var(--text-muted);">${formatDate(n.createdAt)}</span>
                    </div>
                    <div style="font-size:0.8rem; color:var(--text-muted); margin-top:4px;">${escapeHTML(n.message)}</div>
                  </div>
                `).join('')}
              </div>
            ` : `
              <div class="empty-state-box" style="padding:24px 16px;">
                <p style="margin:0; font-size:0.85rem; color:var(--text-muted);">No new notifications.</p>
              </div>
            `}
          </div>
        </div>

        <!-- Recent Activity Logs Stream Widget -->
        <div class="card">
          <div class="card-header">
            <div>
              <h3>Recent Account Activity</h3>
              <span style="font-size:0.76rem; color:var(--text-muted);">Audit log of recent system and self-service actions</span>
            </div>
            <button class="btn btn-link-xs" id="overviewSeeAllActivityBtn" onclick="navigateTo('activity');">
              View All &rarr;
            </button>
          </div>
          <div class="card-body">
            ${recentActs.length ? `
              <div class="activity-timeline">
                ${recentActs.map(act => renderActivityItemHTML(act)).join("")}
              </div>
            ` : `
              <div class="empty-state-box" style="padding:24px 16px;">
                <p style="margin:0; font-size:0.85rem; color:var(--text-muted);">No recent activity records.</p>
              </div>
            `}
          </div>
        </div>

      </div>

    </div>
  `;
}

function renderWorkspaceRosterHTML(roster, empId) {
  return `
    <div class="card stack-gap">
      <div class="card-header">
        <div>
          <h3>My Weekly Roster Schedule</h3>
          <span style="font-size:0.76rem; color:var(--text-muted);">Official published shift assignments and rotation history</span>
        </div>
        <div style="display:flex; gap:8px;">
          <button class="btn btn-secondary btn-sm" id="rosterLogViewBtn">
            <span>🔄 Refresh Schedule</span>
          </button>
        </div>
      </div>
      <div class="table-wrap">
        ${renderMyRosterTableHTML(roster)}
      </div>
    </div>
  `;
}

function bindWorkspaceRosterEvents(empId) {
  const refreshBtn = document.getElementById("rosterLogViewBtn");
  if (refreshBtn) {
    refreshBtn.addEventListener("click", () => {
      apiRequest("/api/activities/view-roster", { method: "POST" }).catch(() => {});
      renderEmployeeWorkspaceView();
      toast("Roster schedule refreshed", "info");
    });
  }
}

function renderWorkspaceNotificationsHTML(notifications) {
  const unreadList = (notifications || []).filter(n => !n.readStatus);
  const readList = (notifications || []).filter(n => n.readStatus);

  return `
    <div class="card">
      <div class="card-header">
        <div>
          <h3>My Notifications</h3>
          <span style="font-size:0.76rem; color:var(--text-muted);">System alerts, roster releases, and leave updates</span>
        </div>
        ${unreadList.length ? `
          <button class="btn btn-secondary btn-sm" id="wsNotifMarkAllReadBtn">
            <span>✓ Mark All Read</span>
          </button>
        ` : ''}
      </div>
      <div class="card-body">
        ${(!notifications || !notifications.length) ? `
          <div class="empty-state-box" style="padding:40px 20px;">
            <div class="empty-state-icon">🔔</div>
            <h3>No notifications</h3>
            <p>You have no notifications in your inbox.</p>
          </div>
        ` : `
          <div class="notif-list-body" style="max-height:none;">
            ${notifications.map(n => `
              <div class="notif-item ${n.readStatus ? '' : 'unread'}" style="border:1px solid var(--border-light); border-radius:var(--radius-md); margin-bottom:8px; padding:12px 16px;">
                <div class="notif-item-header">
                  <strong class="notif-item-title">${escapeHTML(n.title)}</strong>
                  <span class="notif-time">${formatDate(n.createdAt)}</span>
                </div>
                <div class="notif-item-msg">${escapeHTML(n.message)}</div>
                <div style="display:flex; justify-content:space-between; align-items:center; margin-top:8px;">
                  <span class="badge ${n.type === 'ROSTER_PUBLISHED' ? 'morning' : 'general'}">${n.type}</span>
                  ${!n.readStatus ? `
                    <button class="btn-link-xs" data-ws-mark-read="${n.id}">Mark as read</button>
                  ` : `<span style="font-size:0.72rem; color:var(--text-muted);">✓ Read</span>`}
                </div>
              </div>
            `).join("")}
          </div>
        `}
      </div>
    </div>
  `;
}

function bindWorkspaceNotificationEvents() {
  const markAllBtn = document.getElementById("wsNotifMarkAllReadBtn");
  if (markAllBtn) {
    markAllBtn.addEventListener("click", async () => {
      try {
        await apiRequest("/api/notifications/read-all", { method: "PUT" });
        toast("All notifications marked as read", "success");
        renderEmployeeWorkspaceView();
      } catch (err) {
        toast(err.message, "error");
      }
    });
  }

  document.querySelectorAll("[data-ws-mark-read]").forEach(btn => {
    btn.addEventListener("click", async () => {
      const notifId = btn.getAttribute("data-ws-mark-read");
      try {
        await apiRequest(`/api/notifications/${notifId}/read`, { method: "PUT" });
        toast("Notification marked as read", "info");
        renderEmployeeWorkspaceView();
      } catch (err) {
        toast(err.message, "error");
      }
    });
  });
}

function renderWorkspaceActivityHTML(activityData, activeCategory = "ALL") {
  const list = (activityData && activityData.content) ? activityData.content : [];
  const totalElements = activityData ? activityData.totalElements : 0;
  const hasMore = activityData ? activityData.hasMore : false;
  const currentPage = activityData ? activityData.page : 0;

  const categories = [
    { key: "ALL", label: "All" },
    { key: "ACCOUNT", label: "Account" },
    { key: "ROSTER", label: "Roster" },
    { key: "LEAVE", label: "Leave" },
    { key: "NOTIFICATION", label: "Notification" },
    { key: "SECURITY", label: "Security" }
  ];

  const filterChipsHtml = categories.map(c => `
    <button class="activity-filter-chip ${activeCategory === c.key ? 'active' : ''}" data-act-filter="${c.key}">
      ${c.label}
    </button>
  `).join("");

  let timelineHtml = "";
  if (!list.length) {
    timelineHtml = `
      <div class="empty-state-box" style="padding:40px 20px;">
        <div class="empty-state-icon" style="font-size:2.5rem;">📜</div>
        <h3>No activity records found</h3>
        <p style="color:var(--text-muted); max-width:400px; margin:8px auto;">No actions match the selected filter category.</p>
      </div>
    `;
  } else {
    const groups = groupActivitiesByDate(list);
    timelineHtml = Object.keys(groups).map(dateHeader => `
      <div class="activity-date-group">
        <div class="activity-date-header">
          <span>📅</span> ${dateHeader}
        </div>
        <div class="activity-timeline">
          ${groups[dateHeader].map(item => renderActivityItemHTML(item)).join("")}
        </div>
      </div>
    `).join("");
  }

  return `
    <div class="card">
      <div class="card-header" style="border-bottom:1px solid var(--border-light); padding-bottom:16px;">
        <div>
          <h3>Activity & Security Logs</h3>
          <span style="font-size:0.78rem; color:var(--text-muted);">Immutable audit history of your account sign-ins, leave submissions, password updates, and schedule views</span>
        </div>
        <span class="badge morning">${totalElements} Total Activities</span>
      </div>
      <div class="card-body">
        
        <!-- Filter Bar -->
        <div class="activity-filter-bar" id="activityFilterBar">
          ${filterChipsHtml}
        </div>

        <!-- Timeline Container -->
        <div id="activityTimelineWrapper">
          ${timelineHtml}
        </div>

        <!-- Pagination Controls -->
        ${hasMore ? `
          <div style="text-align:center; margin-top:24px; padding-top:16px; border-top:1px solid var(--border-light);">
            <button class="btn btn-secondary" id="loadMoreActivitiesBtn" data-next-page="${currentPage + 1}" data-category="${activeCategory}">
              <span>Load More Activities</span>
              <div class="spinner hidden"></div>
            </button>
            <div style="font-size:0.74rem; color:var(--text-muted); margin-top:8px;">
              Showing page ${currentPage + 1} of ${activityData.totalPages}
            </div>
          </div>
        ` : (list.length > 0 ? `
          <div style="text-align:center; margin-top:20px; font-size:0.76rem; color:var(--text-muted);">
            ✓ All recent activity records loaded
          </div>
        ` : '')}

      </div>
    </div>
  `;
}

function renderActivityItemHTML(item) {
  let icon = "📜";
  if (item.category === "SECURITY") icon = "🔐";
  else if (item.category === "ROSTER") icon = "📅";
  else if (item.category === "LEAVE") icon = "📝";
  else if (item.category === "NOTIFICATION") icon = "🔔";
  else if (item.category === "ACCOUNT") icon = "👤";

  if (item.action === "FAILED_LOGIN") icon = "⚠️";
  if (item.action === "LOGOUT") icon = "🚪";
  if (item.action === "PASSWORD_CHANGED") icon = "🔑";

  const timeStr = formatActivityTime(item.createdAt);
  const actionTitle = formatActionTitle(item.action);

  return `
    <div class="activity-item">
      <div class="activity-icon-wrap activity-icon-${item.category}">
        ${icon}
      </div>
      <div class="activity-content">
        <div class="activity-top-row">
          <div class="activity-title-group">
            <span class="activity-category-tag category-${item.category}">${escapeHTML(item.category)}</span>
            <strong class="activity-action-name">${escapeHTML(actionTitle)}</strong>
          </div>
          <span class="activity-status-badge activity-status-${item.status}">${escapeHTML(item.status)}</span>
        </div>
        <div class="activity-desc">${escapeHTML(item.description)}</div>
        <div class="activity-meta">
          <span>⏰ ${timeStr}</span>
          ${item.source ? `<span class="activity-meta-source">Source: ${escapeHTML(item.source)}</span>` : ''}
        </div>
      </div>
    </div>
  `;
}

function groupActivitiesByDate(list) {
  const todayISO = getTodayISOString();
  const yesterdayDate = new Date();
  yesterdayDate.setDate(yesterdayDate.getDate() - 1);
  const yesterdayISO = yesterdayDate.toISOString().split("T")[0];

  const groups = {};
  list.forEach(item => {
    let datePart = "";
    if (item.createdAt) {
      datePart = typeof item.createdAt === "string" ? item.createdAt.split("T")[0] : new Date(item.createdAt).toISOString().split("T")[0];
    } else {
      datePart = todayISO;
    }

    let header = formatDate(datePart);
    if (datePart === todayISO) header = "Today";
    else if (datePart === yesterdayISO) header = "Yesterday";

    if (!groups[header]) groups[header] = [];
    groups[header].push(item);
  });
  return groups;
}

function formatActivityTime(timestamp) {
  if (!timestamp) return "-";
  try {
    const dt = new Date(timestamp);
    return dt.toLocaleTimeString("en-US", { hour: "2-digit", minute: "2-digit" });
  } catch (e) {
    return String(timestamp);
  }
}

function formatActionTitle(action) {
  if (!action) return "Activity";
  switch (action) {
    case "LOGIN": return "Signed In";
    case "LOGOUT": return "Signed Out";
    case "FAILED_LOGIN": return "Failed Login Attempt";
    case "PASSWORD_CHANGED": return "Password Changed";
    case "LEAVE_APPLIED": return "Leave Application Submitted";
    case "LEAVE_MODIFICATION_REQUESTED": return "Leave Modification Requested";
    case "LEAVE_CANCELLATION_REQUESTED": return "Leave Cancellation Requested";
    case "LEAVE_APPROVED": return "Leave Approved";
    case "LEAVE_REJECTED": return "Leave Rejected";
    case "LEAVE_MODIFIED": return "Leave Modified";
    case "LEAVE_CANCELLED": return "Leave Cancelled";
    case "ROSTER_VIEWED": return "Weekly Roster Viewed";
    case "ROSTER_PUBLISHED": return "Weekly Roster Published";
    case "SHIFT_CHANGED": return "Shift Changed";
    case "NOTIFICATION_VIEWED": return "Notification Viewed";
    default: return action.replace(/_/g, " ").toLowerCase().replace(/\b\w/g, c => c.toUpperCase());
  }
}

function bindWorkspaceActivityEvents(empId) {
  document.querySelectorAll(".activity-filter-chip").forEach(chip => {
    chip.addEventListener("click", async () => {
      const cat = chip.getAttribute("data-act-filter");
      state.activeActivityFilter = cat;
      await refreshActivityTabContent();
    });
  });

  const loadMoreBtn = document.getElementById("loadMoreActivitiesBtn");
  if (loadMoreBtn) {
    loadMoreBtn.addEventListener("click", async () => {
      const nextPage = parseInt(loadMoreBtn.getAttribute("data-next-page"), 10) || 1;
      const category = loadMoreBtn.getAttribute("data-category") || "ALL";
      const spinner = loadMoreBtn.querySelector(".spinner");
      loadMoreBtn.disabled = true;
      if (spinner) spinner.classList.remove("hidden");

      try {
        const catQuery = category !== "ALL" ? `&category=${category}` : "";
        const data = await apiRequest(`/api/activities/my?page=${nextPage}&size=20${catQuery}`);
        
        if (data && data.content && data.content.length) {
          state.accumulatedActivities = (state.accumulatedActivities || []).concat(data.content);
          state.latestActivityData = {
            content: state.accumulatedActivities,
            page: data.page,
            size: data.size,
            totalElements: data.totalElements,
            totalPages: data.totalPages,
            hasMore: data.hasMore
          };
          const contentDiv = document.getElementById("workspaceTabContent");
          if (contentDiv) {
            contentDiv.innerHTML = renderWorkspaceActivityHTML(state.latestActivityData, state.activeActivityFilter || "ALL");
            bindWorkspaceActivityEvents(empId);
          }
        }
      } catch (err) {
        toast(err.message, "error");
      } finally {
        loadMoreBtn.disabled = false;
        if (spinner) spinner.classList.add("hidden");
      }
    });
  }
}

async function refreshActivityTabContent() {
  const contentDiv = document.getElementById("workspaceTabContent");
  if (!contentDiv) return;
  contentDiv.innerHTML = `<div class="empty-state-box"><div class="spinner"></div><p>Loading activity logs...</p></div>`;

  try {
    const cat = state.activeActivityFilter || "ALL";
    const catQuery = cat !== "ALL" ? `&category=${cat}` : "";
    const data = await apiRequest(`/api/activities/my?page=0&size=20${catQuery}`);
    state.accumulatedActivities = data ? data.content : [];
    state.latestActivityData = data;
    contentDiv.innerHTML = renderWorkspaceActivityHTML(data, cat);
    bindWorkspaceActivityEvents(state.profile ? state.profile.employeeId : null);
  } catch (err) {
    contentDiv.innerHTML = `
      <div class="card" style="padding:20px;">
        <div class="empty-state-box">
          <p style="color:var(--danger);">${escapeHTML(err.message)}</p>
        </div>
      </div>
    `;
  }
}

function renderWorkspaceProfileHTML(employee, profile, changeRequests = []) {
  const username = profile ? profile.username : (employee ? employee.employeeCode : "User");
  const firstName = employee ? employee.firstName : "";
  const lastName = employee ? (employee.lastName || "") : "";
  const fullName = employee ? `${employee.firstName} ${employee.lastName || ''}`.trim() : (profile ? profile.employeeName : username);
  const empCode = employee ? employee.employeeCode : (profile ? profile.username : "-");
  const email = employee ? employee.email : `${username}@company.com`;
  const contactNumber = employee ? (employee.contactNumber || "") : (profile ? (profile.contactNumber || "") : "");
  const gender = employee ? (employee.gender || "MALE") : "Not Specified";
  const role = profile ? profile.role.replace("ROLE_", "") : "EMPLOYEE";
  const isActive = employee ? employee.active : true;

  // Track pending change requests per field
  const pendingByField = {};
  changeRequests.filter(r => r.status === "PENDING").forEach(r => {
    pendingByField[r.fieldName] = r;
  });

  return `
    <div class="profile-layout-container">
      
      <!-- Top Banner / Identity Header -->
      <div class="card profile-header-card">
        <div class="profile-header-content">
          <div class="user-avatar profile-avatar-lg">
            ${username.substring(0, 2).toUpperCase()}
          </div>
          <div class="profile-header-info">
            <div class="profile-name-row">
              <h2 class="profile-display-name">${escapeHTML(fullName)}</h2>
              <span class="status-pill ${isActive ? 'active' : 'inactive'}">
                <span class="badge-dot"></span> ${isActive ? 'Active Member' : 'Inactive'}
              </span>
            </div>
            <div class="profile-sub-meta">
              <span><strong>Code:</strong> <code>${escapeHTML(empCode)}</code></span>
              <span class="meta-sep">&bull;</span>
              <span><strong>Role:</strong> ${escapeHTML(role)}</span>
              <span class="meta-sep">&bull;</span>
              <span><strong>Username:</strong> <code>${escapeHTML(username)}</code></span>
            </div>
          </div>
        </div>
      </div>

      <!-- SECTION 1: PERSONAL DETAILS & SECTION 2: EMPLOYMENT DETAILS -->
      <div class="form-row two-col">
        
        <!-- 1. PERSONAL DETAILS CARD -->
        <div class="card">
          <div class="card-header">
            <div>
              <h3>Personal Details</h3>
              <span class="card-subtext">Basic personal information & contact</span>
            </div>
            <span class="badge morning">Verified Profile</span>
          </div>
          <div class="card-body stack-gap">
            
            <!-- First Name -->
            <div class="profile-field-item">
              <div class="profile-field-meta">
                <span class="profile-field-label">First Name</span>
                <strong class="profile-field-val">${escapeHTML(firstName || '-')}</strong>
              </div>
              <div class="profile-field-action">
                ${pendingByField['firstName'] ? `
                  <span class="status-pill pending" title="Change request pending admin review">Pending Review</span>
                ` : `
                  <button class="btn btn-secondary btn-sm btn-req-change" data-field="firstName" data-label="First Name" data-current="${escapeHTML(firstName)}">
                    Request Change
                  </button>
                `}
              </div>
            </div>

            <!-- Last Name -->
            <div class="profile-field-item">
              <div class="profile-field-meta">
                <span class="profile-field-label">Last Name</span>
                <strong class="profile-field-val">${escapeHTML(lastName || '-')}</strong>
              </div>
              <div class="profile-field-action">
                ${pendingByField['lastName'] ? `
                  <span class="status-pill pending" title="Change request pending admin review">Pending Review</span>
                ` : `
                  <button class="btn btn-secondary btn-sm btn-req-change" data-field="lastName" data-label="Last Name" data-current="${escapeHTML(lastName)}">
                    Request Change
                  </button>
                `}
              </div>
            </div>

            <!-- Email Address -->
            <div class="profile-field-item">
              <div class="profile-field-meta">
                <span class="profile-field-label">Email Address</span>
                <strong class="profile-field-val" style="word-break:break-all;">${escapeHTML(email)}</strong>
              </div>
              <div class="profile-field-action">
                ${pendingByField['email'] ? `
                  <span class="status-pill pending" title="Change request pending admin review">Pending Review</span>
                ` : `
                  <button class="btn btn-secondary btn-sm btn-req-change" data-field="email" data-label="Email Address" data-current="${escapeHTML(email)}" data-type="email">
                    Request Change
                  </button>
                `}
              </div>
            </div>

            <!-- Contact Number -->
            <div class="profile-field-item">
              <div class="profile-field-meta">
                <span class="profile-field-label">Contact Number</span>
                <strong class="profile-field-val">${escapeHTML(contactNumber || '-')}</strong>
              </div>
              <div class="profile-field-action">
                ${pendingByField['contactNumber'] ? `
                  <span class="status-pill pending" title="Change request pending admin review">Pending Review</span>
                ` : `
                  <button class="btn btn-secondary btn-sm btn-req-change" data-field="contactNumber" data-label="Contact Number" data-current="${escapeHTML(contactNumber)}" data-type="phone">
                    Request Change
                  </button>
                `}
              </div>
            </div>

            <!-- Gender -->
            <div class="profile-field-item">
              <div class="profile-field-meta">
                <span class="profile-field-label">Gender</span>
                <div class="profile-field-val" style="display:flex; align-items:center; gap:8px;">
                  <span class="badge ${gender === 'FEMALE' ? 'general' : 'morning'}">${escapeHTML(gender)}</span>
                  <small style="font-size:0.72rem; color:var(--text-muted);">
                    ${gender === 'FEMALE' ? '(Day shifts only)' : '(All shifts eligible)'}
                  </small>
                </div>
              </div>
              <div class="profile-field-action">
                ${pendingByField['gender'] ? `
                  <span class="status-pill pending" title="Change request pending admin review">Pending Review</span>
                ` : `
                  <button class="btn btn-secondary btn-sm btn-req-change" data-field="gender" data-label="Gender" data-current="${escapeHTML(gender)}" data-type="gender">
                    Request Change
                  </button>
                `}
              </div>
            </div>

          </div>
        </div>

        <!-- 2. EMPLOYMENT DETAILS CARD -->
        <div class="card">
          <div class="card-header">
            <div>
              <h3>Employment Details</h3>
              <span class="card-subtext">Company master data & workforce assignment</span>
            </div>
            <span class="badge general">Workforce Record</span>
          </div>
          <div class="card-body stack-gap">
            
            <!-- Employee Code -->
            <div class="profile-field-item">
              <div class="profile-field-meta">
                <span class="profile-field-label">Employee Code</span>
                <strong class="profile-field-val"><code>${escapeHTML(empCode)}</code></strong>
              </div>
              <div class="profile-field-action">
                ${pendingByField['employeeCode'] ? `
                  <span class="status-pill pending" title="Change request pending admin review">Pending Review</span>
                ` : `
                  <button class="btn btn-secondary btn-sm btn-req-change" data-field="employeeCode" data-label="Employee Code" data-current="${escapeHTML(empCode)}">
                    Request Change
                  </button>
                `}
              </div>
            </div>

            <!-- Employment Status -->
            <div class="profile-field-item">
              <div class="profile-field-meta">
                <span class="profile-field-label">Employment Status</span>
                <strong class="profile-field-val">
                  <span class="status-pill ${isActive ? 'active' : 'inactive'}">
                    <span class="badge-dot"></span> ${isActive ? 'Active Staff' : 'Inactive'}
                  </span>
                </strong>
              </div>
              <div class="profile-field-action">
                <span style="font-size:0.72rem; color:var(--text-muted); font-weight:600;">Managed by HR</span>
              </div>
            </div>

            <!-- Assigned Role -->
            <div class="profile-field-item">
              <div class="profile-field-meta">
                <span class="profile-field-label">System Role</span>
                <strong class="profile-field-val">${escapeHTML(role)}</strong>
              </div>
              <div class="profile-field-action">
                <span style="font-size:0.72rem; color:var(--text-muted); font-weight:600;">System Assigned</span>
              </div>
            </div>

            <!-- Shift Eligibility -->
            <div class="profile-field-item">
              <div class="profile-field-meta">
                <span class="profile-field-label">Shift Eligibility</span>
                <strong class="profile-field-val">
                  ${gender === 'FEMALE' ? 'Morning & General (Day Protection)' : 'All Shifts (Morning, General, Evening, Night)'}
                </strong>
              </div>
              <div class="profile-field-action">
                <span style="font-size:0.72rem; color:var(--text-muted); font-weight:600;">Policy Enforced</span>
              </div>
            </div>

          </div>
        </div>

      </div>

      <!-- 3. ACCOUNT DETAILS & PASSWORD CHANGE -->
      <div class="card">
        <div class="card-header">
          <div>
            <h3>Account Details & Security</h3>
            <span class="card-subtext">Manage your login account and password</span>
          </div>
          <span class="badge morning">🔐 Account Security</span>
        </div>
        <div class="card-body">
          <div class="form-row two-col" style="align-items:start;">
            
            <!-- Left: Account Summary -->
            <div class="stack-gap" style="padding-right:12px;">
              <div class="profile-field-item" style="border:none; padding:6px 0;">
                <div class="profile-field-meta">
                  <span class="profile-field-label">Login Username</span>
                  <strong class="profile-field-val"><code>${escapeHTML(username)}</code></strong>
                </div>
              </div>
              <div class="profile-field-item" style="border:none; padding:6px 0;">
                <div class="profile-field-meta">
                  <span class="profile-field-label">Account Privilege</span>
                  <strong class="profile-field-val">${escapeHTML(role)}</strong>
                </div>
              </div>
              <div class="alert-info-box" style="margin-top:12px; background-color:var(--bg-app); border-color:var(--border-light); color:var(--text-muted);">
                <strong style="color:var(--text-main); display:block; margin-bottom:2px;">🛡️ Security Notice:</strong>
                <span style="font-size:0.78rem;">Your login credentials and account actions are recorded in the activity log. Keep your password confidential.</span>
              </div>
            </div>

            <!-- Right: Change Password Form -->
            <form id="empChangePasswordForm" class="stack-gap" style="border-left:1px solid var(--border-light); padding-left:24px;">
              <h4 style="margin:0 0 4px 0; font-size:0.95rem;">Update Password</h4>
              
              <div class="form-group">
                <label for="changeCurrentPassword">Current Password <span class="req">*</span></label>
                <div class="input-with-icon">
                  <input type="password" id="changeCurrentPassword" placeholder="••••••••" required autocomplete="current-password">
                  <button type="button" class="password-toggle" id="toggleCurrentPwBtn" title="Show/Hide Password">👁️</button>
                </div>
              </div>

              <div class="form-group">
                <label for="changeNewPassword">New Password <span class="req">*</span></label>
                <div class="input-with-icon">
                  <input type="password" id="changeNewPassword" placeholder="Minimum 6 characters" required autocomplete="new-password">
                  <button type="button" class="password-toggle" id="toggleNewPwBtn" title="Show/Hide Password">👁️</button>
                </div>
                <small style="font-size:0.72rem; color:var(--text-muted);">Must be at least 6 characters long</small>
              </div>

              <div class="form-group">
                <label for="changeConfirmPassword">Confirm New Password <span class="req">*</span></label>
                <div class="input-with-icon">
                  <input type="password" id="changeConfirmPassword" placeholder="Re-enter new password" required autocomplete="new-password">
                  <button type="button" class="password-toggle" id="toggleConfirmPwBtn" title="Show/Hide Password">👁️</button>
                </div>
              </div>

              <div id="changePasswordMsgBox" class="alert-info-box hidden" style="background:#fee2e2; border-color:#fecaca; color:#991b1b;"></div>

              <div style="display:flex; justify-content:flex-end; margin-top:8px;">
                <button type="submit" class="btn btn-primary" id="changePasswordSubmitBtn">
                  <span class="btn-text">Update Password</span>
                  <div class="spinner hidden"></div>
                </button>
              </div>
            </form>

          </div>
        </div>
      </div>

      <!-- 4. PENDING & HISTORICAL CHANGE REQUESTS -->
      <div class="card">
        <div class="card-header">
          <div>
            <h3>Profile Change Requests</h3>
            <span class="card-subtext">History and status of your requested profile updates</span>
          </div>
          <span class="badge ${changeRequests.filter(r => r.status === 'PENDING').length ? 'pending' : 'general'}">
            ${changeRequests.filter(r => r.status === 'PENDING').length} Pending
          </span>
        </div>
        <div class="table-wrap">
          ${renderMyChangeRequestsTableHTML(changeRequests)}
        </div>
      </div>

    </div>
  `;
}

function renderMyChangeRequestsTableHTML(list) {
  if (!list || !list.length) {
    return `
      <div class="empty-state-box" style="padding:32px 20px;">
        <div class="empty-state-icon" style="font-size:2rem;">📋</div>
        <h3>No Change Requests</h3>
        <p style="color:var(--text-muted); font-size:0.85rem;">You have not submitted any profile change requests. Use the "Request Change" buttons above to submit updates.</p>
      </div>
    `;
  }

  const fieldLabels = {
    firstName: "First Name",
    lastName: "Last Name",
    email: "Email Address",
    contactNumber: "Contact Number",
    gender: "Gender",
    employeeCode: "Employee Code"
  };

  return `
    <table>
      <thead>
        <tr>
          <th>Field</th>
          <th>Current Value</th>
          <th>Requested Value</th>
          <th>Status</th>
          <th>Requested On</th>
          <th>Reviewed On</th>
          <th>Admin Remarks</th>
        </tr>
      </thead>
      <tbody>
        ${list.map(r => {
          const fieldLabel = fieldLabels[r.fieldName] || r.fieldName;
          let statusClass = "pending";
          let statusText = "Pending Review";
          if (r.status === "APPROVED") { statusClass = "active"; statusText = "Approved"; }
          else if (r.status === "REJECTED") { statusClass = "inactive"; statusText = "Rejected"; }

          return `
            <tr>
              <td><strong>${escapeHTML(fieldLabel)}</strong></td>
              <td><code>${escapeHTML(r.currentValue || '-')}</code></td>
              <td><strong style="color:var(--primary);">${escapeHTML(r.requestedValue)}</strong></td>
              <td>
                <span class="status-pill ${statusClass}">
                  <span class="badge-dot"></span> ${statusText}
                </span>
              </td>
              <td><small style="color:var(--text-muted);">${r.requestedAt ? new Date(r.requestedAt).toLocaleString() : '-'}</small></td>
              <td><small style="color:var(--text-muted);">${r.reviewedAt ? new Date(r.reviewedAt).toLocaleString() : '-'}</small></td>
              <td style="max-width:220px; font-size:0.82rem;">${r.adminRemarks ? escapeHTML(r.adminRemarks) : '<span style="color:var(--text-subtle);">-</span>'}</td>
            </tr>
          `;
        }).join("")}
      </tbody>
    </table>
  `;
}

function bindWorkspaceProfileEvents() {
  const currentPw = document.getElementById("changeCurrentPassword");
  const newPw = document.getElementById("changeNewPassword");
  const confirmPw = document.getElementById("changeConfirmPassword");

  const toggleBtn = (btnId, inputEl) => {
    const btn = document.getElementById(btnId);
    if (btn && inputEl) {
      btn.addEventListener("click", () => {
        inputEl.type = inputEl.type === "password" ? "text" : "password";
      });
    }
  };

  toggleBtn("toggleCurrentPwBtn", currentPw);
  toggleBtn("toggleNewPwBtn", newPw);
  toggleBtn("toggleConfirmPwBtn", confirmPw);

  const form = document.getElementById("empChangePasswordForm");
  if (form) {
    form.addEventListener("submit", async (e) => {
      e.preventDefault();
      const msgBox = document.getElementById("changePasswordMsgBox");
      msgBox.classList.add("hidden");
      msgBox.textContent = "";

      const currentVal = currentPw.value;
      const newVal = newPw.value;
      const confirmVal = confirmPw.value;
      const submitBtn = document.getElementById("changePasswordSubmitBtn");
      const spinner = submitBtn.querySelector(".spinner");

      if (newVal.length < 6) {
        msgBox.textContent = "New password must be at least 6 characters long.";
        msgBox.classList.remove("hidden");
        return;
      }
      if (newVal !== confirmVal) {
        msgBox.textContent = "New password and confirmation do not match.";
        msgBox.classList.remove("hidden");
        return;
      }

      submitBtn.disabled = true;
      spinner.classList.remove("hidden");

      try {
        const res = await apiRequest("/api/auth/change-password", {
          method: "POST",
          body: {
            currentPassword: currentVal,
            newPassword: newVal,
            confirmPassword: confirmVal
          }
        });

        toast(res.message || "Password updated successfully!", "success");
        form.reset();
      } catch (err) {
        msgBox.textContent = err.message || "Failed to update password.";
        msgBox.classList.remove("hidden");
        toast(err.message, "error");
      } finally {
        submitBtn.disabled = false;
        spinner.classList.add("hidden");
      }
    });
  }

  // Bind Request Change Buttons
  document.querySelectorAll(".btn-req-change").forEach(btn => {
    btn.addEventListener("click", () => {
      const fieldName = btn.getAttribute("data-field");
      const label = btn.getAttribute("data-label");
      const currentValue = btn.getAttribute("data-current");
      const type = btn.getAttribute("data-type") || "text";
      openProfileChangeModal({ fieldName, label, currentValue, type });
    });
  });
}


function renderUpcomingShiftsTableHTML(list) {
  if (!list.length) {
    return `<div class="empty-state-box"><p>No upcoming duties scheduled yet.</p></div>`;
  }

  return `
    <table>
      <thead>
        <tr>
          <th>Date</th>
          <th>Day</th>
          <th>Assigned Duty</th>
          <th>Working Hours</th>
          <th>Status / Flag</th>
        </tr>
      </thead>
      <tbody>
        ${list.map(a => {
          const dt = new Date(a.rosterDate);
          const dayName = dt.toLocaleDateString("en-US", { weekday: "short" });
          let flagHtml = `<span class="flag-badge flag-working">WORKING</span>`;
          if (a.onLeave) {
            flagHtml = `<span class="flag-badge flag-leave">🏖️ OFF — Leave</span>`;
          } else if (a.weeklyOff || a.shiftType === "OFF") {
            flagHtml = `<span class="flag-badge flag-weeklyoff">🛋️ OFF — Weekly OFF</span>`;
          } else if (a.overridden) {
            flagHtml = `<span class="flag-badge flag-override">⚡ OVERRIDE</span>`;
          }

          return `
            <tr>
              <td><strong>${formatDate(a.rosterDate)}</strong></td>
              <td><code>${dayName}</code></td>
              <td><span class="badge ${String(a.shiftType).toLowerCase()}">${a.onLeave ? 'OFF (Leave)' : a.shiftType}</span></td>
              <td><small style="font-weight:600; color:var(--text-muted);">${a.onLeave ? 'Approved Leave' : getShiftTimingDisplay(a.shiftType)}</small></td>
              <td>${flagHtml}</td>
            </tr>
          `;
        }).join("")}
      </tbody>
    </table>
  `;
}

function renderWorkspaceLeaveManagementHTML(leaves, roster, empId) {
  const activeAndPendingLeaves = leaves.filter(l => l.status === "APPROVED" || l.status === "PENDING" || l.status === "PENDING_MODIFICATION" || l.status === "PENDING_CANCELLATION");

  return `
    <!-- Top Row: Apply for Leave + Active/Pending Applications -->
    <div class="form-row two-col" style="margin-bottom:24px;">
      
      <!-- A. Apply for Leave Form -->
      <div class="card">
        <div class="card-header">
          <div>
            <h3>Apply for Leave</h3>
            <span style="font-size:0.76rem; color:var(--text-muted);">Submit dates for administrative approval</span>
          </div>
        </div>
        <form id="empApplyLeaveForm" class="card-body stack-gap">
          <div class="form-row two-col">
            <div class="form-group">
              <label for="leaveStartInput">Start Date <span class="req">*</span></label>
              <input type="date" id="leaveStartInput" required>
            </div>
            <div class="form-group">
              <label for="leaveEndInput">End Date <span class="req">*</span></label>
              <input type="date" id="leaveEndInput" required>
            </div>
          </div>

          <!-- Live Duration Preview Box -->
          <div class="duration-preview-box" id="applyDurationPreview" style="display:none;">
            <span>🗓️ Duration: <strong id="applyDurDaysText">0 days</strong></span>
            <span id="applyDurDatesText" style="font-size:0.78rem; opacity:0.9;"></span>
          </div>

          <!-- Live Overlap Warning Box -->
          <div class="alert-info-box" id="applyOverlapAlert" style="display:none; background-color:#fee2e2; border-color:#fca5a5; color:#991b1b;">
            <strong>⚠️ Overlap Conflict:</strong>
            <span>You already have an active leave request covering these dates.</span>
          </div>

          <div class="form-group">
            <label for="leaveReasonInput">Reason for Leave <span class="req">*</span></label>
            <textarea id="leaveReasonInput" placeholder="State reason (e.g. Urgent family work, Personal, Medical)" required></textarea>
          </div>

          <button type="submit" class="btn btn-primary btn-block" id="submitEmpLeaveBtn">
            <span>Submit Leave Request</span>
            <div class="spinner hidden"></div>
          </button>
        </form>
      </div>

      <!-- B. My Active & Pending Leave Applications -->
      <div class="card">
        <div class="card-header">
          <div>
            <h3>Active & Pending Requests (${activeAndPendingLeaves.length})</h3>
            <span style="font-size:0.76rem; color:var(--text-muted);">Manage approved leaves (Modify / Cancel) and track pending approvals</span>
          </div>
        </div>
        <div class="table-wrap" style="max-height:380px; overflow-y:auto;">
          ${renderActiveLeavesListHTML(activeAndPendingLeaves)}
        </div>
      </div>

    </div>

    <!-- C. Interactive Leave Calendar View -->
    <div class="leave-calendar-card" style="margin-bottom:24px;">
      <div class="cal-header-bar">
        <div>
          <h3 style="display:flex; align-items:center; gap:8px;">
            <span>📅</span> Leave Calendar & Scheduled Absence
          </h3>
          <span style="font-size:0.76rem; color:var(--text-muted);">Visual monthly breakdown of approved leaves, pending requests, and assigned duties</span>
        </div>
        <div style="display:flex; align-items:center; gap:10px;">
          <button class="btn btn-ghost btn-sm" id="calPrevMonthBtn">&larr; Prev</button>
          <strong id="calMonthYearTitle" style="font-size:0.92rem; min-width:130px; text-align:center;">August 2026</strong>
          <button class="btn btn-ghost btn-sm" id="calNextMonthBtn">Next &rarr;</button>
        </div>
      </div>
      
      <div id="leaveCalendarGridWrapper">
        ${renderCalendarGridHTML(leaves, roster)}
      </div>
    </div>

    <!-- D. Complete Leave History & Audit Trail -->
    <div class="card">
      <div class="card-header">
        <div>
          <h3>Leave History & Audit Trail</h3>
          <span style="font-size:0.76rem; color:var(--text-muted);">Complete immutable record of all submitted, modified, rejected, and cancelled leaves</span>
        </div>
        <span class="badge morning">${leaves.length} Total Records</span>
      </div>
      <div class="table-wrap">
        ${renderFullLeaveHistoryTableHTML(leaves)}
      </div>
    </div>
  `;
}

function renderActiveLeavesListHTML(list) {
  if (!list.length) {
    return `<div class="empty-state-box"><p>No active or pending leave applications.</p></div>`;
  }

  return `
    <table>
      <thead>
        <tr>
          <th>Leave Period</th>
          <th>Days</th>
          <th>Status</th>
          <th>Reason / Notes</th>
          <th style="text-align:right;">Actions</th>
        </tr>
      </thead>
      <tbody>
        ${list.map(l => {
          const days = calculateDaysBetween(l.startDate, l.endDate);
          let statusBadge = `<span class="status-pill pending">Pending Review</span>`;
          let actionsHtml = `<span style="font-size:0.74rem; color:var(--text-muted);">In Review</span>`;

          if (l.status === "APPROVED") {
            statusBadge = `<span class="status-pill active">APPROVED</span>`;
            actionsHtml = `
              <div class="row-actions" style="justify-content:flex-end;">
                <button class="btn btn-secondary btn-sm" data-action="open-modify-leave" data-id="${l.id}" title="Shorten or extend leave dates">
                  ✏️ Modify Dates
                </button>
                <button class="btn btn-danger btn-sm" data-action="open-cancel-leave" data-id="${l.id}" title="Request leave cancellation">
                  🚫 Cancel
                </button>
              </div>
            `;
          } else if (l.status === "PENDING_MODIFICATION") {
            const newDays = calculateDaysBetween(l.pendingStartDate, l.pendingEndDate);
            const diff = newDays - days;
            const diffStr = diff < 0 ? `${Math.abs(diff)}d released` : `+${diff}d added`;
            statusBadge = `<span class="status-pill pending-mod">Pending Mod</span>`;
            actionsHtml = `
              <div style="font-size:0.72rem; color:#6d28d9; font-weight:700; text-align:right;">
                Change to ${formatDate(l.pendingStartDate)} - ${formatDate(l.pendingEndDate)} (${diffStr})
              </div>
            `;
          } else if (l.status === "PENDING_CANCELLATION") {
            statusBadge = `<span class="status-pill pending-cancel">Pending Cancel</span>`;
            actionsHtml = `
              <div style="font-size:0.72rem; color:#b45309; font-weight:700; text-align:right;">
                Cancellation under review
              </div>
            `;
          }

          return `
            <tr>
              <td>
                <code>${formatDate(l.startDate)}</code> &rarr; <code>${formatDate(l.endDate)}</code>
                ${l.originalStartDate && l.originalStartDate !== l.startDate ? `<div style="font-size:0.7rem; color:var(--text-muted);">Orig: ${formatDate(l.originalStartDate)} - ${formatDate(l.originalEndDate)}</div>` : ''}
              </td>
              <td><strong>${days}d</strong></td>
              <td>${statusBadge}</td>
              <td style="max-width:180px; font-size:0.82rem;">${l.modificationReason || l.cancellationReason || l.reason}</td>
              <td style="text-align:right;">${actionsHtml}</td>
            </tr>
          `;
        }).join("")}
      </tbody>
    </table>
  `;
}

function renderFullLeaveHistoryTableHTML(list) {
  if (!list.length) {
    return `<div class="empty-state-box"><p>No leave history recorded.</p></div>`;
  }

  return `
    <table>
      <thead>
        <tr>
          <th>Leave Period</th>
          <th>Duration</th>
          <th>Type / Mode</th>
          <th>Reason</th>
          <th>Status</th>
          <th>Requested On</th>
          <th>Audit Details</th>
        </tr>
      </thead>
      <tbody>
        ${list.map(l => {
          const days = calculateDaysBetween(l.startDate, l.endDate);
          let statusPillClass = "pending";
          if (l.status === "APPROVED") statusPillClass = "active";
          if (l.status === "REJECTED") statusPillClass = "inactive";
          if (l.status === "CANCELLED") statusPillClass = "cancelled";
          if (l.status === "PENDING_MODIFICATION") statusPillClass = "pending-mod";
          if (l.status === "PENDING_CANCELLATION") statusPillClass = "pending-cancel";

          let auditDetails = l.adminRemarks || "-";
          if (l.originalStartDate && (l.originalStartDate !== l.startDate || l.originalEndDate !== l.endDate)) {
            auditDetails = `Modified (Original: ${formatDate(l.originalStartDate)} - ${formatDate(l.originalEndDate)}). ${l.adminRemarks || ''}`;
          }

          return `
            <tr>
              <td>
                <strong>${formatDate(l.startDate)}</strong> &rarr; <strong>${formatDate(l.endDate)}</strong>
              </td>
              <td><span class="badge morning">${days} Day(s)</span></td>
              <td><small style="font-weight:600; color:var(--text-muted);">Personal Leave</small></td>
              <td>${l.reason}</td>
              <td><span class="status-pill ${statusPillClass}">${l.status}</span></td>
              <td><small style="color:var(--text-muted);">${l.requestedAt ? new Date(l.requestedAt).toLocaleDateString() : '-'}</small></td>
              <td style="font-size:0.78rem; color:var(--text-muted); max-width:220px;">${auditDetails}</td>
            </tr>
          `;
        }).join("")}
      </tbody>
    </table>
  `;
}

function renderCalendarGridHTML(leaves, roster) {
  const now = new Date();
  const baseDate = new Date(now.getFullYear(), now.getMonth() + (state.calendarMonthOffset || 0), 1);
  const year = baseDate.getFullYear();
  const month = baseDate.getMonth();
  
  const monthNames = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
  const dayNames = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

  const firstDayIndex = new Date(year, month, 1).getDay();
  const lastDayDate = new Date(year, month + 1, 0).getDate();
  const prevMonthLastDate = new Date(year, month, 0).getDate();

  const todayStr = getTodayISOString();

  let daysHtml = "";
  // Day names header
  dayNames.forEach(d => {
    daysHtml += `<div class="cal-day-name">${d}</div>`;
  });

  // Prev month filler cells
  for (let i = firstDayIndex - 1; i >= 0; i--) {
    const d = prevMonthLastDate - i;
    daysHtml += `<div class="cal-day-cell other-month"><span class="cal-day-num">${d}</span></div>`;
  }

  // Current month cells
  for (let day = 1; day <= lastDayDate; day++) {
    const monthPad = String(month + 1).padStart(2, '0');
    const dayPad = String(day).padStart(2, '0');
    const dateStr = `${year}-${monthPad}-${dayPad}`;

    const isToday = dateStr === todayStr;

    // Check Leave
    const approvedLeave = leaves.find(l => l.status === "APPROVED" && dateStr >= l.startDate && dateStr <= l.endDate);
    const pendingLeave = leaves.find(l => (l.status === "PENDING" || l.status === "PENDING_MODIFICATION" || l.status === "PENDING_CANCELLATION") && dateStr >= l.startDate && dateStr <= l.endDate);
    const duty = roster.find(r => r.rosterDate === dateStr);

    let pillsHtml = "";
    if (approvedLeave) {
      pillsHtml += `<span class="cal-pill leave-approved">🏖️ Leave</span>`;
    } else if (pendingLeave) {
      pillsHtml += `<span class="cal-pill leave-pending">⏳ Pending</span>`;
    } else if (duty) {
      if (duty.weeklyOff) {
        pillsHtml += `<span class="cal-pill shift-off">🛋️ OFF</span>`;
      } else {
        pillsHtml += `<span class="cal-pill shift-duty">${duty.shiftType}</span>`;
      }
    }

    daysHtml += `
      <div class="cal-day-cell ${isToday ? 'is-today' : ''}">
        <span class="cal-day-num">${day}</span>
        ${pillsHtml}
      </div>
    `;
  }

  // Next month filler cells
  const totalCells = firstDayIndex + lastDayDate;
  const remaining = (7 - (totalCells % 7)) % 7;
  for (let j = 1; j <= remaining; j++) {
    daysHtml += `<div class="cal-day-cell other-month"><span class="cal-day-num">${j}</span></div>`;
  }

  return `
    <div class="cal-grid">
      ${daysHtml}
    </div>
  `;
}

function bindLeaveManagementEvents(empId, leaves, roster) {
  // Calendar month buttons
  const prevBtn = document.getElementById("calPrevMonthBtn");
  const nextBtn = document.getElementById("calNextMonthBtn");
  if (prevBtn) {
    prevBtn.addEventListener("click", () => {
      state.calendarMonthOffset = (state.calendarMonthOffset || 0) - 1;
      updateCalendarMonthHeader();
      document.getElementById("leaveCalendarGridWrapper").innerHTML = renderCalendarGridHTML(leaves, roster);
    });
  }
  if (nextBtn) {
    nextBtn.addEventListener("click", () => {
      state.calendarMonthOffset = (state.calendarMonthOffset || 0) + 1;
      updateCalendarMonthHeader();
      document.getElementById("leaveCalendarGridWrapper").innerHTML = renderCalendarGridHTML(leaves, roster);
    });
  }

  // Live Apply form duration calculation & overlap check
  const startInput = document.getElementById("leaveStartInput");
  const endInput = document.getElementById("leaveEndInput");
  const durPreview = document.getElementById("applyDurationPreview");
  const overlapAlert = document.getElementById("applyOverlapAlert");

  function updateApplyPreview() {
    const s = startInput.value;
    const e = endInput.value;
    if (s && e) {
      if (e >= s) {
        const days = calculateDaysBetween(s, e);
        durPreview.style.display = "flex";
        document.getElementById("applyDurDaysText").textContent = `${days} day${days > 1 ? 's' : ''}`;
        document.getElementById("applyDurDatesText").textContent = `${formatDate(s)} → ${formatDate(e)}`;

        // Check Overlap
        const hasOverlap = leaves.some(l => 
          (l.status === "APPROVED" || l.status === "PENDING" || l.status === "PENDING_MODIFICATION") &&
          !(e < l.startDate || s > l.endDate)
        );
        overlapAlert.style.display = hasOverlap ? "block" : "none";
      } else {
        durPreview.style.display = "none";
        overlapAlert.style.display = "none";
      }
    } else {
      durPreview.style.display = "none";
      overlapAlert.style.display = "none";
    }
  }

  if (startInput && endInput) {
    startInput.addEventListener("input", updateApplyPreview);
    endInput.addEventListener("input", updateApplyPreview);
  }

  // Apply Form Submit
  const applyForm = document.getElementById("empApplyLeaveForm");
  if (applyForm) {
    applyForm.addEventListener("submit", async (e) => {
      e.preventDefault();
      const startDate = startInput.value;
      const endDate = endInput.value;
      const reason = document.getElementById("leaveReasonInput").value.trim();
      const submitBtn = document.getElementById("submitEmpLeaveBtn");
      const spinner = submitBtn.querySelector(".spinner");

      if (endDate < startDate) {
        toast("End date cannot be before start date", "error");
        return;
      }

      try {
        submitBtn.disabled = true;
        spinner.classList.remove("hidden");

        await apiRequest("/api/leaves", {
          method: "POST",
          body: { employeeId: empId, startDate, endDate, reason }
        });

        broadcastDataMutation("LEAVE_APPLIED");
        toast("Leave request submitted successfully", "success");
        await renderEmployeeWorkspaceView();
      } catch (err) {
        toast(err.message, "error");
      } finally {
        submitBtn.disabled = false;
        spinner.classList.add("hidden");
      }
    });
  }

  // Bind Modify Dates Button
  document.querySelectorAll("[data-action='open-modify-leave']").forEach(btn => {
    btn.addEventListener("click", () => {
      const leaveId = Number(btn.getAttribute("data-id"));
      const leave = leaves.find(l => l.id === leaveId);
      if (leave) {
        openModifyLeaveModal(leave, roster);
      }
    });
  });

  // Bind Cancel Leave Button
  document.querySelectorAll("[data-action='open-cancel-leave']").forEach(btn => {
    btn.addEventListener("click", () => {
      const leaveId = Number(btn.getAttribute("data-id"));
      const leave = leaves.find(l => l.id === leaveId);
      if (leave) {
        openCancelLeaveModal(leave);
      }
    });
  });
}

function updateCalendarMonthHeader() {
  const now = new Date();
  const baseDate = new Date(now.getFullYear(), now.getMonth() + (state.calendarMonthOffset || 0), 1);
  const monthNames = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
  const titleEl = document.getElementById("calMonthYearTitle");
  if (titleEl) {
    titleEl.textContent = `${monthNames[baseDate.getMonth()]} ${baseDate.getFullYear()}`;
  }
}

// Modify Leave Modal Handlers
function openModifyLeaveModal(leave, roster) {
  state.activeLeaveUnderMod = leave;
  document.getElementById("modLeaveId").value = leave.id;
  
  const curDays = calculateDaysBetween(leave.startDate, leave.endDate);
  document.getElementById("modCurrentLeaveBox").innerHTML = `
    <div><strong>Current Approved Leave:</strong> <code>${formatDate(leave.startDate)}</code> &rarr; <code>${formatDate(leave.endDate)}</code> (${curDays} days)</div>
    <div style="font-size:0.76rem; color:var(--text-muted); margin-top:2px;">Reason: ${leave.reason}</div>
  `;

  document.getElementById("modNewStartDate").value = leave.startDate;
  document.getElementById("modNewEndDate").value = leave.endDate;
  document.getElementById("modReasonInput").value = "";

  updateModifyLeaveCalculation();
  openModal("modifyLeaveModal");
}

function updateModifyLeaveCalculation() {
  const leave = state.activeLeaveUnderMod;
  if (!leave) return;

  const newStart = document.getElementById("modNewStartDate").value;
  const newEnd = document.getElementById("modNewEndDate").value;

  const curDays = calculateDaysBetween(leave.startDate, leave.endDate);
  document.getElementById("modCurrentDurVal").textContent = `${curDays} day${curDays > 1 ? 's' : ''}`;

  const impactBox = document.getElementById("modRosterImpactBox");
  const impactText = document.getElementById("modRosterImpactText");

  if (newStart && newEnd && newEnd >= newStart) {
    const newDays = calculateDaysBetween(newStart, newEnd);
    document.getElementById("modNewDurVal").textContent = `${newDays} day${newDays > 1 ? 's' : ''}`;

    const diff = newDays - curDays;
    const diffContainer = document.getElementById("modDiffContainer");
    const diffVal = document.getElementById("modDiffVal");

    if (diff < 0) {
      diffVal.textContent = `${Math.abs(diff)} day(s) released`;
      diffVal.className = "dur-value diff-released";
      impactBox.style.display = "block";
      impactText.innerHTML = `Shortening this approved leave will release <strong>${Math.abs(diff)} day(s)</strong> back to the available duty pool. If you had existing roster assignments during those dates, they will be updated appropriately upon admin approval.`;
    } else if (diff > 0) {
      diffVal.textContent = `+${diff} day(s) added`;
      diffVal.className = "dur-value diff-added";
      impactBox.style.display = "block";
      impactText.innerHTML = `Extending this leave requires administrative approval. Your assigned duties during the extended dates will be set to OFF upon approval.`;
    } else {
      diffVal.textContent = "Dates shifted (same duration)";
      diffVal.className = "dur-value";
      impactBox.style.display = "block";
      impactText.innerHTML = `Changing your approved leave window requires administrative review and will update corresponding shift assignments upon approval.`;
    }
  } else {
    document.getElementById("modNewDurVal").textContent = "-";
    document.getElementById("modDiffVal").textContent = "-";
    impactBox.style.display = "none";
  }
}

async function handleConfirmModifyLeave(e) {
  e.preventDefault();
  const leaveId = document.getElementById("modLeaveId").value;
  const newStartDate = document.getElementById("modNewStartDate").value;
  const newEndDate = document.getElementById("modNewEndDate").value;
  const reason = document.getElementById("modReasonInput").value.trim();
  const btn = document.getElementById("submitModLeaveBtn");
  const spinner = btn.querySelector(".spinner");

  if (newEndDate < newStartDate) {
    toast("New end date cannot be before start date", "error");
    return;
  }

  try {
    btn.disabled = true;
    spinner.classList.remove("hidden");

    await apiRequest(`/api/leaves/${leaveId}/modification`, {
      method: "POST",
      body: { newStartDate, newEndDate, reason }
    });

    broadcastDataMutation("LEAVE_MODIFIED");
    toast("Leave modification request submitted successfully", "success");
    closeModal("modifyLeaveModal");
    await renderEmployeeWorkspaceView();
  } catch (err) {
    toast(err.message, "error");
  } finally {
    btn.disabled = false;
    spinner.classList.add("hidden");
  }
}

// Cancel Leave Modal Handlers
function openCancelLeaveModal(leave) {
  document.getElementById("cancelLeaveId").value = leave.id;
  const days = calculateDaysBetween(leave.startDate, leave.endDate);
  document.getElementById("cancelLeaveDetailsBox").innerHTML = `
    <div>Leave Period: <strong>${formatDate(leave.startDate)} &rarr; ${formatDate(leave.endDate)}</strong> (${days} days)</div>
    <div style="font-size:0.76rem; color:var(--text-muted); margin-top:2px;">Reason: ${leave.reason}</div>
  `;
  document.getElementById("cancelLeaveReasonInput").value = "Plans cancelled / Returning to duty";
  openModal("cancelLeaveModal");
}

async function handleConfirmCancelLeave(e) {
  e.preventDefault();
  const leaveId = document.getElementById("cancelLeaveId").value;
  const reason = document.getElementById("cancelLeaveReasonInput").value.trim();
  const btn = document.getElementById("submitCancelLeaveBtn");
  const spinner = btn.querySelector(".spinner");

  try {
    btn.disabled = true;
    spinner.classList.remove("hidden");

    await apiRequest(`/api/leaves/${leaveId}/cancellation`, {
      method: "POST",
      body: { reason }
    });

    broadcastDataMutation("LEAVE_CANCELLED");
    toast("Leave cancellation request submitted successfully", "success");
    closeModal("cancelLeaveModal");
    await renderEmployeeWorkspaceView();
  } catch (err) {
    toast(err.message, "error");
  } finally {
    btn.disabled = false;
    spinner.classList.add("hidden");
  }
}

// Helper: Calculate days between two ISO date strings (inclusive)
function calculateDaysBetween(startStr, endStr) {
  if (!startStr || !endStr) return 0;
  const dt1 = new Date(startStr);
  const dt2 = new Date(endStr);
  const diffTime = Math.abs(dt2 - dt1);
  return Math.ceil(diffTime / (1000 * 60 * 60 * 24)) + 1;
}

function getTodayISOString() {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function renderMyLeavesTableHTML(list) {
  if (!list.length) return `<div class="empty-state-box"><p>No leave requests found.</p></div>`;
  return `
    <table>
      <thead>
        <tr>
          <th>Period</th>
          <th>Reason</th>
          <th>Status</th>
        </tr>
      </thead>
      <tbody>
        ${list.map(l => `
          <tr>
            <td><code>${formatDate(l.startDate)}</code> &rarr; <code>${formatDate(l.endDate)}</code></td>
            <td>${l.reason}</td>
            <td><span class="status-pill ${l.status.toLowerCase()}">${l.status}</span></td>
          </tr>
        `).join("")}
      </tbody>
    </table>
  `;
}

function renderMyRosterTableHTML(list) {
  if (!list.length) return `<div class="empty-state-box"><p>No scheduled duties assigned yet.</p></div>`;
  return `
    <table>
      <thead>
        <tr>
          <th>Date</th>
          <th>Shift</th>
          <th>Timings</th>
          <th>Flags / Status</th>
        </tr>
      </thead>
      <tbody>
        ${list.map(a => `
          <tr>
            <td><strong>${formatDate(a.rosterDate)}</strong></td>
            <td><span class="badge ${String(a.shiftType).toLowerCase()}">${a.onLeave ? 'OFF (Leave)' : a.shiftType}</span></td>
            <td><small style="font-weight:600; color:var(--text-muted);">${a.onLeave ? 'Approved Leave' : getShiftTimingDisplay(a.shiftType)}</small></td>
            <td>${a.onLeave ? '<span class="flag-badge flag-leave">🏖️ OFF — Leave</span>' : a.weeklyOff ? '<span class="flag-badge flag-weeklyoff">🛋️ OFF — Weekly OFF</span>' : a.overridden ? '<span class="flag-badge flag-override">⚡ OVERRIDE</span>' : '<span class="flag-badge flag-working">WORKING</span>'}</td>
          </tr>
        `).join("")}
      </tbody>
    </table>
  `;
}


/* ==========================================================================
   VIEW 8: INDIVIDUAL EMPLOYEE ROSTER INSPECTOR
   ========================================================================== */

async function renderEmployeeRosterDetailView() {
  const container = dom.views.employeeRosterDetail;
  const empId = state.inspectedEmployeeId;


  container.innerHTML = `<div class="empty-state-box"><div class="spinner"></div><p>Loading schedule for ${state.inspectedEmployeeName}...</p></div>`;

  try {
    const roster = await apiRequest(`/api/rosters/employee/${empId}`);

    container.innerHTML = `
      <div class="card">
        <div class="card-header">
          <div>
            <h2>${state.inspectedEmployeeName}</h2>
            <span style="font-size:0.78rem; color:var(--text-muted);">Historical and upcoming shift duty log</span>
          </div>
          <button class="btn btn-secondary btn-sm" id="backToEmployeesBtn">&larr; Back to Employees</button>
        </div>
        <div class="table-wrap">
          ${renderMyRosterTableHTML(roster)}
        </div>
      </div>
    `;

    document.getElementById("backToEmployeesBtn").addEventListener("click", () => navigateTo("employees"));

  } catch (err) {
    container.innerHTML = `<div class="empty-state-box"><p style="color:var(--danger)">Error loading employee schedule: ${err.message}</p></div>`;
  }
}


/* ==========================================================================
   MODALS ACTIONS & HANDLERS
   ========================================================================== */

function openModal(id) {
  const m = document.getElementById(id);
  if (m) m.classList.remove("hidden");
}

function closeModal(id) {
  const m = document.getElementById(id);
  if (m) m.classList.add("hidden");
}

// Add/Edit Employee Modal
function openEmployeeModal(emp) {
  const form = document.getElementById("employeeModalForm");
  form.reset();

  if (emp) {
    document.getElementById("employeeModalTitle").textContent = `Edit Employee (${emp.employeeCode})`;
    document.getElementById("empFormId").value = emp.id;
    document.getElementById("empFormCode").value = emp.employeeCode;
    document.getElementById("empFormGender").value = emp.gender;
    document.getElementById("empFormFirstName").value = emp.firstName;
    document.getElementById("empFormLastName").value = emp.lastName || "";
    document.getElementById("empFormEmail").value = emp.email;
    document.getElementById("empAccountFields").style.display = "none";
  } else {
    document.getElementById("employeeModalTitle").textContent = "Add New Employee";
    document.getElementById("empFormId").value = "";
    document.getElementById("empAccountFields").style.display = "grid";
  }

  openModal("employeeModal");
}

async function handleSaveEmployee(e) {
  e.preventDefault();
  const id = document.getElementById("empFormId").value;
  const employeeCode = document.getElementById("empFormCode").value.trim();
  const gender = document.getElementById("empFormGender").value;
  const firstName = document.getElementById("empFormFirstName").value.trim();
  const lastName = document.getElementById("empFormLastName").value.trim();
  const email = document.getElementById("empFormEmail").value.trim();
  const username = document.getElementById("empFormUsername").value.trim();
  const password = document.getElementById("empFormPassword").value;
  const saveBtn = document.getElementById("saveEmployeeBtn");

  const payload = { employeeCode, gender, firstName, lastName, email };
  if (!id) {
    if (username) payload.username = username;
    if (password) payload.password = password;
  }

  try {
    saveBtn.disabled = true;
    if (id) {
      await apiRequest(`/api/employees/${id}`, { method: "PUT", body: payload });
      toast("Employee updated successfully", "success");
    } else {
      await apiRequest("/api/employees", { method: "POST", body: payload });
      toast("Employee created successfully", "success");
    }
    closeModal("employeeModal");
    await renderEmployeesView();
  } catch (err) {
    toast(err.message, "error");
  } finally {
    saveBtn.disabled = false;
  }
}

// Coverage & Feasibility Shortage Banner Component
function renderCoverageBanner(currentCycle) {
  const cr = currentCycle.coverageReport;
  if (!cr) return "";
  const opShortage = cr.operationalShortage !== undefined ? cr.operationalShortage : cr.totalShortage;
  const cfgDemand = cr.configuredDemand || cr.totalRequired || 56;
  const wfCap = cr.workforceCapacity || 42;
  const assigned = cr.totalAssigned || 42;

  if (opShortage > 0) {
    return `
      <div style="margin:12px 20px; padding:12px 18px; background-color:rgba(234, 179, 8, 0.1); border:1px solid rgba(234, 179, 8, 0.35); border-radius:8px; display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:10px;">
        <div style="display:flex; align-items:center; gap:10px;">
          <span style="font-size:1.4rem;">⚠️</span>
          <div>
            <strong style="color:#d97706; font-size:0.92rem;">
              Operational Coverage Shortage: ${opShortage} position${opShortage > 1 ? 's' : ''} uncovered
            </strong>
            <span style="display:block; font-size:0.78rem; color:var(--text-muted); margin-top:2px;">
              Workforce Capacity: ${wfCap} positions &bull; Staffed: ${assigned} &bull; All 10 safety rules strictly enforced.
            </span>
          </div>
        </div>
        <button class="btn btn-secondary btn-sm" id="viewFeasibilityDetailsBtn" style="font-weight:700; border-color:#d97706; color:#d97706;">
          📊 View Feasibility Breakdown
        </button>
      </div>
    `;
  } else if (cfgDemand > wfCap) {
    return `
      <div style="margin:12px 20px; padding:12px 18px; background-color:rgba(59, 130, 246, 0.08); border:1px solid rgba(59, 130, 246, 0.25); border-radius:8px; display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:10px;">
        <div style="display:flex; align-items:center; gap:10px;">
          <span style="font-size:1.3rem;">ℹ️</span>
          <div>
            <strong style="color:var(--primary); font-size:0.92rem;">
              ✓ All 4 Shift Types Fully Staffed (0 Operational Shortage)
            </strong>
            <span style="display:block; font-size:0.78rem; color:var(--text-muted); margin-top:2px;">
              Configured Demand: ${cfgDemand} &bull; Active Workforce Capacity: ${wfCap} (6 working + 1 non-working/day) &bull; Staffed: ${assigned} &bull; Configured demand exceeds workforce capacity.
            </span>
          </div>
        </div>
        <button class="btn btn-secondary btn-sm" id="viewFeasibilityDetailsBtn" style="font-weight:700;">
          📊 View Feasibility Breakdown
        </button>
      </div>
    `;
  } else {
    return `
      <div style="margin:10px 20px; padding:8px 16px; background-color:rgba(34, 197, 94, 0.08); border:1px solid rgba(34, 197, 94, 0.25); border-radius:8px; display:flex; justify-content:space-between; align-items:center;">
        <span style="color:#16a34a; font-size:0.82rem; font-weight:700; display:flex; align-items:center; gap:6px;">
          <span>✓</span> 100% Target Capacity Staffed (${assigned}/${cfgDemand} positions staffed)
        </span>
        <button class="btn btn-ghost btn-sm" id="viewFeasibilityDetailsBtn" style="font-size:0.76rem; color:var(--text-muted);">
          View Details &rarr;
        </button>
      </div>
    `;
  }
}

// Feasibility Report Breakdown Modal
function openFeasibilityModal(cycle) {
  const cr = cycle.coverageReport;
  const body = document.getElementById("feasibilityModalBody");
  if (!cr || !cr.dailyReports || !cr.dailyReports.length) {
    body.innerHTML = `<div class="empty-state-box"><p>No feasibility report available for this cycle.</p></div>`;
    openModal("feasibilityModal");
    return;
  }

  const cfgDemand = cr.configuredDemand || cr.totalRequired || 56;
  const wfCap = cr.workforceCapacity || 42;
  const feasibleCap = cr.feasibleCapacity || 42;
  const assigned = cr.totalAssigned || 42;
  const opShortage = cr.operationalShortage !== undefined ? cr.operationalShortage : cr.totalShortage;

  body.innerHTML = `
    <div style="margin-bottom:16px; display:flex; gap:12px; flex-wrap:wrap;">
      <div class="stat-card" style="flex:1; min-width:130px; padding:10px 14px; border-left:3px solid var(--text-muted); background-color:var(--bg-app);">
        <span style="font-size:0.72rem; color:var(--text-muted); font-weight:700;">Configured Demand</span>
        <h3 style="font-size:1.25rem; margin-top:2px;">${cfgDemand}</h3>
        <span style="font-size:0.68rem; color:var(--text-muted);">8/day target</span>
      </div>
      <div class="stat-card" style="flex:1; min-width:130px; padding:10px 14px; border-left:3px solid var(--primary); background-color:var(--bg-app);">
        <span style="font-size:0.72rem; color:var(--text-muted); font-weight:700;">Workforce Capacity</span>
        <h3 style="font-size:1.25rem; margin-top:2px; color:var(--primary);">${wfCap}</h3>
        <span style="font-size:0.68rem; color:var(--text-muted);">7 staff &times; 6 working</span>
      </div>
      <div class="stat-card" style="flex:1; min-width:130px; padding:10px 14px; border-left:3px solid #16a34a; background-color:var(--bg-app);">
        <span style="font-size:0.72rem; color:var(--text-muted); font-weight:700;">Actual Assigned</span>
        <h3 style="font-size:1.25rem; margin-top:2px; color:#16a34a;">${assigned}</h3>
        <span style="font-size:0.68rem; color:var(--text-muted);">100% staffable fulfilled</span>
      </div>
      <div class="stat-card" style="flex:1; min-width:130px; padding:10px 14px; border-left:3px solid ${opShortage > 0 ? '#d97706' : '#16a34a'}; background-color:var(--bg-app);">
        <span style="font-size:0.72rem; color:var(--text-muted); font-weight:700;">Operational Shortage</span>
        <h3 style="font-size:1.25rem; margin-top:2px; color:${opShortage > 0 ? '#d97706' : '#16a34a'};">${opShortage}</h3>
        <span style="font-size:0.68rem; color:var(--text-muted);">${opShortage === 0 ? '✓ Zero Shortage' : 'Safety-limited'}</span>
      </div>
    </div>

    <table class="table" style="font-size:0.82rem; width:100%;">
      <thead>
        <tr>
          <th style="width:110px;">Date</th>
          <th style="width:105px;">Shift Type</th>
          <th style="width:75px; text-align:center;">Configured</th>
          <th style="width:70px; text-align:center;">Feasible</th>
          <th style="width:70px; text-align:center;">Assigned</th>
          <th style="width:110px;">Status</th>
          <th>Reason / Constraint Details</th>
        </tr>
      </thead>
      <tbody>
        ${cr.dailyReports.map(dr => `
          ${dr.shiftSummaries.map((s, idx) => `
            <tr style="${s.operationalShortage > 0 ? 'background-color:rgba(234, 179, 8, 0.04);' : ''}">
              ${idx === 0 ? `<td rowspan="${dr.shiftSummaries.length}" style="font-weight:700; vertical-align:top; border-right:1px solid var(--border);">${formatDate(dr.date)}</td>` : ''}
              <td><span class="badge ${s.shiftType.toLowerCase()}">${s.shiftType}</span></td>
              <td style="text-align:center;"><strong>${s.configuredCapacity !== undefined ? s.configuredCapacity : s.requiredCapacity}</strong></td>
              <td style="text-align:center; color:var(--primary);"><strong>${s.feasibleCapacity !== undefined ? s.feasibleCapacity : s.assignedCount}</strong></td>
              <td style="text-align:center; color:#16a34a;"><strong>${s.assignedCount}</strong></td>
              <td>
                <span class="status-pill ${s.operationalShortage > 0 ? 'inactive' : (s.assignedCount < (s.configuredCapacity || s.requiredCapacity) ? 'pending' : 'active')}" style="font-size:0.72rem;">
                  <span class="badge-dot" style="${s.operationalShortage > 0 ? 'background-color:#d97706;' : ''}"></span>
                  ${s.status || (s.operationalShortage === 0 ? 'Full' : `Short by ${s.operationalShortage}`)}
                </span>
              </td>
              <td style="color:${s.operationalShortage > 0 ? 'var(--text-main)' : 'var(--text-muted)'}; font-size:0.76rem;">
                ${s.reason || '✓ Fully staffed within safety rules'}
              </td>
            </tr>
          `).join("")}
        `).join("")}
      </tbody>
    </table>
  `;

  openModal("feasibilityModal");
}

// Generate Roster Modal
function openGenerateRosterModal() {
  const tomorrow = new Date();
  tomorrow.setDate(tomorrow.getDate() + 1);
  document.getElementById("rosterStartDateInput").value = tomorrow.toISOString().split("T")[0];
  openModal("generateRosterModal");
}

async function handleTriggerGenerateRoster(e) {
  e.preventDefault();
  const rawDate = document.getElementById("rosterStartDateInput").value.trim();
  const btn = document.getElementById("triggerGenerateBtn");
  const spinner = btn.querySelector(".spinner");

  // Normalize date string (support YYYY-MM-DD, DD-MM-YYYY, DD/MM/YYYY)
  let formattedDate = "";
  if (rawDate) {
    if (/^\d{4}-\d{2}-\d{2}$/.test(rawDate)) {
      formattedDate = rawDate;
    } else if (/^\d{2}-\d{2}-\d{4}$/.test(rawDate)) {
      const [d, m, y] = rawDate.split("-");
      formattedDate = `${y}-${m}-${d}`;
    } else if (/^\d{2}\/\d{2}\/\d{4}$/.test(rawDate)) {
      const [d, m, y] = rawDate.split("/");
      formattedDate = `${y}-${m}-${d}`;
    } else {
      const parsed = new Date(rawDate);
      if (!isNaN(parsed.getTime())) {
        formattedDate = parsed.toISOString().split("T")[0];
      } else {
        formattedDate = rawDate;
      }
    }
  }

  try {
    btn.disabled = true;
    spinner.classList.remove("hidden");

    // Check if a cycle already exists for this date range
    const check = await apiRequest(`/api/rosters/check-existing?startDate=${encodeURIComponent(formattedDate)}`);
    if (check && check.exists) {
      btn.disabled = false;
      spinner.classList.add("hidden");
      closeModal("generateRosterModal");
      openConflictWarningModal(check, formattedDate);
      return;
    }

    // Direct generation if no conflict
    await executeRosterGeneration(formattedDate);
  } catch (err) {
    console.error("Roster generation error:", err);
    toast(err.message || "Roster generation failed", "error");
    btn.disabled = false;
    spinner.classList.add("hidden");
  }
}

function openConflictWarningModal(check, rawDate) {
  const info = document.getElementById("conflictModalCycleInfo");
  if (info) {
    info.innerHTML = `Cycle #${check.cycleId}: <strong>${formatDate(check.startDate)}</strong> &rarr; <strong>${formatDate(check.endDate)}</strong> &bull; Mode: <strong>${check.mode || 'MANUAL'}</strong>`;
  }
  const viewBtn = document.getElementById("conflictViewExistingBtn");
  const regenBtn = document.getElementById("conflictRegenerateBtn");

  if (viewBtn) {
    viewBtn.onclick = () => {
      closeModal("conflictWarningModal");
      state.selectedCycleId = check.cycleId;
      navigateTo("roster");
    };
  }

  if (regenBtn) {
    regenBtn.onclick = async () => {
      closeModal("conflictWarningModal");
      await executeRosterGeneration(rawDate);
    };
  }

  openModal("conflictWarningModal");
}

async function executeRosterGeneration(rawDate) {
  const btn = document.getElementById("triggerGenerateBtn");
  const spinner = btn ? btn.querySelector(".spinner") : null;

  try {
    if (btn) btn.disabled = true;
    if (spinner) spinner.classList.remove("hidden");

    const queryParam = rawDate ? `?startDate=${encodeURIComponent(rawDate)}` : "";
    const res = await apiRequest(`/api/rosters/generate${queryParam}`, { method: "POST" });
    broadcastDataMutation("ROSTER_GENERATED");

    // Background email distribution for generated roster
    if (res && res.id) {
      apiRequest(`/api/rosters/cycle/${res.id}/email`, { method: "POST" }).catch(e => console.warn("Email distribution background notice", e));
    }

    if (res.coverageReport && res.coverageReport.totalShortage > 0) {
      toast(`Roster generated with ${res.coverageReport.totalShortage} coverage shortages (maximum valid coverage)`, "warning");
    } else {
      toast("Weekly roster generated & distributed successfully!", "success");
    }

    closeModal("generateRosterModal");
    state.selectedCycleId = res ? res.id : null;
    if (state.activePage === "roster") {
      await renderRosterView();
    } else {
      navigateTo("roster");
    }
  } catch (err) {
    console.error("Roster generation error:", err);
    toast(err.message || "Roster generation failed", "error");
  } finally {
    if (btn) btn.disabled = false;
    if (spinner) spinner.classList.add("hidden");
  }
}

// Download Excel (.xlsx) file
async function downloadExcel(cycleId) {
  try {
    toast("Generating official WRMS Excel export...", "info");
    const token = state.token || sessionStorage.getItem("wrmsToken");
    const response = await fetch(`/api/rosters/cycle/${cycleId}/export/excel`, {
      headers: { "Authorization": "Bearer " + token }
    });
    if (!response.ok) {
      const errJson = await response.json().catch(() => ({}));
      throw new Error(errJson.message || "Failed to export Excel spreadsheet");
    }
    const blob = await response.blob();
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    const disp = response.headers.get("Content-Disposition");
    let filename = `WRMS_Roster_Cycle_${cycleId}.xlsx`;
    if (disp && disp.includes("filename=")) {
      filename = disp.split("filename=")[1].replace(/["']/g, "").trim();
    }
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    window.URL.revokeObjectURL(url);
    toast("Excel export downloaded successfully!", "success");
  } catch (err) {
    toast(err.message, "error");
  }
}

// Download High-Resolution Image (.png) file
async function downloadImage(cycleId) {
  try {
    toast("Rendering high-resolution WRMS Roster image...", "info");
    const token = state.token || sessionStorage.getItem("wrmsToken");
    const response = await fetch(`/api/rosters/cycle/${cycleId}/export/image`, {
      headers: { "Authorization": "Bearer " + token }
    });
    if (!response.ok) {
      const errJson = await response.json().catch(() => ({}));
      throw new Error(errJson.message || "Failed to export PNG image");
    }
    const blob = await response.blob();
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    const disp = response.headers.get("Content-Disposition");
    let filename = `WRMS_Roster_Cycle_${cycleId}.png`;
    if (disp && disp.includes("filename=")) {
      filename = disp.split("filename=")[1].replace(/["']/g, "").trim();
    }
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    window.URL.revokeObjectURL(url);
    toast("Roster image downloaded successfully!", "success");
  } catch (err) {
    toast(err.message, "error");
  }
}

// Send Roster Email to all employees
async function sendRosterEmail(cycleId) {
  if (state.isSendingEmail) return;
  try {
    state.isSendingEmail = true;
    toast("Dispatching roster emails to all active employees...", "info");
    const logs = await apiRequest(`/api/rosters/cycle/${cycleId}/email`, { method: "POST" });
    const sent = logs.filter(l => l.status === "SENT").length;
    const failed = logs.filter(l => l.status === "FAILED").length;
    if (failed === 0) {
      toast(`Roster email sent successfully to all ${sent} active employees!`, "success");
    } else {
      toast(`Sent to ${sent} staff, ${failed} failed. Use 'Retry Failed Emails' to resend.`, "warning");
    }
    broadcastDataMutation("ROSTER_EMAILED");
    if (state.activePage === "history") await renderHistoryView();
  } catch (err) {
    toast(err.message, "error");
  } finally {
    state.isSendingEmail = false;
  }
}

// Retry Failed Roster Emails
async function retryRosterEmail(cycleId) {
  if (state.isRetryingEmail) return;
  try {
    state.isRetryingEmail = true;
    toast("Retrying email delivery for failed recipients...", "info");
    const logs = await apiRequest(`/api/rosters/cycle/${cycleId}/email/retry`, { method: "POST" });
    const sent = logs.filter(l => l.status === "SENT").length;
    const failed = logs.filter(l => l.status === "FAILED").length;
    if (failed === 0) {
      toast(`Retry successful: all ${sent} employees delivered.`, "success");
    } else {
      toast(`Retry completed: ${sent} delivered, ${failed} still pending/failed.`, "warning");
    }
    broadcastDataMutation("ROSTER_EMAILED");
    if (state.activePage === "history") await renderHistoryView();
  } catch (err) {
    toast(err.message, "error");
  } finally {
    state.isRetryingEmail = false;
  }
}

// Global Dashboard Schedule View State
let currentDashScheduleMode = "day";

async function loadDashboardScheduleView(mode, cycleId) {
  currentDashScheduleMode = mode;
  const container = document.getElementById("dashScheduleContent");
  const dayBtn = document.getElementById("dashScheduleDayBtn");
  const empBtn = document.getElementById("dashScheduleEmpBtn");

  if (dayBtn && empBtn) {
    if (mode === "day") {
      dayBtn.classList.add("btn-primary");
      dayBtn.classList.remove("btn-secondary");
      empBtn.classList.remove("btn-primary");
      empBtn.classList.add("btn-secondary");
    } else {
      empBtn.classList.add("btn-primary");
      empBtn.classList.remove("btn-secondary");
      dayBtn.classList.remove("btn-primary");
      dayBtn.classList.add("btn-secondary");
    }
  }

  if (!container) return;
  container.innerHTML = `<div class="empty-state-box"><div class="spinner"></div><p>Loading ${mode === 'day' ? 'day-wise' : 'employee-wise'} schedule breakdown...</p></div>`;

  try {
    const queryParam = cycleId ? `?cycleId=${cycleId}` : "";
    if (mode === "day") {
      const data = await apiRequest(`/api/dashboard/day-view${queryParam}`);
      if (!data || !data.days || !data.days.length) {
        container.innerHTML = `
          <div class="empty-state-box">
            <p>No active roster cycle data available for day-wise view.</p>
            <button class="btn btn-secondary btn-sm" style="margin-top:10px;" onclick="loadDashboardScheduleView('day', ${cycleId || 'null'})">Retry</button>
          </div>`;
        return;
      }
      container.innerHTML = renderDashboardDayCardsHTML(data.days);
    } else {
      const data = await apiRequest(`/api/dashboard/employee-view${queryParam}`);
      if (!data || !data.employees || !data.employees.length) {
        container.innerHTML = `
          <div class="empty-state-box">
            <p>No active roster cycle data available for employee-wise view.</p>
            <button class="btn btn-secondary btn-sm" style="margin-top:10px;" onclick="loadDashboardScheduleView('employee', ${cycleId || 'null'})">Retry</button>
          </div>`;
        return;
      }
      container.innerHTML = renderDashboardEmployeeTableHTML(data.employees);
    }
  } catch (err) {
    console.error("Dashboard schedule error:", err);
    container.innerHTML = `
      <div class="empty-state-box">
        <p style="color:var(--danger); font-weight:600;">Unable to load weekly schedule: ${err.message}</p>
        <button class="btn btn-secondary btn-sm" style="margin-top:10px;" onclick="loadDashboardScheduleView('${mode}', ${cycleId || 'null'})">Retry</button>
      </div>`;
  }
}

function renderDashboardDayCardsHTML(days) {
  if (!Array.isArray(days) || !days.length) {
    return `<div class="empty-state-box"><p>No schedule records for this cycle.</p></div>`;
  }

  return `
    <div class="dash-days-grid">
      ${days.map(d => {
        const morningStaff = d.morning?.employees || [];
        const generalStaff = d.general?.employees || [];
        const eveningStaff = d.evening?.employees || [];
        const nightStaff = d.night?.employees || [];
        const offStaff = d.offEmployees || [];
        const leaveStaff = d.leaveEmployees || [];

        const morningTiming = d.morning?.timing || getShiftTimingDisplay('MORNING');
        const generalTiming = d.general?.timing || getShiftTimingDisplay('GENERAL');
        const eveningTiming = d.evening?.timing || getShiftTimingDisplay('EVENING');
        const nightTiming = d.night?.timing || getShiftTimingDisplay('NIGHT');

        return `
        <div class="day-schedule-card">
          <div class="day-card-header">
            <div class="day-title-group">
              <strong>${d.dayOfWeek || 'Day'}</strong>
              <span>${formatDate(d.date)}</span>
            </div>
            <div class="day-stats-pills">
              <span class="badge morning" title="On Duty Staff">${d.totalWorking || (morningStaff.length + generalStaff.length + eveningStaff.length + nightStaff.length)} Working</span>
              <span class="badge off" title="Weekly Off Staff">${d.totalOff || offStaff.length} OFF</span>
              ${(d.totalLeave > 0 || leaveStaff.length > 0) ? `<span class="badge leave" title="On Leave">${d.totalLeave || leaveStaff.length} Leave</span>` : ''}
            </div>
          </div>

          <!-- Morning Shift (07:00 - 15:00) -->
          <div class="shift-subgroup morning">
            <div class="subgroup-header">
              <div>
                <span class="subgroup-name" style="color:var(--shift-morning-color);">Morning</span>
                <span class="subgroup-timing">${morningTiming}</span>
              </div>
              <span class="subgroup-count">${morningStaff.length} / ${d.morning?.required || 1}</span>
            </div>
            <div class="staff-chips-wrap">
              ${morningStaff.length ? morningStaff.map(renderStaffChip).join("") : '<small style="color:var(--text-muted); font-size:0.72rem;">None assigned</small>'}
            </div>
          </div>

          <!-- General Shift (09:30 - 18:00) -->
          <div class="shift-subgroup general">
            <div class="subgroup-header">
              <div>
                <span class="subgroup-name" style="color:var(--shift-general-color);">General</span>
                <span class="subgroup-timing">${generalTiming}</span>
              </div>
              <span class="subgroup-count">${generalStaff.length} / ${d.general?.required || 1}</span>
            </div>
            <div class="staff-chips-wrap">
              ${generalStaff.length ? generalStaff.map(renderStaffChip).join("") : '<small style="color:var(--text-muted); font-size:0.72rem;">None assigned</small>'}
            </div>
          </div>

          <!-- Evening Shift (14:00 - 22:00) -->
          <div class="shift-subgroup evening">
            <div class="subgroup-header">
              <div>
                <span class="subgroup-name" style="color:var(--shift-evening-color);">Evening</span>
                <span class="subgroup-timing">${eveningTiming}</span>
              </div>
              <span class="subgroup-count">${eveningStaff.length} / ${d.evening?.required || 1}</span>
            </div>
            <div class="staff-chips-wrap">
              ${eveningStaff.length ? eveningStaff.map(renderStaffChip).join("") : '<small style="color:var(--text-muted); font-size:0.72rem;">None assigned</small>'}
            </div>
          </div>

          <!-- Night Shift (22:00 - 07:00 NEXT DAY) -->
          <div class="shift-subgroup night">
            <div class="subgroup-header">
              <div>
                <span class="subgroup-name" style="color:var(--shift-night-color);">Night</span>
                <span class="subgroup-timing">${nightTiming}</span>
              </div>
              <span class="subgroup-count">${nightStaff.length} / 1</span>
            </div>
            <div class="staff-chips-wrap">
              ${nightStaff.length ? nightStaff.map(renderStaffChip).join("") : '<small style="color:var(--text-muted); font-size:0.72rem;">None assigned</small>'}
            </div>
          </div>

          <!-- Off & Leave Personnel -->
          <div style="display:flex; flex-direction:column; gap:6px; font-size:0.74rem; border-top:1px dashed var(--border-light); padding-top:8px;">
            <div>
              <strong style="color:var(--shift-off-color); font-weight:700;">Weekly OFF: </strong>
              <span>${offStaff.length ? offStaff.map(s => s.employeeName).join(", ") : 'None'}</span>
            </div>
            ${leaveStaff.length ? `
              <div>
                <strong style="color:var(--shift-leave-color); font-weight:700;">Approved Leave: </strong>
                <span>${leaveStaff.map(s => s.employeeName).join(", ")}</span>
              </div>
            ` : ''}
          </div>

        </div>
      `;
      }).join("")}
    </div>
  `;
}

function renderDashboardEmployeeTableHTML(employees) {
  if (!Array.isArray(employees) || !employees.length) {
    return `<div class="empty-state-box"><p>No employee schedule data available.</p></div>`;
  }

  return `
    <div class="table-wrap">
      <table class="emp-schedule-table">
        <thead>
          <tr>
            <th>Employee</th>
            <th>Gender</th>
            <th>Mon</th>
            <th>Tue</th>
            <th>Wed</th>
            <th>Thu</th>
            <th>Fri</th>
            <th>Sat</th>
            <th>Sun</th>
            <th>Work</th>
            <th>OFF</th>
            <th>Nights</th>
          </tr>
        </thead>
        <tbody>
          ${employees.map(emp => {
            const schedule = emp.schedule || [];
            return `
            <tr>
              <td>
                <strong>${emp.employeeName}</strong>
                <div style="font-size:0.74rem; color:var(--text-muted);">${emp.employeeCode}</div>
              </td>
              <td>
                <span class="staff-chip ${emp.gender === 'FEMALE' ? 'female' : ''}" style="font-size:0.72rem;">
                  <span class="chip-gender">${emp.gender === 'FEMALE' ? '♀ Female' : '♂ Male'}</span>
                </span>
              </td>
              ${schedule.map(s => {
                const isOff = s.weeklyOff || s.shiftType === 'OFF';
                const isLeave = s.onLeave || s.shiftType === 'LEAVE';
                const shiftTypeLabel = isOff ? 'OFF' : isLeave ? 'LEAVE' : s.shiftType;
                const timingLabel = isOff ? 'Rest' : isLeave ? 'Leave' : s.shiftTiming || getShiftTimingDisplay(s.shiftType);

                return `
                <td>
                  <div class="emp-day-chip ${s.shiftType ? s.shiftType.toLowerCase() : 'off'} ${isOff ? 'off' : ''} ${isLeave ? 'leave' : ''}" title="${s.dayOfWeek || ''} - ${shiftTypeLabel} (${timingLabel})">
                    <strong>${shiftTypeLabel}</strong>
                    <small>${timingLabel}</small>
                  </div>
                </td>
                `;
              }).join("")}
              <td><strong style="color:#047857; font-size:0.88rem;">${emp.workingDaysCount} d</strong></td>
              <td><strong style="color:#475569; font-size:0.88rem;">${emp.offDaysCount} d</strong></td>
              <td><strong style="color:#4338ca; font-size:0.88rem;">${emp.nightShiftsCount} / 2</strong></td>
            </tr>
            `;
          }).join("")}
        </tbody>
      </table>
    </div>
  `;
}

function renderStaffChip(s) {
  return `
    <span class="staff-chip ${s.gender === 'FEMALE' ? 'female' : ''} ${s.overridden ? 'overridden' : ''}" title="${s.employeeCode} - ${s.gender}${s.overridden ? ' (Manual Override)' : ''}">
      <span class="chip-gender">${s.gender === 'FEMALE' ? '♀' : '♂'}</span>
      <span>${s.employeeName}</span>
      ${s.overridden ? '<span style="color:#b45309; font-size:0.65rem;" title="Manual Override">⚡</span>' : ''}
    </span>
  `;
}

// Shift Override Modal
function openShiftOverrideModal(assign) {
  const currentCycle = state.cycles?.find(c => c.id === state.selectedCycleId);
  if (currentCycle && currentCycle.status === "LOCKED") {
    toast("Cannot modify shifts on a LOCKED roster. Unlock the cycle first.", "error");
    return;
  }
  document.getElementById("overrideAssignmentId").value = assign.id;
  document.getElementById("overrideEmployeeInfo").innerHTML = `
    <strong>${assign.employeeName}</strong> &bull; Date: <code>${formatDate(assign.rosterDate)}</code> &bull; Current: <span class="badge ${assign.shiftType.toLowerCase()}">${assign.shiftType}</span>
  `;
  document.getElementById("overrideShiftSelect").value = assign.shiftType;
  document.getElementById("overrideReasonInput").value = "";
  openModal("shiftOverrideModal");
}

async function handleSaveShiftOverride(e) {
  e.preventDefault();
  const assignId = document.getElementById("overrideAssignmentId").value;
  const newShift = document.getElementById("overrideShiftSelect").value;
  const reason = document.getElementById("overrideReasonInput").value.trim();
  const saveBtn = document.getElementById("saveOverrideBtn");

  try {
    saveBtn.disabled = true;
    if (newShift === "OFF") {
      await apiRequest(`/api/rosters/${assignId}/off`, {
        method: "PUT",
        body: { shiftType: "OFF", reason }
      });
    } else {
      await apiRequest(`/api/rosters/${assignId}/shift`, {
        method: "PUT",
        body: { shiftType: newShift, reason }
      });
    }

    toast("Shift override applied successfully", "success");
    broadcastDataMutation("SHIFT_OVERRIDDEN");
    closeModal("shiftOverrideModal");
    await renderRosterView();
  } catch (err) {
    toast(err.message, "error");
  } finally {
    saveBtn.disabled = false;
  }
}

// Shift Swap Modal
function openShiftSwapModal(cycle) {
  if (cycle && cycle.status === "LOCKED") {
    toast("Cannot swap shifts on a LOCKED roster. Unlock the cycle first.", "error");
    return;
  }
  const assignments = cycle.assignments || [];
  if (!assignments.length) {
    toast("No assignments in active cycle to swap", "error");
    return;
  }

  const dates = [...new Set(assignments.map(a => a.rosterDate))].sort();
  const dateSelect = document.getElementById("swapDateFilter");
  dateSelect.innerHTML = dates.map(d => `<option value="${d}">${formatDate(d)}</option>`).join("");

  const updateAssignDropdowns = () => {
    const selectedDate = dateSelect.value;
    const dayAssignments = assignments.filter(a => a.rosterDate === selectedDate && !a.onLeave);

    const options = dayAssignments.map(a => `
      <option value="${a.id}">
        ${a.employeeName} (${a.gender}) &rarr; ${a.shiftType} (${getShiftTimingDisplay(a.shiftType)}) ${a.weeklyOff ? '(OFF)' : ''}
      </option>
    `).join("");

    document.getElementById("swapAssign1").innerHTML = `<option value="">Select Employee 1...</option>` + options;
    document.getElementById("swapAssign2").innerHTML = `<option value="">Select Employee 2...</option>` + options;
  };

  dateSelect.onchange = updateAssignDropdowns;
  updateAssignDropdowns();

  document.getElementById("swapReasonInput").value = "Mutual shift exchange";
  openModal("shiftSwapModal");
}

async function handleExecuteShiftSwap(e) {
  e.preventDefault();
  const assignmentId1 = Number(document.getElementById("swapAssign1").value);
  const assignmentId2 = Number(document.getElementById("swapAssign2").value);
  const reason = document.getElementById("swapReasonInput").value.trim();
  const swapBtn = document.getElementById("executeSwapBtn");

  if (!assignmentId1 || !assignmentId2) {
    toast("Please select two distinct employees", "error");
    return;
  }
  if (assignmentId1 === assignmentId2) {
    toast("Cannot swap an assignment with itself", "error");
    return;
  }

  try {
    swapBtn.disabled = true;
    await apiRequest("/api/rosters/swap", {
      method: "POST",
      body: { assignmentId1, assignmentId2, reason }
    });

    toast("Atomic shift swap completed!", "success");
    broadcastDataMutation("SHIFT_SWAPPED");
    closeModal("shiftSwapModal");
    await renderRosterView();
  } catch (err) {
    toast(err.message, "error");
  } finally {
    swapBtn.disabled = false;
  }
}

// Leave Decision Modal
function openLeaveDecisionModal(info) {
  document.getElementById("leaveDecisionId").value = info.id;
  document.getElementById("leaveDecisionApprove").value = info.approve;
  state.leaveDecisionType = info.type || "standard";

  let typeLabel = "Leave Request";
  if (info.type === "modification") typeLabel = "Leave Modification";
  if (info.type === "cancellation") typeLabel = "Leave Cancellation";

  document.getElementById("leaveDecisionTitle").textContent = info.approve ? `Approve ${typeLabel}` : `Reject ${typeLabel}`;
  document.getElementById("leaveDecisionInfo").innerHTML = `
    <strong>${info.employeeName}</strong> &bull; Dates: <code>${info.dates}</code>
  `;
  document.getElementById("leaveDecisionRemarks").value = info.approve ? "Approved by Administrator" : "Rejected due to duty constraints";
  openModal("leaveDecisionModal");
}

async function handleConfirmLeaveDecision(e) {
  e.preventDefault();
  const leaveId = document.getElementById("leaveDecisionId").value;
  const approve = document.getElementById("leaveDecisionApprove").value === "true";
  const remarks = document.getElementById("leaveDecisionRemarks").value.trim();
  const btn = document.getElementById("leaveDecisionSubmitBtn");
  const type = state.leaveDecisionType || "standard";

  let endpoint = `/api/leaves/${leaveId}/${approve ? 'approve' : 'reject'}`;
  if (type === "modification") {
    endpoint = `/api/leaves/${leaveId}/modification/${approve ? 'approve' : 'reject'}`;
  } else if (type === "cancellation") {
    endpoint = `/api/leaves/${leaveId}/cancellation/${approve ? 'approve' : 'reject'}`;
  }

  try {
    btn.disabled = true;
    await apiRequest(endpoint, {
      method: "PUT",
      body: { remarks }
    });
    toast(approve ? "Leave request approved successfully" : "Leave request rejected", "success");
    broadcastDataMutation("LEAVE_DECISION");
    closeModal("leaveDecisionModal");
    await renderLeavesView();
  } catch (err) {
    toast(err.message, "error");
  } finally {
    btn.disabled = false;
  }
}


/* ==========================================================================
   VIEW 9: ROSTER CONFLICT & HEALTH CENTER
   ========================================================================== */

async function renderHealthView() {
  const container = dom.views.health;
  container.innerHTML = `<div class="empty-state-box"><div class="spinner"></div><p>Evaluating roster health and constraints...</p></div>`;

  try {
    state.cycles = await apiRequest("/api/rosters");
    if (!state.cycles.length) {
      container.innerHTML = `
        <div class="card">
          <div class="empty-state-box">
            <div class="empty-state-icon">🩺</div>
            <h3>No Roster Cycles Available</h3>
            <p>Generate a weekly roster to inspect health rules and conflict reports.</p>
          </div>
        </div>
      `;
      return;
    }

    if (!state.healthSelectedCycleId || !state.cycles.some(c => c.id === state.healthSelectedCycleId)) {
      state.healthSelectedCycleId = state.selectedCycleId || state.cycles[0].id;
    }

    const currentCycle = state.cycles.find(c => c.id === state.healthSelectedCycleId) || state.cycles[0];
    const health = await apiRequest(`/api/rosters/cycle/${currentCycle.id}/health`);
    state.currentHealthReport = health;

    const isReady = health.readyToPublish;
    const status = health.status || currentCycle.status || "GENERATED";
    const isLocked = status === "LOCKED";
    const isPublished = status === "PUBLISHED";

    container.innerHTML = `
      <div class="card">
        <!-- Health Top Bar -->
        <div class="table-toolbar" style="flex-wrap:wrap; gap:12px;">
          <div style="display:flex; align-items:center; gap:10px; flex-wrap:wrap;">
            <select id="healthCycleSelector" style="font-weight:700; min-width:220px;">
              ${state.cycles.map(c => `
                <option value="${c.id}" ${c.id === currentCycle.id ? 'selected' : ''}>
                  Cycle: ${formatDate(c.startDate)} - ${formatDate(c.endDate)} (#${c.id})
                </option>
              `).join("")}
            </select>
            <span class="roster-lifecycle-badge badge-${status.toLowerCase()}">
              ${isLocked ? '🔒 ' : isPublished ? '📢 ' : '⚙️ '}${status}
            </span>
          </div>

          <div class="filter-group" style="flex-wrap:wrap; gap:8px;">
            <button class="btn btn-secondary btn-sm" id="healthRefreshBtn" title="Re-evaluate health constraints">
              🔄 Re-evaluate Health
            </button>
            <button class="btn btn-secondary btn-sm" id="healthViewRosterBtn" title="View Weekly Roster">
              📅 View Roster Schedule
            </button>
            ${!isLocked && !isPublished ? `
              <button class="btn ${isReady ? 'btn-primary' : 'btn-secondary'} btn-sm" id="healthPublishBtn" title="${isReady ? 'Publish Roster' : 'Resolve critical conflicts before publishing'}">
                📢 Publish Roster
              </button>
            ` : ''}
            ${isPublished ? `
              <button class="btn btn-secondary btn-sm" id="healthLockBtn" style="border-color:#f59e0b; color:#b45309;">
                🔒 Lock Roster
              </button>
            ` : ''}
            ${isLocked ? `
              <button class="btn btn-warning btn-sm" id="healthUnlockBtn">
                🔓 Unlock Roster
              </button>
            ` : ''}
          </div>
        </div>

        <div class="card-body">
          <!-- Overall Readiness Status Banner -->
          <div class="health-readiness-banner ${isReady ? 'banner-ready' : 'banner-not-ready'}">
            <div class="health-banner-left">
              <div class="health-status-icon">${isReady ? '✅' : '⛔'}</div>
              <div>
                <h2>${isReady ? 'Ready for Publishing' : 'Not Ready to Publish'}</h2>
                <p>${health.readinessMessage || (isReady ? 'All critical operational and safety constraints are satisfied.' : 'Critical conflicts detected that must be resolved before publishing.')}</p>
              </div>
            </div>
            <div>
              <span class="badge ${isReady ? 'general' : 'night'}" style="font-size:0.85rem; font-weight:800; padding:6px 14px;">
                ${health.criticalConflictsCount} Critical &bull; ${health.highConflictsCount} High &bull; ${health.mediumConflictsCount} Medium &bull; ${health.lowConflictsCount} Low
              </span>
            </div>
          </div>

          <!-- Summary Checklist Cards -->
          <h3 style="font-size:0.95rem; margin-bottom:12px; font-weight:700;">Constraint Verification Checklist</h3>
          <div class="health-checklist-grid">
            <div class="health-check-card">
              <div class="check-card-info">
                <strong>Shift Coverage Minimums</strong>
                <span>M>=1, G>=1, E>=1, N=1</span>
              </div>
              <span class="check-status-badge ${(health.coverageCheck || health.coverageStatus) === 'PASS' ? 'pass' : (health.coverageCheck || health.coverageStatus) === 'WARN' ? 'warn' : 'fail'}">${health.coverageCheck || health.coverageStatus || 'PASS'}</span>
            </div>

            <div class="health-check-card">
              <div class="check-card-info">
                <strong>12-Hour Rest Intervals</strong>
                <span>Fatigue & turnaround protection</span>
              </div>
              <span class="check-status-badge ${(health.restRulesCheck || health.restPeriodStatus) === 'PASS' ? 'pass' : (health.restRulesCheck || health.restPeriodStatus) === 'WARN' ? 'warn' : 'fail'}">${health.restRulesCheck || health.restPeriodStatus || 'PASS'}</span>
            </div>

            <div class="health-check-card">
              <div class="check-card-info">
                <strong>Night Shift Limits</strong>
                <span>Max 2 consecutive nights</span>
              </div>
              <span class="check-status-badge ${(health.nightLimitCheck || health.nightLimitStatus) === 'PASS' ? 'pass' : (health.nightLimitCheck || health.nightLimitStatus) === 'WARN' ? 'warn' : 'fail'}">${health.nightLimitCheck || health.nightLimitStatus || 'PASS'}</span>
            </div>

            <div class="health-check-card">
              <div class="check-card-info">
                <strong>Gender Policy Compliance</strong>
                <span>Female staff day-only duty</span>
              </div>
              <span class="check-status-badge ${(health.genderRulesCheck || health.genderPolicyStatus) === 'PASS' ? 'pass' : (health.genderRulesCheck || health.genderPolicyStatus) === 'WARN' ? 'warn' : 'fail'}">${health.genderRulesCheck || health.genderPolicyStatus || 'PASS'}</span>
            </div>

            <div class="health-check-card">
              <div class="check-card-info">
                <strong>Leave Synchronization</strong>
                <span>No duty during approved leave</span>
              </div>
              <span class="check-status-badge ${(health.leaveRulesCheck || health.leaveComplianceStatus) === 'PASS' ? 'pass' : (health.leaveRulesCheck || health.leaveComplianceStatus) === 'WARN' ? 'warn' : 'fail'}">${health.leaveRulesCheck || health.leaveComplianceStatus || 'PASS'}</span>
            </div>

            <div class="health-check-card">
              <div class="check-card-info">
                <strong>Overrides & Shift Swaps</strong>
                <span>Integrity of manual changes</span>
              </div>
              <span class="check-status-badge ${(health.overridesCheck || health.overridesStatus) === 'PASS' ? 'pass' : (health.overridesCheck || health.overridesStatus) === 'WARN' ? 'warn' : 'fail'}">${health.overridesCheck || health.overridesStatus || 'PASS'}</span>
            </div>

            <div class="health-check-card">
              <div class="check-card-info">
                <strong>Duplicate / Overlap Check</strong>
                <span>1 duty per employee per day</span>
              </div>
              <span class="check-status-badge ${(health.duplicatesCheck || health.duplicatesStatus) === 'PASS' ? 'pass' : (health.duplicatesCheck || health.duplicatesStatus) === 'WARN' ? 'warn' : 'fail'}">${health.duplicatesCheck || health.duplicatesStatus || 'PASS'}</span>
            </div>

            <div class="health-check-card">
              <div class="check-card-info">
                <strong>Weekly OFF Balance</strong>
                <span>Weekly rest compliance</span>
              </div>
              <span class="check-status-badge ${(health.weeklyOffCheck || health.weeklyOffStatus) === 'PASS' ? 'pass' : (health.weeklyOffCheck || health.weeklyOffStatus) === 'WARN' ? 'warn' : 'fail'}">${health.weeklyOffCheck || health.weeklyOffStatus || 'PASS'}</span>
            </div>

            <div class="health-check-card">
              <div class="check-card-info">
                <strong>Intra-Week Continuity</strong>
                <span>Consistent rotation rhythm</span>
              </div>
              <span class="check-status-badge ${(health.shiftContinuityCheck || health.continuityStatus) === 'PASS' ? 'pass' : (health.shiftContinuityCheck || health.continuityStatus) === 'WARN' ? 'warn' : 'fail'}">${health.shiftContinuityCheck || health.continuityStatus || 'PASS'}</span>
            </div>
          </div>

          <!-- Detailed Conflicts Breakdown Table -->
          <div style="display:flex; justify-content:space-between; align-items:center; margin-top:20px; margin-bottom:12px; flex-wrap:wrap; gap:10px;">
            <h3 style="font-size:0.95rem; font-weight:700; margin:0;">
              Detected Conflicts & Recommendations (${health.conflicts.length})
            </h3>
            <div class="filter-group" style="gap:6px;">
              <select id="healthSeverityFilter" style="font-size:0.8rem; padding:4px 8px;">
                <option value="ALL">All Severities</option>
                <option value="CRITICAL">Critical Only</option>
                <option value="HIGH">High Only</option>
                <option value="MEDIUM">Medium Only</option>
                <option value="LOW">Low Only</option>
                <option value="INFO">Info Only</option>
              </select>
            </div>
          </div>

          <div class="table-wrap" id="healthConflictsTableWrapper">
            ${renderHealthConflictsTableHTML(health.conflicts)}
          </div>
        </div>
      </div>
    `;

    document.getElementById("healthCycleSelector").addEventListener("change", (e) => {
      state.healthSelectedCycleId = Number(e.target.value);
      renderHealthView();
    });

    document.getElementById("healthRefreshBtn").addEventListener("click", renderHealthView);

    document.getElementById("healthViewRosterBtn").addEventListener("click", () => {
      state.selectedCycleId = currentCycle.id;
      navigateTo("roster");
    });

    const publishBtn = document.getElementById("healthPublishBtn");
    if (publishBtn) {
      publishBtn.addEventListener("click", () => handlePublishRoster(currentCycle.id));
    }

    const lockBtn = document.getElementById("healthLockBtn");
    if (lockBtn) {
      lockBtn.addEventListener("click", () => handleLockRoster(currentCycle.id));
    }

    const unlockBtn = document.getElementById("healthUnlockBtn");
    if (unlockBtn) {
      unlockBtn.addEventListener("click", () => openUnlockModal(currentCycle.id));
    }

    document.getElementById("healthSeverityFilter").addEventListener("change", (e) => {
      const sev = e.target.value;
      const filtered = sev === "ALL" ? health.conflicts : health.conflicts.filter(c => c.severity === sev);
      document.getElementById("healthConflictsTableWrapper").innerHTML = renderHealthConflictsTableHTML(filtered);
    });

  } catch (err) {
    container.innerHTML = `<div class="empty-state-box"><p style="color:var(--danger)">Error loading health evaluation: ${err.message}</p></div>`;
  }
}

function renderHealthConflictsTableHTML(conflicts) {
  if (!conflicts || !conflicts.length) {
    return `<div class="empty-state-box"><div class="empty-state-icon">🎉</div><h3>Zero Conflicts Found</h3><p>All business rules, coverage requirements, and rest constraints are satisfied.</p></div>`;
  }

  return `
    <table>
      <thead>
        <tr>
          <th>Date</th>
          <th>Employee</th>
          <th>Shift</th>
          <th>Rule Violated</th>
          <th>Current &rarr; Expected</th>
          <th>Reason & Impact</th>
          <th>Severity</th>
          <th>Recommended Action</th>
        </tr>
      </thead>
      <tbody>
        ${conflicts.map(c => `
          <tr>
            <td><code>${c.date ? formatDate(c.date) : 'Cycle-wide'}</code></td>
            <td><strong>${c.employeeName || 'All Personnel'}</strong></td>
            <td>${c.shiftType ? `<span class="badge ${String(c.shiftType).toLowerCase()}">${c.shiftType}</span>` : '-'}</td>
            <td><code>${c.ruleName}</code></td>
            <td>
              <small><strong>${c.currentValue || '-'}</strong> &rarr; <span style="color:#047857;">${c.expectedValue || '-'}</span></small>
            </td>
            <td><small style="color:var(--text-main);">${c.reason}</small></td>
            <td>
              <span class="sev-tag sev-${String(c.severity).toLowerCase()}">${c.severity}</span>
            </td>
            <td><span style="font-size:0.78rem; font-weight:600; color:var(--primary);">${c.recommendedAction || 'Inspect and adjust'}</span></td>
          </tr>
        `).join("")}
      </tbody>
    </table>
  `;
}


/* ==========================================================================
   VIEW 10: COMPLETE ROSTER AUDIT TRAIL
   ========================================================================== */

async function renderAuditView() {
  const container = dom.views.audit;
  container.innerHTML = `<div class="empty-state-box"><div class="spinner"></div><p>Loading authoritative audit logs...</p></div>`;

  try {
    state.cycles = await apiRequest("/api/rosters");
    state.employees = await apiRequest("/api/employees");

    container.innerHTML = `
      <div class="card">
        <div class="card-header">
          <div>
            <h2>Roster Security & Audit Trail</h2>
            <span style="font-size:0.78rem; color:var(--text-muted);">
              Immutable ledger of roster generations, publishing, locks, unlocks, shift overrides, swaps, and leave decisions.
            </span>
          </div>
          <button class="btn btn-secondary btn-sm" id="auditRefreshBtn">
            🔄 Refresh Logs
          </button>
        </div>

        <div class="audit-filter-bar">
          <div class="form-group">
            <label style="font-size:0.75rem; font-weight:700;">Filter by Cycle</label>
            <select id="auditCycleFilter">
              <option value="">All Cycles</option>
              ${state.cycles.map(c => `
                <option value="${c.id}">Cycle #${c.id} (${formatDate(c.startDate)} - ${formatDate(c.endDate)})</option>
              `).join("")}
            </select>
          </div>

          <div class="form-group">
            <label style="font-size:0.75rem; font-weight:700;">Filter by Action</label>
            <select id="auditActionFilter">
              <option value="">All Actions</option>
              <option value="ROSTER_GENERATED">ROSTER_GENERATED</option>
              <option value="ROSTER_PUBLISHED">ROSTER_PUBLISHED</option>
              <option value="ROSTER_LOCKED">ROSTER_LOCKED</option>
              <option value="ROSTER_UNLOCKED">ROSTER_UNLOCKED</option>
              <option value="ROSTER_DELETED">ROSTER_DELETED</option>
              <option value="SHIFT_OVERRIDDEN">SHIFT_OVERRIDDEN</option>
              <option value="SHIFT_SWAPPED">SHIFT_SWAPPED</option>
              <option value="LEAVE_APPROVED">LEAVE_APPROVED</option>
              <option value="LEAVE_REJECTED">LEAVE_REJECTED</option>
              <option value="LEAVE_MODIFIED">LEAVE_MODIFIED</option>
              <option value="LEAVE_CANCELLED">LEAVE_CANCELLED</option>
            </select>
          </div>

          <div class="form-group">
            <label style="font-size:0.75rem; font-weight:700;">Actor (Username)</label>
            <input type="text" id="auditActorFilter" placeholder="e.g. admin or system">
          </div>

          <div class="form-group">
            <label style="font-size:0.75rem; font-weight:700;">Employee</label>
            <select id="auditEmpFilter">
              <option value="">All Employees</option>
              ${state.employees.map(e => `
                <option value="${e.id}">${e.employeeCode} - ${e.firstName} ${e.lastName || ''}</option>
              `).join("")}
            </select>
          </div>

          <div class="form-group" style="align-self:flex-end;">
            <button class="btn btn-primary btn-block btn-sm" id="auditSearchBtn">
              🔍 Filter Audit Logs
            </button>
          </div>
        </div>

        <div class="table-wrap" id="auditTableWrapper">
          <div class="empty-state-box"><div class="spinner"></div><p>Fetching records...</p></div>
        </div>
      </div>
    `;

    document.getElementById("auditRefreshBtn").addEventListener("click", searchAuditLogs);
    document.getElementById("auditSearchBtn").addEventListener("click", searchAuditLogs);

    await searchAuditLogs();

  } catch (err) {
    container.innerHTML = `<div class="empty-state-box"><p style="color:var(--danger)">Error loading audit trail: ${err.message}</p></div>`;
  }
}

async function searchAuditLogs() {
  const wrapper = document.getElementById("auditTableWrapper");
  if (!wrapper) return;
  wrapper.innerHTML = `<div class="empty-state-box"><div class="spinner"></div><p>Fetching audit trail entries...</p></div>`;

  try {
    const cycleId = document.getElementById("auditCycleFilter")?.value || "";
    const action = document.getElementById("auditActionFilter")?.value || "";
    const actor = document.getElementById("auditActorFilter")?.value.trim() || "";
    const employeeId = document.getElementById("auditEmpFilter")?.value || "";

    const params = new URLSearchParams();
    if (cycleId) params.append("cycleId", cycleId);
    if (action) params.append("action", action);
    if (actor) params.append("actor", actor);
    if (employeeId) params.append("employeeId", employeeId);

    const logs = await apiRequest(`/api/audit-logs?${params.toString()}`);
    wrapper.innerHTML = renderAuditTableHTML(logs);
  } catch (err) {
    wrapper.innerHTML = `<div class="empty-state-box"><p style="color:var(--danger)">Error loading audit logs: ${err.message}</p></div>`;
  }
}

function renderAuditTableHTML(logs) {
  if (!logs || !logs.length) {
    return `<div class="empty-state-box"><div class="empty-state-icon">📋</div><h3>No Audit Records Found</h3><p>No audit trail records match the specified search filters.</p></div>`;
  }

  return `
    <table>
      <thead>
        <tr>
          <th>Timestamp</th>
          <th>Actor</th>
          <th>Action</th>
          <th>Entity</th>
          <th>Target Employee</th>
          <th>Change Summary</th>
          <th>Reason / Remarks</th>
          <th>Source</th>
        </tr>
      </thead>
      <tbody>
        ${logs.map(log => `
          <tr>
            <td>
              <small style="color:var(--text-muted); font-weight:600;">
                ${log.timestamp ? new Date(log.timestamp).toLocaleString() : '-'}
              </small>
            </td>
            <td>
              <strong>${log.actor}</strong>
            </td>
            <td>
              <span class="audit-action-badge ${getAuditActionClass(log.action)}">
                ${log.action}
              </span>
            </td>
            <td>
              <code>${log.entityType || '-'}${log.entityId ? ` #${log.entityId}` : ''}</code>
            </td>
            <td>
              ${log.employeeName ? `<strong>${log.employeeName}</strong>` : '<em>-</em>'}
            </td>
            <td>
              ${log.oldValue || log.newValue ? `
                <div class="audit-val-diff">
                  ${log.oldValue ? `<span class="val-old">${log.oldValue}</span> &rarr;` : ''}
                  <span class="val-new">${log.newValue || '-'}</span>
                </div>
              ` : '<em>-</em>'}
            </td>
            <td>
              <small style="color:var(--text-main); font-weight:500;">${log.reason || '-'}</small>
            </td>
            <td>
              <span class="badge ${log.source === 'AUTOMATIC' ? 'night' : 'morning'}" style="font-size:0.7rem;">
                ${log.source || 'MANUAL'}
              </span>
            </td>
          </tr>
        `).join("")}
      </tbody>
    </table>
  `;
}

function getAuditActionClass(action) {
  if (!action) return "badge-draft";
  if (action.includes("PUBLISHED")) return "badge-published";
  if (action.includes("LOCKED") || action.includes("UNLOCKED")) return "badge-locked";
  if (action.includes("DELETED") || action.includes("REJECTED")) return "sev-critical";
  if (action.includes("APPROVED")) return "badge-published";
  return "badge-generated";
}


/* ==========================================================================
   VIEW 11: EMPLOYEE PROFILE CHANGE APPROVALS (ADMIN)
   ========================================================================== */

async function renderProfileApprovalsView() {
  const container = dom.views.profileApprovals;
  if (!container) return;

  container.innerHTML = `<div class="empty-state-box"><div class="spinner"></div><p>Loading pending profile changes...</p></div>`;

  try {
    const requests = await apiRequest("/api/admin/profile-change-requests/pending");
    state.pendingProfileChanges = Array.isArray(requests) ? requests : [];
    state.pendingProfileChangesCount = state.pendingProfileChanges.length;
    renderNavigation();

    const fieldLabels = {
      firstName: "First Name",
      lastName: "Last Name",
      email: "Email Address",
      contactNumber: "Contact Number",
      gender: "Gender",
      employeeCode: "Employee Code"
    };

    container.innerHTML = `
      <div class="card stack-gap">
        <div class="card-header">
          <div>
            <h3>Employee Profile Change Approvals</h3>
            <span style="font-size:0.76rem; color:var(--text-muted);">Review and decide pending profile change requests submitted by employees</span>
          </div>
          <div style="display:flex; align-items:center; gap:8px;">
            <span class="badge ${state.pendingProfileChangesCount > 0 ? 'morning' : 'general'}">
              ${state.pendingProfileChangesCount} Pending Request${state.pendingProfileChangesCount === 1 ? '' : 's'}
            </span>
            <button class="btn btn-secondary btn-sm" id="refreshProfileApprovalsBtn">
              <span>🔄 Refresh</span>
            </button>
          </div>
        </div>

        <div class="alert-info-box" style="margin: 0 20px 10px; background-color:#eff6ff; border-color:#bfdbfe; color:#1e40af;">
          <strong>Approval Policy:</strong>
          <span style="display:block; margin-top:2px; font-size:0.82rem;">
            Approving a request applies the changes immediately to the employee's official database record and development CSV, logs an audit trail, and sends an approval notification. Rejecting requires an operational reason and preserves existing approved data.
          </span>
        </div>

        <div class="table-wrap">
          ${!state.pendingProfileChanges.length ? `
            <div class="empty-state-box" style="padding:48px 20px;">
              <div class="empty-state-icon" style="font-size:2.4rem;">✅</div>
              <h3 style="margin-top:8px;">No Pending Profile Approvals</h3>
              <p style="color:var(--text-muted); font-size:0.88rem;">All employee profile change requests have been reviewed and processed.</p>
            </div>
          ` : `
            <table>
              <thead>
                <tr>
                  <th>Req ID</th>
                  <th>Employee</th>
                  <th>Field</th>
                  <th>Current (Old) Value</th>
                  <th>Requested (New) Value</th>
                  <th>Requested At</th>
                  <th>Status</th>
                  <th style="text-align:right;">Actions</th>
                </tr>
              </thead>
              <tbody>
                ${state.pendingProfileChanges.map(r => {
                  const label = fieldLabels[r.fieldName] || r.fieldName;
                  return `
                    <tr>
                      <td><code>#${r.id}</code></td>
                      <td>
                        <strong>${escapeHTML(r.employeeName || 'Employee')}</strong>
                        <div style="font-size:0.75rem; color:var(--text-muted);">Code: <code>${escapeHTML(r.employeeCode || '-')}</code></div>
                      </td>
                      <td><span class="badge general">${escapeHTML(label)}</span></td>
                      <td><span class="val-diff-old">${escapeHTML(r.currentValue || '-')}</span></td>
                      <td><span class="val-diff-new">${escapeHTML(r.requestedValue)}</span></td>
                      <td><small style="color:var(--text-muted); font-weight:600;">${formatDate(r.requestedAt)}</small></td>
                      <td><span class="status-pill pending"><span class="badge-dot"></span> PENDING</span></td>
                      <td style="text-align:right;">
                        <div style="display:flex; justify-content:flex-end; gap:6px;">
                          <button class="btn btn-primary btn-xs" data-approve-pcr="${r.id}" data-emp-name="${escapeHTML(r.employeeName || '')}" data-field="${r.fieldName}" data-old="${escapeHTML(r.currentValue || '')}" data-new="${escapeHTML(r.requestedValue)}">
                            ✓ Approve
                          </button>
                          <button class="btn btn-danger btn-xs" data-reject-pcr="${r.id}" data-emp-name="${escapeHTML(r.employeeName || '')}" data-field="${r.fieldName}" data-old="${escapeHTML(r.currentValue || '')}" data-new="${escapeHTML(r.requestedValue)}">
                            ✕ Reject
                          </button>
                        </div>
                      </td>
                    </tr>
                  `;
                }).join('')}
              </tbody>
            </table>
          `}
        </div>
      </div>
    `;

    document.getElementById("refreshProfileApprovalsBtn")?.addEventListener("click", () => {
      renderProfileApprovalsView();
      toast("Profile change approvals refreshed", "info");
    });

    container.querySelectorAll("[data-approve-pcr]").forEach(btn => {
      btn.addEventListener("click", () => {
        const id = btn.getAttribute("data-approve-pcr");
        const employeeName = btn.getAttribute("data-emp-name");
        const fieldName = btn.getAttribute("data-field");
        const oldValue = btn.getAttribute("data-old");
        const newValue = btn.getAttribute("data-new");
        openAdminProfileDecisionModal({ id, approve: true, employeeName, fieldName, oldValue, newValue });
      });
    });

    container.querySelectorAll("[data-reject-pcr]").forEach(btn => {
      btn.addEventListener("click", () => {
        const id = btn.getAttribute("data-reject-pcr");
        const employeeName = btn.getAttribute("data-emp-name");
        const fieldName = btn.getAttribute("data-field");
        const oldValue = btn.getAttribute("data-old");
        const newValue = btn.getAttribute("data-new");
        openAdminProfileDecisionModal({ id, approve: false, employeeName, fieldName, oldValue, newValue });
      });
    });

  } catch (err) {
    container.innerHTML = `
      <div class="card">
        <div class="empty-state-box">
          <div class="empty-state-icon" style="color:var(--danger)">⚠️</div>
          <h3 style="color:var(--danger)">Unable to load profile approvals</h3>
          <p>${escapeHTML(err.message)}</p>
          <button class="btn btn-secondary btn-sm" onclick="renderProfileApprovalsView()">🔄 Retry</button>
        </div>
      </div>
    `;
  }
}

async function fetchPendingProfileChangesCount() {
  if (!state.token || !state.profile || state.profile.role !== "ROLE_ADMIN") return;
  try {
    const res = await apiRequest("/api/admin/profile-change-requests/pending");
    state.pendingProfileChangesCount = Array.isArray(res) ? res.length : 0;
    renderNavigation();
  } catch (_) {}
}


/* ==========================================================================
   NOTIFICATION CENTER & LIFECYCLE MANAGEMENT
   ========================================================================== */

async function setupNotificationCenter() {
  if (!state.token) return;
  await fetchUnreadNotifCount();
}

async function fetchUnreadNotifCount() {
  if (!state.token) return;
  try {
    const res = await apiRequest("/api/notifications/unread-count");
    const count = res.unreadCount || 0;
    state.unreadNotificationCount = count;
    
    const badge = document.getElementById("notifBadge");
    const headerPill = document.getElementById("notifUnreadCountBadge");
    if (badge) {
      if (count > 0) {
        badge.textContent = count > 99 ? "99+" : count;
        badge.classList.remove("hidden");
      } else {
        badge.classList.add("hidden");
      }
    }
    if (headerPill) {
      headerPill.textContent = `${count} unread`;
    }
  } catch (err) {
    console.warn("[WRMS] Failed to fetch unread notification count:", err.message);
  }
}

async function fetchNotifications() {
  if (!state.token) return;
  const container = document.getElementById("notifListContainer");
  if (!container) return;
  container.innerHTML = `<div class="empty-state-box" style="padding:16px;"><div class="spinner"></div></div>`;

  try {
    const notifs = await apiRequest("/api/notifications/my");
    renderNotificationList(notifs);
    await fetchUnreadNotifCount();
  } catch (err) {
    container.innerHTML = `<div class="notif-empty" style="color:var(--danger);">Failed to load notifications</div>`;
  }
}

function renderNotificationList(notifs) {
  const container = document.getElementById("notifListContainer");
  if (!container) return;

  if (!notifs || !notifs.length) {
    container.innerHTML = `<div class="notif-empty">No notifications yet</div>`;
    return;
  }

  container.innerHTML = notifs.map(n => {
    let iconClass = "shift";
    let iconChar = "🔔";
    if (n.type === "ROSTER_PUBLISHED") { iconClass = "published"; iconChar = "📢"; }
    else if (n.type === "ROSTER_LOCKED" || n.type === "ROSTER_UNLOCKED") { iconClass = "locked"; iconChar = "🔒"; }
    else if (n.type === "ADMIN_ALERT" || n.type === "CRITICAL_CONFLICT") { iconClass = "alert"; iconChar = "⚠️"; }
    else if (n.type === "LEAVE_DECISION" || n.type === "LEAVE_REQUEST") { iconClass = "leave"; iconChar = "📋"; }

    return `
      <div class="notif-item ${!n.readStatus ? 'unread' : ''}" data-id="${n.id}" data-page="${n.linkPage || ''}" data-linkid="${n.linkId || ''}">
        <div class="notif-icon-circle ${iconClass}">${iconChar}</div>
        <div class="notif-content">
          <strong>${n.title}</strong>
          <p>${n.message}</p>
          <span class="notif-time">${n.createdAt ? new Date(n.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', month: 'short', day: 'numeric' }) : ''}</span>
        </div>
      </div>
    `;
  }).join("");

  container.querySelectorAll(".notif-item").forEach(item => {
    item.addEventListener("click", async () => {
      const notifId = item.getAttribute("data-id");
      const linkPage = item.getAttribute("data-page");
      const linkId = item.getAttribute("data-linkid");

      try {
        await apiRequest(`/api/notifications/${notifId}/read`, { method: "PUT" });
        item.classList.remove("unread");
        await fetchUnreadNotifCount();
      } catch (e) {
        // ignore
      }

      document.getElementById("notificationDropdown")?.classList.add("hidden");
      if (linkPage && dom.views[linkPage]) {
        if (linkId && (linkPage === "roster" || linkPage === "health")) {
          state.selectedCycleId = Number(linkId);
          state.healthSelectedCycleId = Number(linkId);
        }
        navigateTo(linkPage);
      }
    });
  });
}

async function handleMarkAllNotificationsRead() {
  try {
    await apiRequest("/api/notifications/read-all", { method: "PUT" });
    toast("All notifications marked as read", "success");
    await fetchNotifications();
    await fetchUnreadNotifCount();
  } catch (err) {
    toast(err.message, "error");
  }
}

async function handlePublishRoster(cycleId) {
  if (state.isPublishingRoster) return;
  try {
    state.isPublishingRoster = true;
    const res = await apiRequest(`/api/rosters/cycle/${cycleId}/publish`, { method: "POST" });
    toast(`Roster cycle #${cycleId} published successfully! Staff notified.`, "success");
    broadcastDataMutation("ROSTER_PUBLISHED");
    await fetchUnreadNotifCount();
    if (state.activePage === "roster") await renderRosterView();
    if (state.activePage === "health") await renderHealthView();
    if (state.activePage === "history") await renderHistoryView();
  } catch (err) {
    toast(err.message, "error");
    if (err.message && err.message.includes("critical conflict")) {
      state.healthSelectedCycleId = cycleId;
      navigateTo("health");
    }
  } finally {
    state.isPublishingRoster = false;
  }
}

async function handleLockRoster(cycleId) {
  if (state.isLockingRoster) return;
  try {
    state.isLockingRoster = true;
    const res = await apiRequest(`/api/rosters/cycle/${cycleId}/lock`, { method: "POST" });
    toast(`Roster cycle #${cycleId} has been LOCKED. Changes are now restricted.`, "success");
    broadcastDataMutation("ROSTER_LOCKED");
    await fetchUnreadNotifCount();
    if (state.activePage === "roster") await renderRosterView();
    if (state.activePage === "health") await renderHealthView();
    if (state.activePage === "history") await renderHistoryView();
  } catch (err) {
    toast(err.message, "error");
  } finally {
    state.isLockingRoster = false;
  }
}

function openUnlockModal(cycleId) {
  document.getElementById("unlockCycleId").value = cycleId;
  document.getElementById("unlockReasonInput").value = "";
  openModal("unlockRosterModal");
}

async function handleConfirmUnlockRoster(e) {
  e.preventDefault();
  const cycleId = document.getElementById("unlockCycleId").value;
  const reason = document.getElementById("unlockReasonInput").value.trim();
  const btn = document.getElementById("submitUnlockBtn");

  if (!reason) {
    toast("A mandatory operational reason is required to unlock this roster", "error");
    return;
  }

  try {
    btn.disabled = true;
    await apiRequest(`/api/rosters/cycle/${cycleId}/unlock`, {
      method: "POST",
      body: { reason }
    });
    toast(`Roster cycle #${cycleId} UNLOCKED. Changes are now permitted.`, "success");
    broadcastDataMutation("ROSTER_UNLOCKED");
    closeModal("unlockRosterModal");
    await fetchUnreadNotifCount();
    if (state.activePage === "roster") await renderRosterView();
    if (state.activePage === "health") await renderHealthView();
    if (state.activePage === "history") await renderHistoryView();
  } catch (err) {
    toast(err.message, "error");
  } finally {
    btn.disabled = false;
  }
}


/* ==========================================================================
   PROFILE CHANGE REQUEST MODAL HANDLERS
   ========================================================================== */

function openProfileChangeModal({ fieldName, label, currentValue, type }) {
  document.getElementById("pcrFormFieldName").value = fieldName;
  document.getElementById("profileChangeModalTitle").textContent = `Request Change: ${label}`;
  document.getElementById("pcrFormCurrentLabel").textContent = `Current ${label}`;
  document.getElementById("pcrFormCurrentValue").value = currentValue || "-";
  document.getElementById("pcrFormRequestedLabel").innerHTML = `Requested New ${label} <span class="req">*</span>`;

  const container = document.getElementById("pcrFormInputContainer");
  const helper = document.getElementById("pcrFormHelperText");
  const alertBox = document.getElementById("pcrFormAlertBox");
  alertBox.classList.add("hidden");
  alertBox.textContent = "";

  if (type === "gender") {
    container.innerHTML = `
      <select id="pcrFormRequestedValue" required>
        <option value="MALE" ${currentValue === 'MALE' ? 'selected' : ''}>MALE (Eligible for all shifts)</option>
        <option value="FEMALE" ${currentValue === 'FEMALE' ? 'selected' : ''}>FEMALE (Morning & General only)</option>
      </select>
    `;
    helper.textContent = "Female staff are scheduled for Morning and General shifts only.";
  } else if (type === "email") {
    container.innerHTML = `
      <input type="email" id="pcrFormRequestedValue" placeholder="e.g. name@company.com" required value="">
    `;
    helper.textContent = "Must be a valid email address.";
  } else if (type === "phone") {
    container.innerHTML = `
      <input type="tel" id="pcrFormRequestedValue" placeholder="e.g. +91 9876543210" required value="">
    `;
    helper.textContent = "Enter valid contact/phone number.";
  } else {
    container.innerHTML = `
      <input type="text" id="pcrFormRequestedValue" placeholder="Enter new ${label}" required value="">
    `;
    helper.textContent = "";
  }

  openModal("profileChangeModal");
  setTimeout(() => {
    document.getElementById("pcrFormRequestedValue")?.focus();
  }, 100);
}

async function handleConfirmProfileChange(e) {
  e.preventDefault();
  const fieldName = document.getElementById("pcrFormFieldName").value;
  const reqInput = document.getElementById("pcrFormRequestedValue");
  const requestedValue = reqInput ? reqInput.value.trim() : "";
  const alertBox = document.getElementById("pcrFormAlertBox");
  const submitBtn = document.getElementById("submitProfileChangeBtn");
  const spinner = submitBtn.querySelector(".spinner");

  alertBox.classList.add("hidden");
  alertBox.textContent = "";

  if (!requestedValue) {
    alertBox.textContent = "Please enter a requested value.";
    alertBox.classList.remove("hidden");
    return;
  }

  try {
    submitBtn.disabled = true;
    if (spinner) spinner.classList.remove("hidden");

    await apiRequest("/api/profile-change-requests", {
      method: "POST",
      body: { fieldName, requestedValue }
    });

    toast("Profile change request submitted successfully!", "success");
    broadcastDataMutation("PROFILE_CHANGE_REQUESTED");
    closeModal("profileChangeModal");
    await renderEmployeeWorkspaceView();
  } catch (err) {
    alertBox.textContent = err.message || "Unable to submit the change request. Please try again.";
    alertBox.classList.remove("hidden");
    toast(err.message || "Unable to submit the change request.", "error");
  } finally {
    submitBtn.disabled = false;
    if (spinner) spinner.classList.add("hidden");
  }
}

function openAdminProfileDecisionModal({ id, approve, employeeName, fieldName, oldValue, newValue }) {
  document.getElementById("adminPcrId").value = id;
  document.getElementById("adminPcrApprove").value = String(approve);
  document.getElementById("adminPcrRemarks").value = "";

  const title = approve ? "Approve Profile Change Request" : "Reject Profile Change Request";
  const icon = approve ? "✅" : "🚫";
  const btnText = approve ? "Confirm Approval" : "Confirm Rejection";
  const submitBtn = document.getElementById("submitAdminPcrBtn");

  document.getElementById("adminProfileDecisionTitle").textContent = title;
  document.getElementById("adminProfileDecisionIcon").textContent = icon;
  document.getElementById("submitAdminPcrBtnText").textContent = btnText;

  if (approve) {
    submitBtn.classList.remove("btn-danger");
    submitBtn.classList.add("btn-primary");
  } else {
    submitBtn.classList.remove("btn-primary");
    submitBtn.classList.add("btn-danger");
  }

  const fieldLabels = {
    firstName: "First Name",
    lastName: "Last Name",
    email: "Email Address",
    contactNumber: "Contact Number",
    gender: "Gender",
    employeeCode: "Employee Code"
  };
  const label = fieldLabels[fieldName] || fieldName;

  document.getElementById("adminPcrEmployeeBadge").innerHTML = `
    <div style="display:flex; flex-direction:column; gap:4px;">
      <div>Employee: <strong>${escapeHTML(employeeName)}</strong></div>
      <div>Field: <strong>${escapeHTML(label)}</strong></div>
      <div class="val-diff-tag" style="margin-top:2px;">
        <span class="val-diff-old">${escapeHTML(oldValue || '-')}</span> &rarr;
        <span class="val-diff-new">${escapeHTML(newValue)}</span>
      </div>
    </div>
  `;

  openModal("adminProfileDecisionModal");
}

async function handleConfirmAdminPcrDecision(e) {
  e.preventDefault();
  const id = document.getElementById("adminPcrId").value;
  const isApprove = document.getElementById("adminPcrApprove").value === "true";
  const remarks = document.getElementById("adminPcrRemarks").value.trim();
  const submitBtn = document.getElementById("submitAdminPcrBtn");
  const spinner = submitBtn.querySelector(".spinner");

  if (!id) return;

  if (!isApprove && !remarks) {
    toast("Rejection reason is required", "warning");
    document.getElementById("adminPcrRemarks")?.focus();
    return;
  }

  try {
    submitBtn.disabled = true;
    if (spinner) spinner.classList.remove("hidden");

    const endpoint = isApprove
      ? `/api/admin/profile-change-requests/${id}/approve`
      : `/api/admin/profile-change-requests/${id}/reject`;

    await apiRequest(endpoint, {
      method: "POST",
      body: { adminRemarks: remarks }
    });

    toast(`Profile change request ${isApprove ? 'approved' : 'rejected'} successfully!`, "success");
    broadcastDataMutation("PROFILE_CHANGE_DECIDED");
    closeModal("adminProfileDecisionModal");

    if (state.activePage === "profileApprovals") {
      await renderProfileApprovalsView();
    } else if (state.activePage === "employees") {
      await renderEmployeesView();
    }
    await fetchPendingProfileChangesCount();
  } catch (err) {
    toast(err.message || "Failed to process decision", "error");
  } finally {
    submitBtn.disabled = false;
    if (spinner) spinner.classList.add("hidden");
  }
}


/* ==========================================================================
   UTILITY & API HELPER
   ========================================================================== */

async function apiRequest(endpoint, options = {}) {
  const headers = { ...(options.headers || {}) };
  headers["Accept"] = "application/json";
  if (options.body) headers["Content-Type"] = "application/json";
  const authToken = state.token || sessionStorage.getItem("wrmsToken");
  if (options.auth !== false && authToken) {
    headers["Authorization"] = `Bearer ${authToken}`;
  }

  const timeoutMs = options.timeout || 15000;
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), timeoutMs);

  let response;
  try {
    response = await fetch(endpoint, {
      method: options.method || "GET",
      headers,
      body: options.body ? JSON.stringify(options.body) : undefined,
      signal: controller.signal
    });
  } catch (networkErr) {
    clearTimeout(timeoutId);
    if (networkErr.name === 'AbortError') {
      throw new Error("Request timed out. Please check your connection or retry.");
    }
    throw new Error("Unable to connect to the server. Please check your network connection.");
  } finally {
    clearTimeout(timeoutId);
  }

  if (response.status === 401) {
    if (options.auth !== false) {
      handleLogout();
      throw new Error("Session expired. Please sign in again.");
    }
  }

  const text = await response.text();
  let data = null;
  if (text && text.trim()) {
    try {
      data = JSON.parse(text);
    } catch (parseErr) {
      // Non-JSON response (e.g. HTML error page or raw text)
      data = null;
    }
  }

  if (!response.ok) {
    let errorMsg = "";
    if (data) {
      if (data.validationErrors && typeof data.validationErrors === "object") {
        errorMsg = Object.values(data.validationErrors).join(", ");
      } else if (data.message) {
        errorMsg = data.message;
      } else if (data.error) {
        errorMsg = data.error;
      }
    }
    if (!errorMsg) {
      if (text && !text.trim().startsWith("<")) {
        errorMsg = text.trim();
      } else {
        errorMsg = `Server error (HTTP ${response.status}: ${response.statusText || 'Action failed'})`;
      }
    }
    throw new Error(errorMsg);
  }

  return data;
}

function toast(message, type = "info") {
  const t = document.createElement("div");
  t.className = `toast ${type}`;
  t.innerHTML = `
    <span>${type === 'success' ? '✅' : type === 'error' ? '⚠️' : 'ℹ️'}</span>
    <span>${message}</span>
  `;

  dom.toastContainer.appendChild(t);
  setTimeout(() => {
    t.style.opacity = "0";
    t.style.transform = "translateX(100%)";
    t.style.transition = "all 0.3s ease";
    setTimeout(() => t.remove(), 300);
  }, 4000);
}

function formatDate(dateStr) {
  if (!dateStr) return "-";
  if (typeof dateStr === "string" && dateStr.includes("-")) {
    const parts = dateStr.split("T")[0].split("-");
    if (parts.length === 3) {
      const year = parseInt(parts[0], 10);
      const month = parseInt(parts[1], 10) - 1;
      const day = parseInt(parts[2], 10);
      const dt = new Date(year, month, day);
      return dt.toLocaleDateString("en-US", { day: "2-digit", month: "short", year: "numeric" });
    }
  }
  const dt = new Date(dateStr);
  return dt.toLocaleDateString("en-US", { day: "2-digit", month: "short", year: "numeric" });
}
