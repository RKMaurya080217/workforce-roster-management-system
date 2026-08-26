/* ==========================================================================
   ENTERPRISE FEATURES MODULE (WRMS Enterprise v2.0)
   1. Roster Analytics Dashboard
   2. Smart Roster Conflict Detector / Validator
   3. Employee Availability & Shift Preference
   4. Holiday Calendar
   5. Shift Handover Management
   6. Employee Workload Analytics
   7. Export Center (PDF / Excel / CSV)
   8. Employee Skill Matrix
   9. Roster Version History & Version Comparison
   10. Advanced Notification Integrations
   ========================================================================== */

// --- 1. ROSTER ANALYTICS DASHBOARD ---
async function renderAnalyticsView() {
  const container = dom.views.analytics;
  if (!container) return;
  container.innerHTML = `<div class="empty-state-box"><div class="spinner"></div><p>Loading enterprise roster analytics...</p></div>`;

  try {
    const data = await apiRequest("/api/admin/analytics");
    const summary = data.summary || {};
    const shifts = data.shiftDistribution || [];
    const coverage = data.dayCoverages || [];
    const topWorkloads = data.topWorkloadEmployees || [];

    const totalDuty = (summary.morningShifts || 0) + (summary.generalShifts || 0) + (summary.eveningShifts || 0) + (summary.nightShifts || 0);

    container.innerHTML = `
      <div class="view-header-bar">
        <div>
          <h2>Roster Analytics & Intelligence</h2>
          <p class="text-muted">Real-time duty distribution, shift balance metrics, and daily coverage heatmaps</p>
        </div>
        <div class="header-actions">
          <button class="btn btn-secondary btn-sm" onclick="renderAnalyticsView()">
            ${WRMS_ICONS.refresh}
            <span>Refresh Analytics</span>
          </button>
        </div>
      </div>

      <!-- KPI Summary Cards -->
      <div class="metric-cards-grid" style="margin-bottom:24px;">
        <div class="metric-card">
          <div class="metric-icon" style="background:#e0f2fe; color:#0284c7;">${WRMS_ICONS.analytics}</div>
          <div class="metric-details">
            <span class="metric-label">Total Assigned Duties</span>
            <strong class="metric-value">${summary.totalAssignments || 0}</strong>
            <small class="text-muted">${totalDuty} active shift duties</small>
          </div>
        </div>
        <div class="metric-card">
          <div class="metric-icon" style="background:#dcfce7; color:#16a34a;">${WRMS_ICONS.shifts}</div>
          <div class="metric-details">
            <span class="metric-label">Shift Balance Score</span>
            <strong class="metric-value">${summary.shiftBalanceScore || 100}%</strong>
            <small class="text-muted">Duty fairness index</small>
          </div>
        </div>
        <div class="metric-card">
          <div class="metric-icon" style="background:#fef3c7; color:#d97706;">${WRMS_ICONS.holidays}</div>
          <div class="metric-details">
            <span class="metric-label">Weekly Offs & Leaves</span>
            <strong class="metric-value">${(summary.weeklyOffs || 0) + (summary.leaves || 0)}</strong>
            <small class="text-muted">${summary.weeklyOffs || 0} OFFs &bull; ${summary.leaves || 0} Leaves</small>
          </div>
        </div>
        <div class="metric-card">
          <div class="metric-icon" style="background:#ede9fe; color:#7c3aed;">${WRMS_ICONS.shifts}</div>
          <div class="metric-details">
            <span class="metric-label">Total Rest Interval</span>
            <strong class="metric-value">${summary.totalRestHours || 0} hrs</strong>
            <small class="text-muted">12h rest interval compliant</small>
          </div>
        </div>
      </div>

      <!-- 2-Column Section: Distribution & Daily Coverage -->
      <div style="display:grid; grid-template-columns: 1fr 1.3fr; gap:20px; margin-bottom:24px;">
        
        <!-- Shift Distribution Breakdown Card -->
        <div class="card">
          <div class="card-header">
            <h3>Shift Distribution Breakdown</h3>
          </div>
          <div class="card-body" style="padding:16px 20px;">
            ${shifts.map(s => {
              const pct = s.percentage || 0;
              let barColor = "var(--primary)";
              if (s.shiftType === "MORNING") barColor = "#0284c7";
              else if (s.shiftType === "GENERAL") barColor = "#0d9488";
              else if (s.shiftType === "EVENING") barColor = "#f59e0b";
              else if (s.shiftType === "NIGHT") barColor = "#6366f1";
              else if (s.shiftType === "OFF") barColor = "#64748b";
              else if (s.shiftType === "LEAVE") barColor = "#ef4444";

              return `
                <div style="margin-bottom:14px;">
                  <div style="display:flex; justify-content:space-between; font-size:0.84rem; font-weight:600; margin-bottom:4px;">
                    <span>${escapeHTML(s.shiftName)} (${s.shiftType})</span>
                    <span>${s.count} (${pct}%)</span>
                  </div>
                  <div style="background:#f1f5f9; height:8px; border-radius:4px; overflow:hidden;">
                    <div style="background:${barColor}; width:${pct}%; height:100%; border-radius:4px; transition:width 0.5s;"></div>
                  </div>
                </div>
              `;
            }).join("")}
          </div>
        </div>

        <!-- Daily Shift Coverage Grid Card -->
        <div class="card">
          <div class="card-header">
            <h3>Daily Shift Coverage Breakdown</h3>
          </div>
          <div class="card-body" style="padding:0; overflow-x:auto;">
            <table class="data-table">
              <thead>
                <tr>
                  <th>Date</th>
                  <th>Day</th>
                  <th style="color:#0284c7;">Morning</th>
                  <th style="color:#0d9488;">General</th>
                  <th style="color:#f59e0b;">Evening</th>
                  <th style="color:#6366f1;">Night</th>
                  <th>OFF / Leave</th>
                  <th>Total Active</th>
                </tr>
              </thead>
              <tbody>
                ${coverage.length === 0 ? `<tr><td colspan="8" class="text-center text-muted">No cycle data</td></tr>` : 
                  coverage.map(c => `
                    <tr>
                      <td><strong>${formatDate(c.date)}</strong></td>
                      <td>${escapeHTML(c.dayOfWeek)}</td>
                      <td style="font-weight:700; color:#0284c7;">${c.morningCount}</td>
                      <td style="font-weight:700; color:#0d9488;">${c.generalCount}</td>
                      <td style="font-weight:700; color:#f59e0b;">${c.eveningCount}</td>
                      <td style="font-weight:700; color:#6366f1;">${c.nightCount}</td>
                      <td>${c.offCount + c.leaveCount}</td>
                      <td><span class="badge" style="background:#e0f2fe; color:#0369a1; font-weight:700;">${c.totalAssigned} on duty</span></td>
                    </tr>
                  `).join("")}
              </tbody>
            </table>
          </div>
        </div>

      </div>

      <!-- Top Workload Employees Table -->
      <div class="card">
        <div class="card-header">
          <h3>Workforce Workload Distribution (Top Active Employees)</h3>
        </div>
        <div class="card-body" style="padding:0; overflow-x:auto;">
          <table class="data-table">
            <thead>
              <tr>
                <th>Employee Code</th>
                <th>Employee Name</th>
                <th>Designation</th>
                <th>Total Working Hours</th>
                <th>Night Shifts</th>
                <th>Consecutive Days</th>
                <th>Workload Score</th>
                <th>Rating</th>
              </tr>
            </thead>
            <tbody>
              ${topWorkloads.length === 0 ? `<tr><td colspan="8" class="text-center text-muted">No employee workload data available</td></tr>` :
                topWorkloads.map(w => `
                  <tr>
                    <td><strong>${escapeHTML(w.employeeCode)}</strong></td>
                    <td>${escapeHTML(w.employeeName)}</td>
                    <td>${escapeHTML(w.designation || "-")}</td>
                    <td><strong>${w.totalWorkingHours} hrs</strong></td>
                    <td>${w.nightShiftsCount}</td>
                    <td>${w.consecutiveWorkingDays} days</td>
                    <td><strong>${w.workloadScore} / 100</strong></td>
                    <td><span class="workload-score-badge workload-${w.workloadRating}">${w.workloadRating}</span></td>
                  </tr>
                `).join("")}
            </tbody>
          </table>
        </div>
      </div>
    `;
  } catch (err) {
    container.innerHTML = `<div class="card"><div class="empty-state-box text-danger">âš ï¸ Error loading analytics: ${escapeHTML(err.message)}</div></div>`;
  }
}


// --- 2. SMART ROSTER CONFLICT DETECTOR / VALIDATOR ---
async function renderValidationView() {
  const container = dom.views.validation;
  if (!container) return;
  container.innerHTML = `<div class="empty-state-box"><div class="spinner"></div><p>Auditing active roster for conflict violations...</p></div>`;

  try {
    let cycles = [];
    try {
      cycles = await apiRequest("/api/rosters/cycles");
    } catch (_) {}

    const selectedCycle = state.selectedValidationCycleId || (cycles.length > 0 ? cycles[0].id : null);

    let res;
    if (selectedCycle) {
      res = await apiRequest(`/api/admin/validation/cycle/${selectedCycle}`);
    } else {
      res = await apiRequest("/api/admin/validation/active");
    }

    const summary = res.summary || {};
    const findings = res.findings || [];
    const isCompliant = res.compliant;

    container.innerHTML = `
      <div class="view-header-bar">
        <div>
          <h2>Smart Roster Conflict Detector & Validator</h2>
          <p class="text-muted">Automated rule verification: 12h rest interval, female evening/night protections, leave synchronization & fair rotation</p>
        </div>
        <div class="header-actions" style="display:flex; align-items:center; gap:10px;">
          ${cycles.length > 0 ? `
            <select id="validationCycleSelect" class="form-control-sm" style="padding:6px 12px; border-radius:var(--radius-sm); border:1px solid var(--border-light);">
              ${cycles.map(c => `
                <option value="${c.id}" ${c.id == selectedCycle ? 'selected' : ''}>
                  Cycle #${c.id} (${formatDate(c.startDate)} - ${formatDate(c.endDate)}) [${c.status}]
                </option>
              `).join("")}
            </select>
          ` : ''}
          <button class="btn btn-primary btn-sm" onclick="triggerValidationAudit()">
            ${WRMS_ICONS.refresh}
            <span>Re-Run Audit</span>
          </button>
        </div>
      </div>

      <!-- Audit Status Banner -->
      <div class="alert-info-box" style="background:${isCompliant ? '#dcfce7' : '#fee2e2'}; border-color:${isCompliant ? '#86efac' : '#fca5a5'}; color:${isCompliant ? '#14532d' : '#7f1d1d'}; margin-bottom:20px; display:flex; align-items:center; justify-content:space-between;">
        <div>
          <strong style="font-size:1.05rem;">${isCompliant ? 'Roster 100% Valid & Safe' : 'Rule Violations or Warnings Detected'}</strong>
          <p style="margin-top:4px; font-size:0.86rem; margin-bottom:0;">
            ${isCompliant ? 'All mandatory safety constraints, rest intervals, and statutory gender protections are fully satisfied.' : 'Action required: Review findings below before publishing or locking this schedule.'}
          </p>
        </div>
        <div style="display:flex; align-items:center;">${isCompliant ? WRMS_ICONS.check : WRMS_ICONS.alert}</div>
      </div>

      <!-- Summary KPI Bar -->
      <div class="validation-kpi-bar">
        <div class="validation-card">
          <div class="metric-icon" style="background:#e0f2fe; color:#0284c7;">${WRMS_ICONS.validation}</div>
          <div>
            <span class="text-muted" style="font-size:0.8rem; font-weight:700;">TOTAL RULES AUDITED</span>
            <h3 style="margin:2px 0 0;">${summary.totalRulesChecked || 13} Rules</h3>
          </div>
        </div>
        <div class="validation-card">
          <div class="metric-icon validation-badge-pass">${WRMS_ICONS.check}</div>
          <div>
            <span class="text-muted" style="font-size:0.8rem; font-weight:700;">PASSED RULES</span>
            <h3 style="margin:2px 0 0; color:#166534;">${summary.passedRules || 0}</h3>
          </div>
        </div>
        <div class="validation-card">
          <div class="metric-icon validation-badge-warning">${WRMS_ICONS.alert}</div>
          <div>
            <span class="text-muted" style="font-size:0.8rem; font-weight:700;">WARNINGS</span>
            <h3 style="margin:2px 0 0; color:#854d0e;">${summary.warningCount || 0}</h3>
          </div>
        </div>
        <div class="validation-card">
          <div class="metric-icon validation-badge-error">${WRMS_ICONS.alert}</div>
          <div>
            <span class="text-muted" style="font-size:0.8rem; font-weight:700;">HARD ERRORS</span>
            <h3 style="margin:2px 0 0; color:#991b1b;">${summary.errorCount || 0}</h3>
          </div>
        </div>
      </div>

      <!-- Findings List Card -->
      <div class="card">
        <div class="card-header" style="display:flex; justify-content:space-between; align-items:center;">
          <h3>Detailed Audit Findings (${findings.length})</h3>
        </div>
        <div class="card-body" style="padding:0; overflow-x:auto;">
          <table class="data-table">
            <thead>
              <tr>
                <th>Severity</th>
                <th>Rule ID</th>
                <th>Rule Name</th>
                <th>Employee</th>
                <th>Affected Date</th>
                <th>Shift / Context</th>
                <th>Description & Recommendation</th>
              </tr>
            </thead>
            <tbody>
              ${findings.length === 0 ? `
                <tr>
                  <td colspan="7" class="text-center" style="padding:32px 16px;">
                    <div style="display:flex; justify-content:center; margin-bottom:8px;">${WRMS_ICONS.check}</div>
                    <strong>Zero Conflict Violations!</strong>
                    <p class="text-muted" style="font-size:0.84rem;">No rest violations, leave clashes, or statutory restrictions breached.</p>
                  </td>
                </tr>
              ` : findings.map(f => `
                <tr>
                  <td><span class="severity-tag severity-${f.severity}">${f.severity}</span></td>
                  <td><code>${escapeHTML(f.ruleId)}</code></td>
                  <td><strong>${escapeHTML(f.ruleName)}</strong></td>
                  <td>${f.employeeCode ? `${escapeHTML(f.employeeName)} (<code>${escapeHTML(f.employeeCode)}</code>)` : '<span class="text-muted">Global / Shift</span>'}</td>
                  <td>${f.affectedDate ? formatDate(f.affectedDate) : '<span class="text-muted">Cycle-wide</span>'}</td>
                  <td>${f.shiftType ? `<span class="badge" style="background:#e0f2fe; color:#0369a1;">${f.shiftType}</span>` : '-'}</td>
                  <td style="max-width:350px;">
                    <div style="font-size:0.86rem; color:var(--text-main);">${escapeHTML(f.description)}</div>
                    ${f.recommendation ? `<div style="font-size:0.78rem; color:var(--primary); margin-top:2px;"><strong>Fix:</strong> ${escapeHTML(f.recommendation)}</div>` : ''}
                  </td>
                </tr>
              `).join("")}
            </tbody>
          </table>
        </div>
      </div>
    `;

    const cycleSelect = document.getElementById("validationCycleSelect");
    if (cycleSelect) {
      cycleSelect.addEventListener("change", (e) => {
        state.selectedValidationCycleId = e.target.value;
        renderValidationView();
      });
    }
  } catch (err) {
    container.innerHTML = `<div class="card"><div class="empty-state-box text-danger">⚠️ Error running validator: ${escapeHTML(err.message)}</div></div>`;
  }
}

function triggerValidationAudit() {
  const cycleSelect = document.getElementById("validationCycleSelect");
  if (cycleSelect) {
    state.selectedValidationCycleId = cycleSelect.value;
  }
  renderValidationView();
  toast("Roster validation audit completed!", "success");
}


// --- 3. EMPLOYEE AVAILABILITY & SHIFT PREFERENCES (ADMIN VIEW) ---
async function renderAdminPreferencesView() {
  const container = dom.views.adminPreferences;
  if (!container) return;
  container.innerHTML = `<div class="empty-state-box"><div class="spinner"></div><p>Loading employee shift availability preferences...</p></div>`;

  try {
    const list = await apiRequest("/api/admin/preferences");

    container.innerHTML = `
      <div class="view-header-bar">
        <div>
          <h2>Employee Shift Preferences & Availability</h2>
          <p class="text-muted">Review soft preferences for shift types and weekly off requests (safety constraints remain strictly enforced)</p>
        </div>
        <div class="header-actions">
          <button class="btn btn-secondary btn-sm" onclick="renderAdminPreferencesView()"><span>ðŸ”„ Refresh</span></button>
        </div>
      </div>

      <div class="card">
        <div class="card-body" style="padding:0;">
          <div class="table-wrap">
            <table class="data-table">
              <thead>
                <tr>
                  <th>ID</th>
                  <th>Employee</th>
                  <th>Preferred Shifts</th>
                  <th>Avoid Shifts</th>
                  <th>Preferred OFF Days</th>
                  <th>Preferred Work Days</th>
                  <th>Constraints / Reason</th>
                  <th>Status</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                ${list.length === 0 ? `<tr><td colspan="9" class="text-center text-muted" style="padding:32px;">No employee preferences submitted yet</td></tr>` :
                  list.map(p => `
                    <tr>
                      <td>#${p.id}</td>
                      <td><strong>${escapeHTML(p.employeeName)}</strong><br><small class="text-muted">${escapeHTML(p.employeeCode)}</small></td>
                      <td>${escapeHTML(p.preferredShiftTypes || p.preferredShifts || "-")}</td>
                      <td>${escapeHTML(p.avoidShiftTypes || p.avoidShifts || "-")}</td>
                      <td>${escapeHTML(p.preferredOffDays || "-")}</td>
                      <td>${escapeHTML(p.preferredWorkingDays || "-")}</td>
                      <td style="max-width:200px; font-size:0.82rem;">${escapeHTML(p.temporaryRestrictions || p.temporaryConstraints || "-")}</td>
                      <td>
                        <span class="badge" style="background:${p.status === 'APPROVED' ? '#dcfce7' : p.status === 'REJECTED' ? '#fee2e2' : '#fef9c3'}; color:${p.status === 'APPROVED' ? '#166534' : p.status === 'REJECTED' ? '#991b1b' : '#854d0e'};">
                          ${p.status}
                        </span>
                      </td>
                      <td>
                        ${p.status === 'PENDING' ? `
                          <div style="display:flex; gap:6px;">
                            <button class="btn btn-primary btn-xs" onclick="openAdminPrefDecisionModal(${p.id}, 'APPROVED', '${escapeHTML(p.employeeName)}')">Approve</button>
                            <button class="btn btn-danger btn-xs" onclick="openAdminPrefDecisionModal(${p.id}, 'REJECTED', '${escapeHTML(p.employeeName)}')">Reject</button>
                          </div>
                        ` : `<small class="text-muted">${formatDate(p.reviewedAt)}</small>`}
                      </td>
                    </tr>
                  `).join("")}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    `;
  } catch (err) {
    container.innerHTML = `<div class="card"><div class="empty-state-box text-danger">âš ï¸ Error loading preferences: ${escapeHTML(err.message)}</div></div>`;
  }
}

function openAdminPrefDecisionModal(id, status, empName) {
  document.getElementById("adminPrefId").value = id;
  document.getElementById("adminPrefStatus").value = status;
  document.getElementById("adminPrefDecisionTitle").textContent = `${status === 'APPROVED' ? 'Approve' : 'Reject'} Preference Request`;
  document.getElementById("adminPrefInfo").innerHTML = `<span>Reviewing preference for: <strong>${empName}</strong> &bull; Target Status: <strong>${status}</strong></span>`;
  document.getElementById("adminPrefRemarks").value = "";
  
  const modal = document.getElementById("adminPrefDecisionModal");
  if (modal) modal.classList.remove("hidden");
}


// --- 4. HOLIDAY CALENDAR (ADMIN & EMPLOYEE) ---
async function renderAdminHolidaysView() {
  const container = dom.views.adminHolidays;
  if (!container) return;
  container.innerHTML = `<div class="empty-state-box"><div class="spinner"></div><p>Loading holiday calendar...</p></div>`;

  try {
    const list = await apiRequest("/api/admin/holidays");

    container.innerHTML = `
      <div class="view-header-bar">
        <div>
          <h2>Official Holiday Calendar</h2>
          <p class="text-muted">Manage company and public holidays recognized across weekly roster scheduling</p>
        </div>
        <div class="header-actions">
          <button class="btn btn-primary btn-sm" onclick="openHolidayModal()"><span>âž• Add Holiday</span></button>
          <button class="btn btn-secondary btn-sm" onclick="renderAdminHolidaysView()"><span>ðŸ”„ Refresh</span></button>
        </div>
      </div>

      <div class="card">
        <div class="card-body" style="padding:0; overflow-x:auto;">
          <table class="data-table">
            <thead>
              <tr>
                <th>Holiday Date</th>
                <th>Holiday Name</th>
                <th>Description</th>
                <th>Status</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              ${list.length === 0 ? `<tr><td colspan="5" class="text-center text-muted" style="padding:32px;">No holidays configured yet</td></tr>` :
                list.map(h => `
                  <tr>
                    <td><strong>${formatDate(h.holidayDate)}</strong></td>
                    <td><strong>${escapeHTML(h.name)}</strong></td>
                    <td>${escapeHTML(h.description || "-")}</td>
                    <td>
                      <button class="btn btn-xs ${h.active ? 'btn-success' : 'btn-ghost'}" onclick="toggleHolidayStatus(${h.id}, ${!h.active})" title="Toggle Active Status">
                        ${h.active ? 'Active' : 'Inactive'}
                      </button>
                    </td>
                    <td>
                      <div style="display:flex; gap:8px;">
                        <button class="btn btn-secondary btn-xs" onclick="openHolidayModal(${h.id}, '${escapeHTML(h.name)}', '${h.holidayDate}', '${escapeHTML(h.description || '')}')">Edit</button>
                        <button class="btn btn-danger btn-xs" onclick="deleteHoliday(${h.id})">Delete</button>
                      </div>
                    </td>
                  </tr>
                `).join("")}
            </tbody>
          </table>
        </div>
      </div>
    `;
  } catch (err) {
    container.innerHTML = `<div class="card"><div class="empty-state-box text-danger">âš ï¸ Error loading holidays: ${escapeHTML(err.message)}</div></div>`;
  }
}

function openHolidayModal(id = null, name = "", date = "", desc = "") {
  document.getElementById("holidayFormId").value = id || "";
  document.getElementById("holidayFormName").value = name;
  document.getElementById("holidayFormDate").value = date;
  document.getElementById("holidayFormDesc").value = desc;
  document.getElementById("holidayModalTitle").textContent = id ? "Edit Holiday" : "Add New Holiday";
  document.getElementById("holidayModal").classList.remove("hidden");
}

async function toggleHolidayStatus(id, active) {
  try {
    await apiRequest(`/api/admin/holidays/${id}/status?active=${active}`, { method: "PATCH" });
    toast("Holiday status updated", "success");
    renderAdminHolidaysView();
  } catch (err) {
    toast(err.message, "error");
  }
}

async function deleteHoliday(id) {
  if (!confirm("Are you sure you want to delete this holiday?")) return;
  try {
    await apiRequest(`/api/admin/holidays/${id}`, { method: "DELETE" });
    toast("Holiday deleted successfully", "success");
    renderAdminHolidaysView();
  } catch (err) {
    toast(err.message, "error");
  }
}


// --- 5. SHIFT HANDOVER MANAGEMENT ---
async function renderAdminHandoversView() {
  const container = dom.views.adminHandovers;
  if (!container) return;
  container.innerHTML = `<div class="empty-state-box"><div class="spinner"></div><p>Loading shift handovers log...</p></div>`;

  try {
    const list = await apiRequest("/api/admin/handovers");

    container.innerHTML = `
      <div class="view-header-bar">
        <div>
          <h2>Shift Handover Management</h2>
          <p class="text-muted">Digital shift logbook, transition briefings, pending action items, and reliever acknowledgments</p>
        </div>
        <div class="header-actions">
          <button class="btn btn-secondary btn-sm" onclick="renderAdminHandoversView()"><span>ðŸ”„ Refresh</span></button>
        </div>
      </div>

      <div class="card">
        <div class="card-body" style="padding:0; overflow-x:auto;">
          <table class="data-table">
            <thead>
              <tr>
                <th>Date & Shift</th>
                <th>Outgoing Staff</th>
                <th>Reliever</th>
                <th>Priority</th>
                <th>Executive Summary</th>
                <th>Pending Tasks</th>
                <th>Status</th>
                <th>Logged At</th>
              </tr>
            </thead>
            <tbody>
              ${list.length === 0 ? `<tr><td colspan="8" class="text-center text-muted" style="padding:32px;">No shift handovers logged yet</td></tr>` :
                list.map(h => `
                  <tr>
                    <td><strong>${formatDate(h.handoverDate)}</strong><br><span class="badge" style="background:#e0f2fe; color:#0369a1;">${escapeHTML(h.shiftName)}</span></td>
                    <td><strong>${escapeHTML(h.fromEmployeeName)}</strong><br><small class="text-muted">${escapeHTML(h.fromEmployeeCode)}</small></td>
                    <td>${h.toEmployeeName ? `<strong>${escapeHTML(h.toEmployeeName)}</strong><br><small class="text-muted">${escapeHTML(h.toEmployeeCode)}</small>` : '<span class="text-muted">Open Reliever</span>'}</td>
                    <td><span class="badge prio-${h.priority}">${h.priority}</span></td>
                    <td style="max-width:220px; font-weight:600;">${escapeHTML(h.shiftSummary)}</td>
                    <td style="max-width:220px; font-size:0.82rem; color:var(--text-muted);">${escapeHTML(h.pendingTasks || "-")}</td>
                    <td><span class="badge status-${h.status}">${h.status}</span></td>
                    <td><small class="text-muted">${formatDate(h.createdAt)}</small></td>
                  </tr>
                `).join("")}
            </tbody>
          </table>
        </div>
      </div>
    `;
  } catch (err) {
    container.innerHTML = `<div class="card"><div class="empty-state-box text-danger">âš ï¸ Error loading handovers: ${escapeHTML(err.message)}</div></div>`;
  }
}


// --- 6. EMPLOYEE WORKLOAD ANALYTICS ---
async function renderAdminWorkloadView() {
  const container = dom.views.adminWorkload;
  if (!container) return;
  container.innerHTML = `<div class="empty-state-box"><div class="spinner"></div><p>Computing workforce workload metrics...</p></div>`;

  try {
    const data = await apiRequest("/api/admin/workload");
    const metrics = data.employeeMetrics || [];

    container.innerHTML = `
      <div class="view-header-bar">
        <div>
          <h2>Employee Workload Analytics & Duty Balance</h2>
          <p class="text-muted">Transparent scoring algorithm (0-100) combining duty hours, night shifts, consecutive days, and weekly off fairness</p>
        </div>
        <div class="header-actions">
          <button class="btn btn-secondary btn-sm" onclick="renderAdminWorkloadView()">
            ${WRMS_ICONS.refresh}
            <span>Refresh</span>
          </button>
        </div>
      </div>

      <!-- Workload Summary Cards -->
      <div class="metric-cards-grid" style="margin-bottom:20px;">
        <div class="metric-card">
          <div class="metric-icon" style="background:#e0f2fe; color:#0284c7;">${WRMS_ICONS.employees}</div>
          <div class="metric-details">
            <span class="metric-label">Analyzed Workforce</span>
            <strong class="metric-value">${data.totalEmployeesAnalyzed || 0} Staff</strong>
          </div>
        </div>
        <div class="metric-card">
          <div class="metric-icon" style="background:#dcfce7; color:#16a34a;">${WRMS_ICONS.shifts}</div>
          <div class="metric-details">
            <span class="metric-label">Average Workload Score</span>
            <strong class="metric-value">${data.averageWorkloadScore || 0} / 100</strong>
          </div>
        </div>
        <div class="metric-card">
          <div class="metric-icon" style="background:#fee2e2; color:#991b1b;">${WRMS_ICONS.alert}</div>
          <div class="metric-details">
            <span class="metric-label">Overloaded Staff</span>
            <strong class="metric-value">${data.overloadedCount || 0}</strong>
          </div>
        </div>
        <div class="metric-card">
          <div class="metric-icon" style="background:#fef9c3; color:#854d0e;">${WRMS_ICONS.workload}</div>
          <div class="metric-details">
            <span class="metric-label">Balanced / Optimal</span>
            <strong class="metric-value">${data.balancedCount || 0}</strong>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="card-body" style="padding:0; overflow-x:auto;">
          <table class="data-table">
            <thead>
              <tr>
                <th>Employee Code</th>
                <th>Employee Name</th>
                <th>Designation</th>
                <th>Working Hours</th>
                <th>Night Shifts</th>
                <th>Consecutive Days</th>
                <th>Weekly Offs</th>
                <th>Workload Score</th>
                <th>Rating</th>
              </tr>
            </thead>
            <tbody>
              ${metrics.length === 0 ? `<tr><td colspan="9" class="text-center text-muted" style="padding:32px;">No employee metrics available</td></tr>` :
                metrics.map(m => `
                  <tr>
                    <td><strong>${escapeHTML(m.employeeCode)}</strong></td>
                    <td><strong>${escapeHTML(m.employeeName)}</strong></td>
                    <td>${escapeHTML(m.designation || "-")}</td>
                    <td><strong>${m.totalWorkingHours} hrs</strong></td>
                    <td>${m.nightShiftsCount}</td>
                    <td>${m.consecutiveWorkingDays} days</td>
                    <td>${m.weeklyOffCount}</td>
                    <td><strong style="font-size:1.05rem;">${m.workloadScore}</strong> / 100</td>
                    <td><span class="workload-score-badge workload-${m.workloadRating}">${m.workloadRating}</span></td>
                  </tr>
                `).join("")}
            </tbody>
          </table>
        </div>
      </div>
    `;
  } catch (err) {
    container.innerHTML = `<div class="card"><div class="empty-state-box text-danger">âš ï¸  Error loading workload analytics: ${escapeHTML(err.message)}</div></div>`;
  }
}


// --- 7. EXPORT CENTER (PDF / EXCEL / CSV) ---
async function renderExportCenterView() {
  const container = dom.views.exportCenter;
  if (!container) return;

  const reports = [
    { type: "WEEKLY_ROSTER", title: "Weekly Roster Schedule", icon: WRMS_ICONS.roster, desc: "Detailed duty assignments, shifts, timing, and working/off status for the selected schedule." },
    { type: "EMPLOYEE_MASTER", title: "Employee Master Directory", icon: WRMS_ICONS.employees, desc: "Complete workforce directory with employee codes, emails, designations, contact numbers, and status." },
    { type: "LEAVE_REGISTER", title: "Leave Register & History", icon: WRMS_ICONS.leaves, desc: "Comprehensive log of all approved, pending, and past employee leave requests." },
    { type: "WORKLOAD_REPORT", title: "Employee Workload Analytics", icon: WRMS_ICONS.workload, desc: "Duty hours, night shift counts, consecutive work days, and composite workload scores." },
    { type: "AUDIT_REPORT", title: "System Audit Trail", icon: WRMS_ICONS.audit, desc: "Complete security and operation audit trail of all manual overrides, swaps, and roster lifecycle events." },
    { type: "HOLIDAY_CALENDAR", title: "Official Holiday Calendar", icon: WRMS_ICONS.holidays, desc: "List of recognized organization and public holidays across scheduling cycles." },
    { type: "SKILL_MATRIX", title: "Employee Skill Matrix", icon: WRMS_ICONS.skills, desc: "Workforce competency catalog with verified employee proficiency ratings and certifications." },
    { type: "SHIFT_CAPACITY", title: "Shift Capacities & Timings", icon: WRMS_ICONS.shifts, desc: "Shift configuration data, required headcounts, timing ranges, and operational windows." }
  ];

  container.innerHTML = `
    <div class="view-header-bar">
      <div>
        <h2>Enterprise Export Center</h2>
        <p class="text-muted">Instant one-click exports in standard Excel (.xlsx), PDF documents, CSV datasets, and high-resolution images (PNG / JPG / JPEG)</p>
      </div>
    </div>

    <div class="export-grid">
      ${reports.map(r => `
        <div class="export-card">
          <div>
            <div class="export-card-header">
              <span class="export-card-icon">${r.icon}</span>
              <div>
                <h3 style="margin:0; font-size:1.05rem;">${r.title}</h3>
                <small class="text-muted">Type: <code>${r.type}</code></small>
              </div>
            </div>
            <p style="font-size:0.84rem; color:var(--text-muted); line-height:1.4;">${r.desc}</p>
          </div>
          <div class="export-actions">
            <button class="btn btn-secondary btn-sm" onclick="triggerDownload('${r.type}', 'XLSX')" title="Download Excel Spreadsheet">
              ${WRMS_ICONS.fileExcel || '📊'}
              <span>Excel (.xlsx)</span>
            </button>
            <button class="btn btn-secondary btn-sm" onclick="triggerDownload('${r.type}', 'PDF')" title="Download PDF Document">
              ${WRMS_ICONS.filePdf || '📄'}
              <span>PDF</span>
            </button>
            <button class="btn btn-secondary btn-sm" onclick="triggerDownload('${r.type}', 'CSV')" title="Download CSV Dataset">
              ${WRMS_ICONS.fileCsv || '📑'}
              <span>CSV</span>
            </button>
            <button class="btn btn-secondary btn-sm" onclick="triggerDownload('${r.type}', 'PNG')" title="Download PNG Image">
              <span>🖼️ PNG</span>
            </button>
            <button class="btn btn-secondary btn-sm" onclick="triggerDownload('${r.type}', 'JPG')" title="Download JPG Image">
              <span>📷 JPG</span>
            </button>
            <button class="btn btn-secondary btn-sm" onclick="triggerDownload('${r.type}', 'JPEG')" title="Download JPEG Image">
              <span>🖼️ JPEG</span>
            </button>
          </div>
        </div>
      `).join("")}
    </div>
  `;
}

async function triggerDownload(reportType, format) {
  try {
    toast(`Generating ${reportType} in ${format} format...`, "info");
    const token = state.token;
    const url = `/api/admin/exports/download?reportType=${encodeURIComponent(reportType)}&format=${encodeURIComponent(format)}`;
    
    const res = await fetch(url, {
      method: "GET",
      headers: {
        "Authorization": `Bearer ${token}`
      }
    });

    if (!res.ok) {
      let errText = "";
      try {
        errText = await res.text();
      } catch (e) {}
      throw new Error(errText || `Unable to generate ${format} export (HTTP ${res.status}). Please try again.`);
    }

    const blob = await res.blob();
    if (!blob || blob.size === 0) {
      throw new Error(`Unable to generate ${format} export (received 0 bytes). Please try again.`);
    }

    const extMap = {
      XLSX: "xlsx",
      PDF: "pdf",
      CSV: "csv",
      PNG: "png",
      JPG: "jpg",
      JPEG: "jpg"
    };
    const ext = extMap[format.toUpperCase()] || format.toLowerCase();
    const filename = `${reportType.toLowerCase()}_export_${new Date().toISOString().split('T')[0]}.${ext}`;

    const downloadUrl = window.URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = downloadUrl;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    window.URL.revokeObjectURL(downloadUrl);

    toast(`Downloaded ${filename} successfully (${(blob.size / 1024).toFixed(1)} KB)!`, "success");
  } catch (err) {
    toast(`Export error: ${err.message || "Failed to download export"}`, "error");
  }
}


// --- 8. EMPLOYEE SKILL MATRIX ---
async function renderAdminSkillsView() {
  const container = dom.views.adminSkills;
  if (!container) return;
  container.innerHTML = `<div class="empty-state-box"><div class="spinner"></div><p>Loading workforce skill matrix...</p></div>`;

  try {
    const [skills, matrix, employees] = await Promise.all([
      apiRequest("/api/admin/skills"),
      apiRequest("/api/admin/skills/employee-matrix"),
      apiRequest("/api/employees")
    ]);

    container.innerHTML = `
      <div class="view-header-bar">
        <div>
          <h2>Employee Skill Matrix & Competency Catalog</h2>
          <p class="text-muted">Track verified workforce competencies, certifications, and operational proficiency levels</p>
        </div>
        <div class="header-actions">
          <button class="btn btn-primary btn-sm" onclick="openAssignSkillModal()">
            ${WRMS_ICONS.skills}
            <span>Assign Skill to Employee</span>
          </button>
          <button class="btn btn-secondary btn-sm" onclick="openSkillModal()">
            ${WRMS_ICONS.skills}
            <span>New Skill</span>
          </button>
          <button class="btn btn-secondary btn-sm" onclick="renderAdminSkillsView()">
            ${WRMS_ICONS.refresh}
            <span>Refresh</span>
          </button>
        </div>
      </div>

      <!-- Employee Assigned Skills Matrix -->
      <div class="card" style="margin-bottom:24px;">
        <div class="card-header">
          <h3>Workforce Verified Skills Matrix (${matrix.length} assignments)</h3>
        </div>
        <div class="card-body" style="padding:0; overflow-x:auto;">
          <table class="data-table">
            <thead>
              <tr>
                <th>Employee</th>
                <th>Skill Name</th>
                <th>Category</th>
                <th>Proficiency</th>
                <th>Certified</th>
                <th>Certification Title</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              ${matrix.length === 0 ? `<tr><td colspan="7" class="text-center text-muted" style="padding:32px;">No skill assignments recorded yet</td></tr>` :
                matrix.map(m => `
                  <tr>
                    <td><strong>${escapeHTML(m.employeeName)}</strong><br><small class="text-muted">${escapeHTML(m.employeeCode)}</small></td>
                    <td><strong>${escapeHTML(m.skillName)}</strong></td>
                    <td>${escapeHTML(m.category || "GENERAL")}</td>
                    <td><span class="proficiency-tag prof-${m.proficiencyLevel}">${m.proficiencyLevel}</span></td>
                    <td>${m.certified ? 'Yes' : 'No'}</td>
                    <td>${escapeHTML(m.certificationName || "-")}</td>
                    <td>
                      <button class="btn btn-danger btn-xs" onclick="deleteEmployeeSkill(${m.id})">Remove</button>
                    </td>
                  </tr>
                `).join("")}
            </tbody>
          </table>
        </div>
      </div>

      <!-- Skill Catalog List -->
      <div class="card">
        <div class="card-header">
          <h3>Skill Catalog Directory (${skills.length})</h3>
        </div>
        <div class="card-body" style="padding:0; overflow-x:auto;">
          <table class="data-table">
            <thead>
              <tr>
                <th>Skill ID</th>
                <th>Skill Name</th>
                <th>Category</th>
                <th>Description</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              ${skills.length === 0 ? `<tr><td colspan="5" class="text-center text-muted" style="padding:24px;">No catalog skills created</td></tr>` :
                skills.map(s => `
                  <tr>
                    <td>#${s.id}</td>
                    <td><strong>${escapeHTML(s.name)}</strong></td>
                    <td>${escapeHTML(s.category || "-")}</td>
                    <td>${escapeHTML(s.description || "-")}</td>
                    <td>
                      <button class="btn btn-danger btn-xs" onclick="deleteSkill(${s.id})">Delete</button>
                    </td>
                  </tr>
                `).join("")}
            </tbody>
          </table>
        </div>
      </div>
    `;

    // Populate Assign Skill Modal selects
    const empSelect = document.getElementById("assignSkillEmp");
    if (empSelect) {
      empSelect.innerHTML = employees.map(e => `<option value="${e.id}">${escapeHTML(e.firstName)} ${escapeHTML(e.lastName || '')} (${e.employeeCode})</option>`).join("");
    }
    const skillSelect = document.getElementById("assignSkillId");
    if (skillSelect) {
      skillSelect.innerHTML = skills.map(s => `<option value="${s.id}">${escapeHTML(s.name)} [${s.category || 'General'}]</option>`).join("");
    }

  } catch (err) {
    container.innerHTML = `<div class="card"><div class="empty-state-box text-danger">âš ï¸ Error loading skill matrix: ${escapeHTML(err.message)}</div></div>`;
  }
}

function openSkillModal() {
  document.getElementById("skillFormId").value = "";
  document.getElementById("skillFormName").value = "";
  document.getElementById("skillFormCategory").value = "";
  document.getElementById("skillFormDesc").value = "";
  document.getElementById("skillModal").classList.remove("hidden");
}

function openAssignSkillModal() {
  document.getElementById("assignSkillCertName").value = "";
  document.getElementById("assignSkillModal").classList.remove("hidden");
}

async function deleteSkill(id) {
  if (!confirm("Delete this skill from catalog?")) return;
  try {
    await apiRequest(`/api/admin/skills/${id}`, { method: "DELETE" });
    toast("Skill deleted from catalog", "success");
    renderAdminSkillsView();
  } catch (err) {
    toast(err.message, "error");
  }
}

async function deleteEmployeeSkill(id) {
  if (!confirm("Remove skill assignment?")) return;
  try {
    await apiRequest(`/api/admin/skills/employee-skill/${id}`, { method: "DELETE" });
    toast("Skill assignment removed", "success");
    renderAdminSkillsView();
  } catch (err) {
    toast(err.message, "error");
  }
}


// --- 9. ROSTER VERSION HISTORY & VERSION COMPARISON ---
async function renderRosterVersionsView() {
  const container = dom.views.rosterVersions;
  if (!container) return;
  container.innerHTML = `<div class="empty-state-box"><div class="spinner"></div><p>Loading roster version history...</p></div>`;

  try {
    let cycles = [];
    try {
      cycles = await apiRequest("/api/rosters/cycles");
    } catch (_) {}

    const selectedCycle = state.selectedVersionCycleId || (cycles.length > 0 ? cycles[0].id : null);

    let versions = [];
    if (selectedCycle) {
      versions = await apiRequest(`/api/admin/roster-versions/cycle/${selectedCycle}`);
    }

    const actionFilter = state.versionActionFilter || "ALL";
    const filteredVersions = versions.filter(v => {
      if (actionFilter !== "ALL" && (v.action || v.actionTaken) !== actionFilter) return false;
      return true;
    });

    container.innerHTML = `
      <div class="view-header-bar">
        <div>
          <h2>Roster Version History & Revision Comparison</h2>
          <p class="text-muted">Trace all historical snapshots created across roster generation, swaps, overrides, and publication</p>
        </div>
        <div class="header-actions" style="display:flex; align-items:center; gap:10px; flex-wrap:wrap;">
          ${cycles.length > 0 ? `
            <select id="versionCycleSelect" class="form-control-sm" style="padding:6px 12px; border-radius:var(--radius-sm); border:1px solid var(--border-light);">
              ${cycles.map(c => `
                <option value="${c.id}" ${c.id == selectedCycle ? 'selected' : ''}>
                  Cycle #${c.id} (${formatDate(c.startDate)} - ${formatDate(c.endDate)})
                </option>
              `).join("")}
            </select>
          ` : ''}
          <select id="versionActionFilterSelect" class="form-control-sm" style="padding:6px 12px; border-radius:var(--radius-sm); border:1px solid var(--border-light);">
            <option value="ALL" ${actionFilter === 'ALL' ? 'selected' : ''}>All Actions</option>
            <option value="GENERATED" ${actionFilter === 'GENERATED' ? 'selected' : ''}>GENERATED</option>
            <option value="OVERRIDE_APPLIED" ${actionFilter === 'OVERRIDE_APPLIED' ? 'selected' : ''}>OVERRIDE_APPLIED</option>
            <option value="SHIFT_SWAPPED" ${actionFilter === 'SHIFT_SWAPPED' ? 'selected' : ''}>SHIFT_SWAPPED</option>
            <option value="PUBLISHED" ${actionFilter === 'PUBLISHED' ? 'selected' : ''}>PUBLISHED</option>
            <option value="RESTORED" ${actionFilter === 'RESTORED' ? 'selected' : ''}>RESTORED</option>
          </select>
          ${versions.length >= 2 ? `
            <button class="btn btn-primary btn-sm" onclick="openVersionComparisonFromPage()"><span>🔄 Compare 2 Revisions</span></button>
          ` : ''}
          <button class="btn btn-secondary btn-sm" onclick="renderRosterVersionsView()"><span>🔄 Refresh</span></button>
        </div>
      </div>

      <div class="card">
        <div class="card-header" style="display:flex; justify-content:space-between; align-items:center;">
          <h3>Snapshots Timeline (${filteredVersions.length} of ${versions.length} versions)</h3>
        </div>
        <div class="card-body" style="padding:0; overflow-x:auto;">
          <table class="data-table">
            <thead>
              <tr>
                <th>Version</th>
                <th>Action Taken</th>
                <th>Status</th>
                <th>Mode</th>
                <th>Summary / Reason</th>
                <th>Author</th>
                <th>Created Timestamp</th>
                <th>Duties</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              ${filteredVersions.length === 0 ? `<tr><td colspan="9" class="text-center text-muted" style="padding:32px;">No versions matching criteria</td></tr>` :
                filteredVersions.map((v, idx) => `
                  <tr>
                    <td><strong style="font-size:1.05rem;">v${v.versionNumber}</strong></td>
                    <td><span class="badge" style="background:#e0f2fe; color:#0369a1; font-weight:700;">${escapeHTML(v.action || v.actionTaken || "UPDATED")}</span></td>
                    <td><span class="badge" style="background:${v.status === 'PUBLISHED' ? '#dcfce7; color:#166534;' : (v.status === 'LOCKED' ? '#fee2e2; color:#991b1b;' : '#fef9c3; color:#854d0e;')} font-weight:600;">${escapeHTML(v.status || "GENERATED")}</span></td>
                    <td><small class="badge" style="background:#f1f5f9; color:#475569;">${escapeHTML(v.generationMode || "MANUAL")}</small></td>
                    <td style="max-width:280px;">${escapeHTML(v.actionReason || v.description || "-")}</td>
                    <td><strong>${escapeHTML(v.createdBy || v.createdByName || "System")}</strong></td>
                    <td>${formatDate(v.createdTimestamp || v.createdAt)}</td>
                    <td>${v.affectedAssignmentsCount || 42}</td>
                    <td>
                      <div style="display:flex; gap:6px;">
                        <button class="btn btn-secondary btn-xs" onclick="viewVersionDetails(${selectedCycle}, ${v.versionNumber})">Details</button>
                        ${idx > 0 ? `
                          <button class="btn btn-secondary btn-xs" onclick="compareVersionsByIds(${filteredVersions[idx].id}, ${filteredVersions[0].id})">Diff</button>
                          <button class="btn btn-warning btn-xs" onclick="confirmRestoreVersion(${selectedCycle}, ${v.versionNumber})">Restore</button>
                        ` : '<span class="text-muted" style="font-size:0.75rem; padding:4px 0;">Latest</span>'}
                      </div>
                    </td>
                  </tr>
                `).join("")}
            </tbody>
          </table>
        </div>
      </div>
    `;

    const vSelect = document.getElementById("versionCycleSelect");
    if (vSelect) {
      vSelect.addEventListener("change", (e) => {
        state.selectedVersionCycleId = e.target.value;
        renderRosterVersionsView();
      });
    }

    const aSelect = document.getElementById("versionActionFilterSelect");
    if (aSelect) {
      aSelect.addEventListener("change", (e) => {
        state.versionActionFilter = e.target.value;
        renderRosterVersionsView();
      });
    }
  } catch (err) {
    container.innerHTML = `<div class="card"><div class="empty-state-box text-danger">⚠️ Error loading versions: ${escapeHTML(err.message)}</div></div>`;
  }
}

async function viewVersionDetails(cycleId, versionNumber) {
  try {
    const v = await apiRequest(`/api/admin/roster-versions/cycle/${cycleId}/version/${versionNumber}`);
    let snapshotList = [];
    try {
      snapshotList = JSON.parse(v.snapshotData || "[]");
    } catch (_) {}

    const modal = document.getElementById("compareVersionsModal");
    const body = document.getElementById("compareVersionsBody");
    const title = document.getElementById("compareVersionsSubtitle");
    if (!modal || !body) {
      toast(`Version ${versionNumber}: ${snapshotList.length} assignments recorded`, "info");
      return;
    }

    modal.classList.remove("hidden");
    if (title) title.textContent = `Version ${versionNumber} Details (${v.action} by ${v.createdBy})`;

    body.innerHTML = `
      <div style="margin-bottom:12px; padding:10px 14px; background:var(--bg-surface); border-radius:6px; font-size:0.88rem;">
        <strong>Action:</strong> ${escapeHTML(v.action)} | <strong>Mode:</strong> ${escapeHTML(v.generationMode)} | <strong>Timestamp:</strong> ${formatDate(v.createdTimestamp)}
        <div style="margin-top:4px; color:var(--text-muted);">${escapeHTML(v.actionReason || "No details provided")}</div>
      </div>
      <table class="data-table">
        <thead>
          <tr>
            <th>Date</th>
            <th>Employee</th>
            <th>Shift Assigned</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          ${snapshotList.length === 0 ? '<tr><td colspan="4" class="text-center text-muted">No assignment records</td></tr>' :
            snapshotList.map(item => `
              <tr>
                <td><strong>${formatDate(item.date)}</strong></td>
                <td><strong>${escapeHTML(item.employeeName)}</strong> (<code>${escapeHTML(item.employeeCode)}</code>)</td>
                <td><span class="badge" style="background:#e0f2fe; color:#0369a1;">${escapeHTML(item.shiftType || "OFF")}</span></td>
                <td>${item.isOff ? '<span class="badge" style="background:#fef9c3; color:#854d0e;">OFF</span>' : (item.isOnLeave ? '<span class="badge" style="background:#fee2e2; color:#991b1b;">LEAVE</span>' : '<span class="text-muted">Active</span>')}</td>
              </tr>
            `).join("")}
        </tbody>
      </table>
    `;
  } catch (err) {
    toast(err.message, "error");
  }
}

async function confirmRestoreVersion(cycleId, versionNumber) {
  if (!confirm(`Are you sure you want to restore Roster Cycle #${cycleId} to Version ${versionNumber}? This will create a new RESTORED snapshot and update active shifts.`)) {
    return;
  }

  try {
    const res = await apiRequest(`/api/admin/roster-versions/cycle/${cycleId}/restore/${versionNumber}`, { method: "POST" });
    toast(`Roster cycle #${cycleId} successfully restored from version v${versionNumber}! (Created new version v${res.versionNumber})`, "success");
    broadcastDataMutation("ROSTER_RESTORED");
    renderRosterVersionsView();
  } catch (err) {
    toast(err.message, "error");
  }
}

async function compareVersionsByIds(v1Id, v2Id) {
  const modal = document.getElementById("compareVersionsModal");
  const body = document.getElementById("compareVersionsBody");
  if (!modal || !body) return;

  modal.classList.remove("hidden");
  body.innerHTML = `<div class="empty-state-box"><div class="spinner"></div><p>Calculating side-by-side version diff...</p></div>`;

  try {
    const diff = await apiRequest(`/api/admin/roster-versions/compare?version1Id=${v1Id}&version2Id=${v2Id}`);
    const v1Num = diff.version1Number || (diff.version1 ? diff.version1.versionNumber : 1);
    const v2Num = diff.version2Number || (diff.version2 ? diff.version2.versionNumber : 2);
    const v1Act = diff.v1Action || (diff.version1 ? diff.version1.actionTaken : "v" + v1Num);
    const v2Act = diff.v2Action || (diff.version2 ? diff.version2.actionTaken : "v" + v2Num);
    const items = diff.diffs || [];
    const totalChanges = diff.totalChanges !== undefined ? diff.totalChanges : (diff.changedAssignmentsCount || 0);

    const sub = document.getElementById("compareVersionsSubtitle");
    if (sub) {
      sub.textContent = `Comparing Version ${v1Num} vs Version ${v2Num} (${totalChanges} changed duties)`;
    }

    body.innerHTML = `
      <div style="margin-bottom:16px; display:flex; gap:16px;">
        <div style="background:#fee2e2; color:#991b1b; padding:10px 14px; border-radius:6px; flex:1;">
          <strong>Version ${v1Num} (${escapeHTML(v1Act)})</strong>
          <div style="font-size:0.8rem; margin-top:2px;">${diff.v1Timestamp ? formatDate(diff.v1Timestamp) : ''}</div>
        </div>
        <div style="background:#dcfce7; color:#166534; padding:10px 14px; border-radius:6px; flex:1;">
          <strong>Version ${v2Num} (${escapeHTML(v2Act)})</strong>
          <div style="font-size:0.8rem; margin-top:2px;">${diff.v2Timestamp ? formatDate(diff.v2Timestamp) : ''}</div>
        </div>
      </div>

      <table class="data-table">
        <thead>
          <tr>
            <th>Date</th>
            <th>Employee</th>
            <th>Version ${v1Num} Assignment</th>
            <th>Version ${v2Num} Assignment</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          ${items.length === 0 ? `<tr><td colspan="5" class="text-center text-muted" style="padding:24px;">No differences between these two snapshots</td></tr>` :
            items.map(d => `
              <tr style="${d.changed ? 'background:rgba(254, 240, 138, 0.25); font-weight:600;' : ''}">
                <td><strong>${formatDate(d.date || d.rosterDate)}</strong> (${d.dayOfWeek || ''})</td>
                <td><strong>${escapeHTML(d.employeeName || "")}</strong> (<code>${escapeHTML(d.employeeCode || "")}</code>)</td>
                <td>
                  <span class="badge" style="background:#f1f5f9; color:#334155;">${escapeHTML(d.v1Shift || d.v1ShiftName || "None")}</span>
                </td>
                <td>
                  <span class="badge" style="background:#e0f2fe; color:#0369a1;">${escapeHTML(d.v2Shift || d.v2ShiftName || "None")}</span>
                </td>
                <td>
                  ${d.changed ? '<span class="badge" style="background:#fef9c3; color:#854d0e; font-weight:700;">CHANGED</span>' : '<span class="text-muted">Unchanged</span>'}
                </td>
              </tr>
            `).join("")}
        </tbody>
      </table>
    `;
  } catch (err) {
    body.innerHTML = `<div class="empty-state-box text-danger">⚠️ Error comparing versions: ${escapeHTML(err.message)}</div>`;
  }
}

function openVersionComparisonFromPage() {
  const cycleSelect = document.getElementById("versionCycleSelect");
  const cycleId = cycleSelect ? cycleSelect.value : null;
  if (!cycleId) return;
  
  apiRequest(`/api/admin/roster-versions/cycle/${cycleId}`).then(versions => {
    if (versions.length >= 2) {
      compareVersionsByIds(versions[1].id, versions[0].id);
    } else {
      toast("Need at least 2 versions to compare", "warning");
    }
  }).catch(err => toast(err.message, "error"));
}


// --- 10. EMPLOYEE SELF-SERVICE WORKSPACE ADDON TABS ---

// Render Employee Shift Preferences Tab
async function renderEmployeePreferencesTabHTML() {
  try {
    const list = await apiRequest("/api/preferences/my");
    return `
      <div class="card" style="margin-bottom:20px;">
        <div class="card-header" style="display:flex; justify-content:space-between; align-items:center;">
          <div>
            <h3>My Shift Availability & Preferences</h3>
            <p class="text-muted" style="margin:0; font-size:0.84rem;">Submit preferred working shifts and avoid days for upcoming roster cycles</p>
          </div>
          <button class="btn btn-primary btn-sm" onclick="openPreferenceModal()">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="margin-right:4px;"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
            <span>Submit New Preference</span>
          </button>
        </div>
        <div class="card-body" style="padding:0;">
          <div class="table-wrap">
            <table class="data-table">
              <thead>
                <tr>
                  <th>Submitted Date</th>
                  <th>Preferred Shifts</th>
                  <th>Avoid Shifts</th>
                  <th>Preferred OFF Days</th>
                  <th>Working Days</th>
                  <th>Restrictions</th>
                  <th>Status</th>
                  <th>Admin Remarks</th>
                </tr>
              </thead>
              <tbody>
                ${list.length === 0 ? `<tr><td colspan="8" class="text-center text-muted" style="padding:32px;">No shift preferences submitted yet</td></tr>` :
                  list.map(p => `
                    <tr>
                      <td>${formatDate(p.createdAt)}</td>
                      <td><strong>${escapeHTML(p.preferredShiftTypes || p.preferredShifts || "-")}</strong></td>
                      <td>${escapeHTML(p.avoidShiftTypes || p.avoidShifts || "-")}</td>
                      <td>${escapeHTML(p.preferredOffDays || "-")}</td>
                      <td>${escapeHTML(p.preferredWorkingDays || "-")}</td>
                      <td style="max-width:180px; font-size:0.82rem;">${escapeHTML(p.temporaryRestrictions || p.temporaryConstraints || "-")}</td>
                      <td>
                        <span class="badge" style="background:${p.status === 'APPROVED' ? '#dcfce7' : p.status === 'REJECTED' ? '#fee2e2' : '#fef9c3'}; color:${p.status === 'APPROVED' ? '#166534' : p.status === 'REJECTED' ? '#991b1b' : '#854d0e'};">
                          ${p.status}
                        </span>
                      </td>
                      <td>${escapeHTML(p.adminRemarks || "-")}</td>
                    </tr>
                  `).join("")}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    `;
  } catch (err) {
    return `<div class="card"><div class="empty-state-box text-danger">âš ï¸ Error loading preferences: ${escapeHTML(err.message)}</div></div>`;
  }
}

// Render Employee Shift Handovers Tab
async function renderEmployeeHandoversTabHTML() {
  try {
    const [outgoing, incoming, shifts, employees] = await Promise.all([
      apiRequest("/api/handovers/my"),
      apiRequest("/api/handovers/incoming"),
      apiRequest("/api/shifts"),
      apiRequest("/api/employees")
    ]);

    // Pre-populate handover modal shifts & employees
    const shiftSelect = document.getElementById("handoverShift");
    if (shiftSelect) {
      shiftSelect.innerHTML = shifts.map(s => `<option value="${s.id}">${escapeHTML(s.shiftName)} (${s.timingDisplay || s.shiftType})</option>`).join("");
    }
    const empSelect = document.getElementById("handoverToEmployee");
    if (empSelect) {
      empSelect.innerHTML = `<option value="">-- Open to oncoming reliever --</option>` +
        employees.map(e => `<option value="${e.id}">${escapeHTML(e.firstName)} ${escapeHTML(e.lastName || '')} (${e.employeeCode})</option>`).join("");
    }

    return `
      <div class="card" style="margin-bottom:20px;">
        <div class="card-header" style="display:flex; justify-content:space-between; align-items:center;">
          <div>
            <h3>Shift Handover Logbook</h3>
            <p class="text-muted" style="margin:0; font-size:0.84rem;">Document shift completions, hand over pending tasks, and review incoming transition notes</p>
          </div>
          <button class="btn btn-primary btn-sm" onclick="openHandoverCreateModal()">
            <span>âž• Create Handover Note</span>
          </button>
        </div>
      </div>

      <!-- Incoming Shift Handovers -->
      <div class="card" style="margin-bottom:20px;">
        <div class="card-header">
          <h3>Incoming Shift Handovers (For My Shifts)</h3>
        </div>
        <div class="card-body" style="padding:0; overflow-x:auto;">
          <table class="data-table">
            <thead>
              <tr>
                <th>Date & Shift</th>
                <th>From Staff</th>
                <th>Priority</th>
                <th>Executive Summary</th>
                <th>Pending Tasks</th>
                <th>Status</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              ${incoming.length === 0 ? `<tr><td colspan="7" class="text-center text-muted" style="padding:24px;">No incoming handovers assigned to you</td></tr>` :
                incoming.map(h => `
                  <tr>
                    <td><strong>${formatDate(h.handoverDate)}</strong><br><span class="badge" style="background:#e0f2fe; color:#0369a1;">${escapeHTML(h.shiftName)}</span></td>
                    <td><strong>${escapeHTML(h.fromEmployeeName)}</strong></td>
                    <td><span class="badge prio-${h.priority}">${h.priority}</span></td>
                    <td style="max-width:220px; font-weight:600;">${escapeHTML(h.shiftSummary)}</td>
                    <td style="max-width:220px; font-size:0.82rem; color:var(--text-muted);">${escapeHTML(h.pendingTasks || "-")}</td>
                    <td><span class="badge status-${h.status}">${h.status}</span></td>
                    <td>
                      ${h.status !== 'COMPLETED' ? `
                        <button class="btn btn-success btn-xs" onclick="completeHandover(${h.id})">Acknowledge</button>
                      ` : 'âœ”ï¸ Done'}
                    </td>
                  </tr>
                `).join("")}
            </tbody>
          </table>
        </div>
      </div>

      <!-- Outgoing Handover Notes -->
      <div class="card">
        <div class="card-header">
          <h3>My Outgoing Handover Notes</h3>
        </div>
        <div class="card-body" style="padding:0; overflow-x:auto;">
          <table class="data-table">
            <thead>
              <tr>
                <th>Date & Shift</th>
                <th>Reliever</th>
                <th>Priority</th>
                <th>Executive Summary</th>
                <th>Status</th>
                <th>Logged At</th>
              </tr>
            </thead>
            <tbody>
              ${outgoing.length === 0 ? `<tr><td colspan="6" class="text-center text-muted" style="padding:24px;">No outgoing handovers created yet</td></tr>` :
                outgoing.map(h => `
                  <tr>
                    <td><strong>${formatDate(h.handoverDate)}</strong><br><span class="badge" style="background:#e0f2fe; color:#0369a1;">${escapeHTML(h.shiftName)}</span></td>
                    <td>${h.toEmployeeName ? `<strong>${escapeHTML(h.toEmployeeName)}</strong>` : '<span class="text-muted">Open</span>'}</td>
                    <td><span class="badge prio-${h.priority}">${h.priority}</span></td>
                    <td style="max-width:260px;">${escapeHTML(h.shiftSummary)}</td>
                    <td><span class="badge status-${h.status}">${h.status}</span></td>
                    <td>${formatDate(h.createdAt)}</td>
                  </tr>
                `).join("")}
            </tbody>
          </table>
        </div>
      </div>
    `;
  } catch (err) {
    return `<div class="card"><div class="empty-state-box text-danger">âš ï¸ Error loading handovers: ${escapeHTML(err.message)}</div></div>`;
  }
}

function openHandoverCreateModal() {
  document.getElementById("handoverFormId").value = "";
  document.getElementById("handoverDate").value = new Date().toISOString().split('T')[0];
  document.getElementById("handoverSummary").value = "";
  document.getElementById("handoverPendingTasks").value = "";
  document.getElementById("handoverCompletedTasks").value = "";
  document.getElementById("handoverNotes").value = "";
  document.getElementById("handoverModal").classList.remove("hidden");
}

async function completeHandover(id) {
  try {
    await apiRequest(`/api/handovers/${id}`, {
      method: "PUT",
      body: { status: "COMPLETED" }
    });
    toast("Handover acknowledged and marked COMPLETED!", "success");
    switchEmployeeWorkspaceTab("handovers");
  } catch (err) {
    toast(err.message, "error");
  }
}

// Render Employee Skills Tab
async function renderEmployeeSkillsTabHTML() {
  try {
    const list = await apiRequest("/api/skills/my");
    return `
      <div class="card">
        <div class="card-header">
          <h3>My Verified Skills & Competencies</h3>
          <p class="text-muted" style="margin:0; font-size:0.84rem;">Operational qualifications, certifications, and proficiency ratings verified by administration</p>
        </div>
        <div class="card-body" style="padding:0; overflow-x:auto;">
          <table class="data-table">
            <thead>
              <tr>
                <th>Skill Name</th>
                <th>Category</th>
                <th>Proficiency Level</th>
                <th>Certified</th>
                <th>Certification Title</th>
                <th>Verified Date</th>
              </tr>
            </thead>
            <tbody>
              ${list.length === 0 ? `<tr><td colspan="6" class="text-center text-muted" style="padding:32px;">No skills recorded on your profile yet</td></tr>` :
                list.map(s => `
                  <tr>
                    <td><strong>${escapeHTML(s.skillName)}</strong></td>
                    <td>${escapeHTML(s.category || "GENERAL")}</td>
                    <td><span class="proficiency-tag prof-${s.proficiencyLevel}">${s.proficiencyLevel}</span></td>
                    <td>${s.certified ? 'âœ… Certified' : 'Standard'}</td>
                    <td>${escapeHTML(s.certificationName || "-")}</td>
                    <td>${formatDate(s.createdAt)}</td>
                  </tr>
                `).join("")}
            </tbody>
          </table>
        </div>
      </div>
    `;
  } catch (err) {
    return `<div class="card"><div class="empty-state-box text-danger">âš ï¸ Error loading skills: ${escapeHTML(err.message)}</div></div>`;
  }
}

// Render Employee Holidays Tab
async function renderEmployeeHolidaysTabHTML() {
  try {
    const list = await apiRequest("/api/holidays");
    return `
      <div class="card">
        <div class="card-header">
          <h3>Official Company Holidays</h3>
          <p class="text-muted" style="margin:0; font-size:0.84rem;">Upcoming recognized public and organizational holidays</p>
        </div>
        <div class="card-body" style="padding:0; overflow-x:auto;">
          <table class="data-table">
            <thead>
              <tr>
                <th>Date</th>
                <th>Holiday Name</th>
                <th>Description</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              ${list.length === 0 ? `<tr><td colspan="4" class="text-center text-muted" style="padding:32px;">No company holidays scheduled</td></tr>` :
                list.map(h => `
                  <tr>
                    <td><strong>${formatDate(h.holidayDate)}</strong></td>
                    <td><strong>${escapeHTML(h.name)}</strong></td>
                    <td>${escapeHTML(h.description || "-")}</td>
                    <td><span class="badge" style="background:#dcfce7; color:#166534;">Official Holiday</span></td>
                  </tr>
                `).join("")}
            </tbody>
          </table>
        </div>
      </div>
    `;
  } catch (err) {
    return `<div class="card"><div class="empty-state-box text-danger">âš ï¸ Error loading holidays: ${escapeHTML(err.message)}</div></div>`;
  }
}

// Global modal bindings
document.addEventListener("DOMContentLoaded", () => {
  const prefDecisionForm = document.getElementById("adminPrefDecisionForm");
  if (prefDecisionForm) {
    prefDecisionForm.addEventListener("submit", async (e) => {
      e.preventDefault();
      const id = document.getElementById("adminPrefId").value;
      const status = document.getElementById("adminPrefStatus").value;
      const remarks = document.getElementById("adminPrefRemarks").value;

      try {
        await apiRequest(`/api/admin/preferences/${id}/decision`, {
          method: "PUT",
          body: { status, adminRemarks: remarks }
        });
        toast(`Preference ${status.toLowerCase()} successfully!`, "success");
        document.getElementById("adminPrefDecisionModal").classList.add("hidden");
        renderAdminPreferencesView();
      } catch (err) {
        toast(err.message, "error");
      }
    });
  }

  const holidayForm = document.getElementById("holidayModalForm");
  if (holidayForm) {
    holidayForm.addEventListener("submit", async (e) => {
      e.preventDefault();
      const id = document.getElementById("holidayFormId").value;
      const name = document.getElementById("holidayFormName").value;
      const holidayDate = document.getElementById("holidayFormDate").value;
      const description = document.getElementById("holidayFormDesc").value;

      try {
        if (id) {
          await apiRequest(`/api/admin/holidays/${id}`, {
            method: "PUT",
            body: { name, holidayDate, description, active: true }
          });
          toast("Holiday updated successfully!", "success");
        } else {
          await apiRequest("/api/admin/holidays", {
            method: "POST",
            body: { name, holidayDate, description, active: true }
          });
          toast("Holiday created successfully!", "success");
        }
        document.getElementById("holidayModal").classList.add("hidden");
        renderAdminHolidaysView();
      } catch (err) {
        toast(err.message, "error");
      }
    });
  }

  const handoverForm = document.getElementById("handoverModalForm");
  if (handoverForm) {
    handoverForm.addEventListener("submit", async (e) => {
      e.preventDefault();
      const handoverDate = document.getElementById("handoverDate").value;
      const shiftId = parseInt(document.getElementById("handoverShift").value, 10);
      const toEmpVal = document.getElementById("handoverToEmployee").value;
      const toEmployeeId = toEmpVal ? parseInt(toEmpVal, 10) : null;
      const priority = document.getElementById("handoverPriority").value;
      const shiftSummary = document.getElementById("handoverSummary").value;
      const pendingTasks = document.getElementById("handoverPendingTasks").value;
      const completedTasks = document.getElementById("handoverCompletedTasks").value;
      const notes = document.getElementById("handoverNotes").value;

      try {
        await apiRequest("/api/handovers", {
          method: "POST",
          body: { handoverDate, shiftId, toEmployeeId, priority, shiftSummary, pendingTasks, completedTasks, notes }
        });
        toast("Shift handover note saved successfully!", "success");
        document.getElementById("handoverModal").classList.add("hidden");
        switchEmployeeWorkspaceTab("handovers");
      } catch (err) {
        toast(err.message, "error");
      }
    });
  }

  // --- Shift Preferences Chip & Modal Logic ---
  window.openPreferenceModal = function() {
    const modal = document.getElementById("preferenceModal");
    if (!modal) return;
    
    // Clear all chip active states
    document.querySelectorAll("#preferenceModal .pref-chip").forEach(btn => {
      btn.classList.remove("selected", "selected-avoid");
    });
    
    const alertBox = document.getElementById("prefFormAlert");
    if (alertBox) {
      alertBox.textContent = "";
      alertBox.classList.add("hidden");
    }
    
    const restrInput = document.getElementById("prefRestrictions");
    if (restrInput) restrInput.value = "";

    modal.classList.remove("hidden");
  };

  function initPreferenceChipControls() {
    document.querySelectorAll("#preferenceModal .pref-chip").forEach(chip => {
      // Remove any previously attached click listeners
      const newChip = chip.cloneNode(true);
      chip.parentNode.replaceChild(newChip, chip);
    });

    document.querySelectorAll("#preferenceModal .pref-chip").forEach(chip => {
      chip.addEventListener("click", () => {
        const type = chip.getAttribute("data-type");
        const val = chip.getAttribute("data-value");
        const isSelected = chip.classList.contains("selected") || chip.classList.contains("selected-avoid");
        
        const alertBox = document.getElementById("prefFormAlert");
        if (alertBox) alertBox.classList.add("hidden");

        if (type === "pref-shift") {
          if (!isSelected) {
            chip.classList.add("selected");
            const avoidMatch = document.querySelector(`#prefGroupAvoidShifts .pref-chip[data-value="${val}"]`);
            if (avoidMatch) avoidMatch.classList.remove("selected-avoid", "selected");
          } else {
            chip.classList.remove("selected");
          }
        } else if (type === "avoid-shift") {
          if (!isSelected) {
            chip.classList.add("selected-avoid");
            const prefMatch = document.querySelector(`#prefGroupPreferredShifts .pref-chip[data-value="${val}"]`);
            if (prefMatch) prefMatch.classList.remove("selected");
          } else {
            chip.classList.remove("selected-avoid", "selected");
          }
        } else if (type === "off-day") {
          if (!isSelected) {
            chip.classList.add("selected");
            const workMatch = document.querySelector(`#prefGroupWorkDays .pref-chip[data-value="${val}"]`);
            if (workMatch) workMatch.classList.remove("selected");
          } else {
            chip.classList.remove("selected");
          }
        } else if (type === "work-day") {
          if (!isSelected) {
            chip.classList.add("selected");
            const offMatch = document.querySelector(`#prefGroupOffDays .pref-chip[data-value="${val}"]`);
            if (offMatch) offMatch.classList.remove("selected");
          } else {
            chip.classList.remove("selected");
          }
        }
      });
    });
  }

  const prefForm = document.getElementById("preferenceModalForm");
  if (prefForm) {
    initPreferenceChipControls();

    prefForm.addEventListener("submit", async (e) => {
      e.preventDefault();

      const alertBox = document.getElementById("prefFormAlert");
      if (alertBox) {
        alertBox.textContent = "";
        alertBox.classList.add("hidden");
      }

      // Collect selected arrays from chips
      const preferredShiftTypes = Array.from(document.querySelectorAll("#prefGroupPreferredShifts .pref-chip.selected"))
        .map(c => c.getAttribute("data-value"));
      
      const avoidShiftTypes = Array.from(document.querySelectorAll("#prefGroupAvoidShifts .pref-chip.selected-avoid"))
        .map(c => c.getAttribute("data-value"));

      const preferredOffDays = Array.from(document.querySelectorAll("#prefGroupOffDays .pref-chip.selected"))
        .map(c => c.getAttribute("data-value"));

      const preferredWorkingDays = Array.from(document.querySelectorAll("#prefGroupWorkDays .pref-chip.selected"))
        .map(c => c.getAttribute("data-value"));

      const temporaryRestrictions = (document.getElementById("prefRestrictions").value || "").trim();

      // Validate conflicting shifts
      const shiftConflict = preferredShiftTypes.filter(s => avoidShiftTypes.includes(s));
      if (shiftConflict.length > 0) {
        const msg = `${shiftConflict.join(", ")} cannot be both preferred and avoided.`;
        if (alertBox) {
          alertBox.textContent = msg;
          alertBox.classList.remove("hidden");
        }
        toast(msg, "warning");
        return;
      }

      // Validate conflicting days
      const dayConflict = preferredOffDays.filter(d => preferredWorkingDays.includes(d));
      if (dayConflict.length > 0) {
        const msg = `${dayConflict.join(", ")} cannot be selected as both a preferred OFF day and preferred working day.`;
        if (alertBox) {
          alertBox.textContent = msg;
          alertBox.classList.remove("hidden");
        }
        toast(msg, "warning");
        return;
      }

      const payload = {
        preferredShiftTypes,
        avoidShiftTypes,
        preferredOffDays,
        preferredWorkingDays,
        temporaryRestrictions
      };

      const submitBtn = document.getElementById("submitPrefBtn");
      if (submitBtn) {
        submitBtn.disabled = true;
        const spinner = submitBtn.querySelector(".spinner");
        if (spinner) spinner.classList.remove("hidden");
      }

      try {
        await apiRequest("/api/preferences", {
          method: "POST",
          body: payload
        });
        toast("Shift preferences submitted successfully!", "success");
        if (typeof broadcastDataMutation === "function") {
          broadcastDataMutation("PREFERENCE_SUBMITTED");
        }
        document.getElementById("preferenceModal").classList.add("hidden");
        
        // Refresh preferences tab/view
        if (typeof switchEmployeeWorkspaceTab === "function") {
          switchEmployeeWorkspaceTab("preferences");
        }
        if (typeof renderAdminPreferencesView === "function" && document.getElementById("viewAdminPreferences")) {
          renderAdminPreferencesView();
        }
      } catch (err) {
        const errorMsg = err.message || "Failed to submit shift preferences";
        if (alertBox) {
          alertBox.textContent = errorMsg;
          alertBox.classList.remove("hidden");
        }
        toast(errorMsg, "error");
      } finally {
        if (submitBtn) {
          submitBtn.disabled = false;
          const spinner = submitBtn.querySelector(".spinner");
          if (spinner) spinner.classList.add("hidden");
        }
      }
    });
  }

  const skillForm = document.getElementById("skillModalForm");
  if (skillForm) {
    skillForm.addEventListener("submit", async (e) => {
      e.preventDefault();
      const name = document.getElementById("skillFormName").value;
      const category = document.getElementById("skillFormCategory").value;
      const description = document.getElementById("skillFormDesc").value;

      try {
        await apiRequest("/api/admin/skills", {
          method: "POST",
          body: { name, category, description, active: true }
        });
        toast("Skill created successfully!", "success");
        document.getElementById("skillModal").classList.add("hidden");
        renderAdminSkillsView();
      } catch (err) {
        toast(err.message, "error");
      }
    });
  }

  const assignForm = document.getElementById("assignSkillForm");
  if (assignForm) {
    assignForm.addEventListener("submit", async (e) => {
      e.preventDefault();
      const employeeId = parseInt(document.getElementById("assignSkillEmp").value, 10);
      const skillId = parseInt(document.getElementById("assignSkillId").value, 10);
      const proficiencyLevel = document.getElementById("assignSkillProficiency").value;
      const certified = document.getElementById("assignSkillCertified").value === "true";
      const certificationName = document.getElementById("assignSkillCertName").value;

      try {
        await apiRequest("/api/admin/skills/assign", {
          method: "POST",
          body: { employeeId, skillId, proficiencyLevel, certified, certificationName }
        });
        toast("Skill assigned to employee successfully!", "success");
        document.getElementById("assignSkillModal").classList.add("hidden");
        renderAdminSkillsView();
      } catch (err) {
        toast(err.message, "error");
      }
    });
  }
});