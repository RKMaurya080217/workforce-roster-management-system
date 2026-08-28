package com.weeklyroster.service;

import com.weeklyroster.dto.ApplicablePreference;
import com.weeklyroster.dto.response.ConflictItem;
import com.weeklyroster.dto.response.RosterHealthReport;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.Gender;
import com.weeklyroster.entity.LeaveRequest;
import com.weeklyroster.entity.LeaveStatus;
import com.weeklyroster.entity.RosterAssignment;
import com.weeklyroster.entity.RosterCycle;
import com.weeklyroster.entity.RosterStatus;
import com.weeklyroster.entity.Shift;
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.EmployeePreferenceRepository;
import com.weeklyroster.repository.LeaveRequestRepository;
import com.weeklyroster.repository.RosterAssignmentRepository;
import com.weeklyroster.repository.RosterCycleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class RosterHealthService {

    private final RosterCycleRepository cycleRepository;
    private final RosterAssignmentRepository assignmentRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final EmployeePreferenceRepository preferenceRepository;

    @Autowired
    public RosterHealthService(RosterCycleRepository cycleRepository,
                               RosterAssignmentRepository assignmentRepository,
                               LeaveRequestRepository leaveRequestRepository,
                               @Autowired(required = false) EmployeePreferenceRepository preferenceRepository) {
        this.cycleRepository = cycleRepository;
        this.assignmentRepository = assignmentRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.preferenceRepository = preferenceRepository;
    }

    public RosterHealthService(RosterCycleRepository cycleRepository,
                               RosterAssignmentRepository assignmentRepository,
                               LeaveRequestRepository leaveRequestRepository) {
        this(cycleRepository, assignmentRepository, leaveRequestRepository, null);
    }

    @Transactional(readOnly = true)
    public RosterHealthReport getCycleHealth(Long cycleId) {
        RosterCycle cycle = cycleRepository.findById(cycleId)
                .orElseThrow(() -> new ResourceNotFoundException("Roster cycle not found with id: " + cycleId));
        List<RosterAssignment> assignments = assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(cycle);
        return evaluateHealth(cycle, assignments);
    }

    public RosterHealthReport evaluateHealth(RosterCycle cycle, List<RosterAssignment> assignments) {
        List<ConflictItem> conflicts = new ArrayList<>();

        if (assignments == null || assignments.isEmpty()) {
            conflicts.add(new ConflictItem(
                    cycle.getStartDate(), null, "All Staff", null,
                    "NO_ASSIGNMENTS", "0 assignments", "42+ assignments",
                    "Roster cycle has no generated shift assignments", "CRITICAL",
                    "Generate roster assignments before publishing", false
            ));
            return new RosterHealthReport(
                    cycle.getId(), cycle.getStartDate(), cycle.getEndDate(),
                    cycle.getStatus() != null ? cycle.getStatus() : RosterStatus.GENERATED,
                    false, "NOT READY TO PUBLISH - NO ASSIGNMENTS",
                    "FAILED", "FAILED", "FAILED", "FAILED", "FAILED", "FAILED", "FAILED", "FAILED", "FAILED",
                    1, 0, 0, 0, 0, conflicts,
                    0.0, 0.0, "0 / 0", "INVALID",
                    0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                    "INVALID — HARD CONSTRAINT FAILURE",
                    Map.of("Coverage", "FAILED", "12-hour Rest", "FAILED", "Night Rule", "FAILED", "Female Shift Restrictions", "FAILED", "Approved Leave", "FAILED"),
                    null, null, null, null, null
            );
        }

        // Grouping data structures
        Map<LocalDate, Map<ShiftType, List<RosterAssignment>>> dateShiftMap = new TreeMap<>();
        Map<Long, List<RosterAssignment>> empMap = new LinkedHashMap<>();
        Map<String, Integer> duplicateCheckMap = new HashMap<>();

        for (RosterAssignment a : assignments) {
            LocalDate d = a.getRosterDate();
            dateShiftMap.computeIfAbsent(d, k -> new EnumMap<>(ShiftType.class));
            if (!a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() != ShiftType.OFF) {
                dateShiftMap.get(d).computeIfAbsent(a.getShift().getShiftType(), k -> new ArrayList<>()).add(a);
            }

            if (a.getEmployee() != null) {
                empMap.computeIfAbsent(a.getEmployee().getId(), k -> new ArrayList<>()).add(a);
                String dupKey = a.getEmployee().getId() + "_" + d;
                duplicateCheckMap.put(dupKey, duplicateCheckMap.getOrDefault(dupKey, 0) + 1);
            }
        }

        // 1. Minimum Shift Coverage Check by Date (Hard Constraint)
        boolean coverageOk = true;
        int totalDaysEvaluated = 0;
        int coveredDaysCount = 0;

        for (Map.Entry<LocalDate, Map<ShiftType, List<RosterAssignment>>> entry : dateShiftMap.entrySet()) {
            totalDaysEvaluated++;
            LocalDate date = entry.getKey();
            Map<ShiftType, List<RosterAssignment>> shiftMap = entry.getValue();

            int morningCount = shiftMap.getOrDefault(ShiftType.MORNING, Collections.emptyList()).size();
            int generalCount = shiftMap.getOrDefault(ShiftType.GENERAL, Collections.emptyList()).size();
            int eveningCount = shiftMap.getOrDefault(ShiftType.EVENING, Collections.emptyList()).size();
            int nightCount = shiftMap.getOrDefault(ShiftType.NIGHT, Collections.emptyList()).size();

            boolean dayOk = true;
            if (morningCount < 1) {
                coverageOk = false;
                dayOk = false;
                conflicts.add(new ConflictItem(
                        date, null, "Morning Staffing", ShiftType.MORNING,
                        "MIN_COVERAGE_MORNING", "0 assigned", ">= 1 assigned",
                        "Morning shift has 0 assigned staff on " + date,
                        "CRITICAL", "Assign at least 1 employee to Morning shift", false
                ));
            }
            if (generalCount < 1) {
                coverageOk = false;
                dayOk = false;
                conflicts.add(new ConflictItem(
                        date, null, "General Staffing", ShiftType.GENERAL,
                        "MIN_COVERAGE_GENERAL", "0 assigned", ">= 1 assigned",
                        "General shift has 0 assigned staff on " + date,
                        "HIGH", "Assign at least 1 employee to General shift", false
                ));
            }
            if (eveningCount < 1) {
                coverageOk = false;
                dayOk = false;
                conflicts.add(new ConflictItem(
                        date, null, "Evening Staffing", ShiftType.EVENING,
                        "MIN_COVERAGE_EVENING", "0 assigned", ">= 1 assigned",
                        "Evening shift has 0 assigned staff on " + date,
                        "CRITICAL", "Assign at least 1 employee to Evening shift", false
                ));
            }
            if (nightCount == 0) {
                coverageOk = false;
                dayOk = false;
                conflicts.add(new ConflictItem(
                        date, null, "Night Staffing", ShiftType.NIGHT,
                        "MIN_COVERAGE_NIGHT", "0 assigned", "Exactly 1 assigned",
                        "Night shift has 0 assigned staff on " + date,
                        "CRITICAL", "Assign exactly 1 eligible male employee to Night shift", false
                ));
            } else if (nightCount > 1) {
                coverageOk = false;
                dayOk = false;
                conflicts.add(new ConflictItem(
                        date, null, "Night Staffing", ShiftType.NIGHT,
                        "EXACT_NIGHT_COVERAGE", nightCount + " assigned", "Exactly 1 assigned",
                        "Night shift has " + nightCount + " staff assigned (strictly 1 required)",
                        "CRITICAL", "Reduce Night shift assignments to exactly 1 staff member", false
                ));
            }
            if (dayOk) coveredDaysCount++;
        }
        double coveragePercentage = totalDaysEvaluated > 0 ? Math.round((coveredDaysCount * 100.0 / totalDaysEvaluated) * 10.0) / 10.0 : 100.0;

        // 2. Duplicate / Overlapping Assignment Check
        boolean duplicatesOk = true;
        for (Map.Entry<String, Integer> dupEntry : duplicateCheckMap.entrySet()) {
            if (dupEntry.getValue() > 1) {
                duplicatesOk = false;
                String[] parts = dupEntry.getKey().split("_");
                Long empId = Long.parseLong(parts[0]);
                LocalDate date = LocalDate.parse(parts[1]);
                conflicts.add(new ConflictItem(
                        date, empId, "Employee #" + empId, null,
                        "DUPLICATE_ASSIGNMENT", dupEntry.getValue() + " assignments", "1 assignment",
                        "Employee has multiple roster assignments on the same date",
                        "CRITICAL", "Remove duplicate assignments", false
                ));
            }
        }

        // 3. 12-Hour Rest Rules, Night Limits, Gender Compliance, Weekly OFF
        boolean restOk = true;
        boolean nightLimitOk = true;
        boolean genderOk = true;
        boolean weeklyOffOk = true;
        int totalTransitionsChecked = 0;
        int validRestTransitionsCount = 0;

        List<RosterHealthReport.WorkloadEmployeeItem> workloadItems = new ArrayList<>();
        List<RosterHealthReport.NightEmployeeItem> nightItems = new ArrayList<>();
        List<RosterHealthReport.OffEmployeeItem> offItems = new ArrayList<>();
        int femaleNightCount = 0;
        int totalNightDuties = 0;

        for (List<RosterAssignment> empAssignments : empMap.values()) {
            if (empAssignments.isEmpty()) continue;
            empAssignments.sort(Comparator.comparing(RosterAssignment::getRosterDate));
            Employee emp = empAssignments.get(0).getEmployee();
            String empName = emp.getFirstName() + " " + emp.getLastName();

            int dutyDays = 0;
            int dutyHours = 0;
            int nightCount = 0;
            int eveningCount = 0;
            int weekendCount = 0;
            int weeklyOffCount = 0;
            int leaveCount = 0;
            List<LocalDate> offDates = new ArrayList<>();

            for (int i = 0; i < empAssignments.size(); i++) {
                RosterAssignment cur = empAssignments.get(i);
                LocalDate d = cur.getRosterDate();
                boolean isWeekend = (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY);
                boolean isLeave = cur.isOnLeave();
                boolean isWeeklyOff = !isLeave && (cur.isWeeklyOff() || (cur.getShift() != null && cur.getShift().getShiftType() == ShiftType.OFF));

                if (isLeave) {
                    leaveCount++;
                } else if (isWeeklyOff) {
                    weeklyOffCount++;
                    offDates.add(d);
                } else {
                    dutyDays++;
                    ShiftType curType = cur.getShift().getShiftType();
                    dutyHours += (curType == ShiftType.NIGHT ? 9 : 8);
                    if (isWeekend) weekendCount++;

                    if (curType == ShiftType.NIGHT) {
                        nightCount++;
                        totalNightDuties++;
                    } else if (curType == ShiftType.EVENING) {
                        eveningCount++;
                    }

                    // Gender check: Female staff cannot work Evening or Night
                    if (emp.getGender() == Gender.FEMALE && (curType == ShiftType.EVENING || curType == ShiftType.NIGHT)) {
                        genderOk = false;
                        if (curType == ShiftType.NIGHT) femaleNightCount++;
                        conflicts.add(new ConflictItem(
                                d, emp.getId(), empName, curType,
                                "FEMALE_DAY_ONLY", curType.name(), "MORNING or GENERAL",
                                "Female staff assigned to restricted " + curType + " shift. Only day shifts permitted",
                                "CRITICAL", "Reassign female staff to Morning, General or Weekly Off", false
                        ));
                    }

                    // Rest Rule check with subsequent day
                    if (i < empAssignments.size() - 1) {
                        RosterAssignment next = empAssignments.get(i + 1);
                        boolean isNextOff = next.isWeeklyOff() || next.isOnLeave() || next.getShift() == null || next.getShift().getShiftType() == ShiftType.OFF;
                        if (!isNextOff) {
                            totalTransitionsChecked++;
                            ShiftType nextType = next.getShift().getShiftType();

                            // Night (ends 07:00) -> Morning (07:00) = 0h rest
                            if (curType == ShiftType.NIGHT && nextType == ShiftType.MORNING) {
                                restOk = false;
                                conflicts.add(new ConflictItem(
                                        next.getRosterDate(), emp.getId(), empName, nextType,
                                        "REST_INTERVAL_12H", "0h rest (NIGHT -> MORNING)", ">= 12h rest",
                                        "Night shift followed immediately by Morning shift violates 12h rest rule (0h rest)",
                                        "CRITICAL", "Schedule a rest day or evening shift after Night duty", false
                                ));
                            }
                            // Night (ends 07:00) -> General (09:30) = 2.5h rest
                            else if (curType == ShiftType.NIGHT && nextType == ShiftType.GENERAL) {
                                restOk = false;
                                conflicts.add(new ConflictItem(
                                        next.getRosterDate(), emp.getId(), empName, nextType,
                                        "REST_INTERVAL_12H", "2.5h rest (NIGHT -> GENERAL)", ">= 12h rest",
                                        "Night shift followed by General shift violates 12h rest rule (2.5h rest)",
                                        "CRITICAL", "Schedule a rest day or evening shift after Night duty", false
                                ));
                            }
                            // Evening (ends 22:00) -> Morning (07:00) = 9h rest
                            else if (curType == ShiftType.EVENING && nextType == ShiftType.MORNING) {
                                restOk = false;
                                conflicts.add(new ConflictItem(
                                        next.getRosterDate(), emp.getId(), empName, nextType,
                                        "REST_INTERVAL_12H", "9h rest (EVENING -> MORNING)", ">= 12h rest",
                                        "Evening shift followed by Morning shift violates 12h rest rule (9h rest)",
                                        "CRITICAL", "Reassign morning shift to general or evening duty", false
                                ));
                            } else {
                                validRestTransitionsCount++;
                            }
                        }
                    }
                }
            }

            // Max 2 Night shifts per cycle check
            if (nightCount > 2) {
                nightLimitOk = false;
                conflicts.add(new ConflictItem(
                        cycle.getStartDate(), emp.getId(), empName, ShiftType.NIGHT,
                        "MAX_NIGHT_LIMIT", nightCount + " night shifts", "<= 2 night shifts",
                        "Employee exceeds maximum allowable Night shifts in this cycle (" + nightCount + " > 2)",
                        "CRITICAL", "Reassign excess Night shifts to other eligible male staff", false
                ));
            }

            // Weekly OFF check: Exactly 1 Weekly OFF required per employee per cycle
            boolean empOffCompliant = (weeklyOffCount == 1) || (leaveCount >= 7);
            if (weeklyOffCount > 1) {
                weeklyOffOk = false;
                String datesStr = offDates.stream().map(LocalDate::toString).collect(Collectors.joining(", "));
                conflicts.add(new ConflictItem(
                        cycle.getStartDate(), emp.getId(), empName, null,
                        "DUPLICATE_WEEKLY_OFF", weeklyOffCount + " Weekly OFFs (" + datesStr + ")", "Exactly 1 Weekly OFF",
                        "Employee " + empName + " has " + weeklyOffCount + " weekly OFF assignments in cycle (expected exactly 1). OFF dates: " + datesStr,
                        "CRITICAL", "Reassign excess Weekly OFF days to working shifts", false
                ));
            } else if (weeklyOffCount == 0 && leaveCount < 7) {
                weeklyOffOk = false;
                conflicts.add(new ConflictItem(
                        cycle.getStartDate(), emp.getId(), empName, null,
                        "MIN_WEEKLY_OFF", "0 Weekly OFFs", "Exactly 1 Weekly OFF",
                        "Employee " + empName + " has 0 scheduled weekly OFF days in the 7-day roster cycle",
                        "HIGH", "Assign exactly one Weekly OFF day", false
                ));
            }

            // Workload status classification
            String workloadStatus = dutyDays == 6 ? "Balanced" : (dutyDays > 6 ? "Higher" : "Lower");
            workloadItems.add(new RosterHealthReport.WorkloadEmployeeItem(
                    empName, emp.getEmployeeCode(), dutyDays, dutyHours, nightCount, eveningCount, weekendCount, workloadStatus
            ));

            if (emp.getGender() == Gender.MALE) {
                boolean maleNightCompliant = (nightCount >= 1 && nightCount <= 2) || (leaveCount >= 7);
                nightItems.add(new RosterHealthReport.NightEmployeeItem(
                        empName, emp.getEmployeeCode(), nightCount, maleNightCompliant
                ));
            }

            String offDatesStr = offDates.stream().map(LocalDate::toString).collect(Collectors.joining(", "));
            offItems.add(new RosterHealthReport.OffEmployeeItem(
                    empName, emp.getEmployeeCode(), weeklyOffCount, offDatesStr.isEmpty() ? "None" : offDatesStr, true, empOffCompliant
            ));
        }

        double restCompliancePercentage = totalTransitionsChecked > 0
                ? Math.round((validRestTransitionsCount * 100.0 / totalTransitionsChecked) * 10.0) / 10.0
                : 100.0;

        // 4. Approved Leave Compliance Check
        boolean leaveOk = true;
        try {
            List<LeaveRequest> approvedLeaves = leaveRequestRepository.findApprovedLeavesInCycle(
                    LeaveStatus.APPROVED, cycle.getStartDate(), cycle.getEndDate());
            for (LeaveRequest leave : approvedLeaves) {
                Long empId = leave.getEmployee().getId();
                LocalDate curDate = leave.getStartDate().isBefore(cycle.getStartDate()) ? cycle.getStartDate() : leave.getStartDate();
                LocalDate maxDate = leave.getEndDate().isAfter(cycle.getEndDate()) ? cycle.getEndDate() : leave.getEndDate();

                while (!curDate.isAfter(maxDate)) {
                    final LocalDate d = curDate;
                    RosterAssignment match = assignments.stream()
                            .filter(a -> a.getEmployee() != null && a.getEmployee().getId().equals(empId) && a.getRosterDate().equals(d))
                            .findFirst().orElse(null);

                    if (match != null && !match.isOnLeave()) {
                        leaveOk = false;
                        conflicts.add(new ConflictItem(
                                d, empId, leave.getEmployee().getFirstName() + " " + leave.getEmployee().getLastName(), null,
                                "LEAVE_NON_COMPLIANCE", "Working / Not marked ON LEAVE", "ON LEAVE",
                                "Employee has approved leave on " + d + " but roster assignment is not marked as ON LEAVE",
                                "CRITICAL", "Synchronize leave with roster assignment", false
                        ));
                    }
                    curDate = curDate.plusDays(1);
                }
            }
        } catch (Exception ignored) {}

        // 5. Shift Continuity Calculation
        double totalContinuityTransitions = 0;
        double continuityPoints = 0;
        int continuousBlocksCount = 0;
        List<RosterHealthReport.ContinuityIssueItem> continuityIssues = new ArrayList<>();

        for (List<RosterAssignment> empAssignments : empMap.values()) {
            ShiftType lastType = null;
            ShiftType prevPrevType = null;
            Employee emp = empAssignments.get(0).getEmployee();
            String empName = emp.getFirstName() + " " + emp.getLastName();

            for (RosterAssignment a : empAssignments) {
                if (a.isWeeklyOff() || a.isOnLeave() || a.getShift() == null || a.getShift().getShiftType() == ShiftType.OFF) {
                    continue;
                }
                ShiftType curType = a.getShift().getShiftType();
                if (lastType != null) {
                    totalContinuityTransitions++;
                    if (lastType == curType) {
                        continuityPoints += 100.0;
                        continuousBlocksCount++;
                    } else if ((lastType == ShiftType.MORNING && curType == ShiftType.GENERAL) ||
                               (lastType == ShiftType.GENERAL && curType == ShiftType.MORNING) ||
                               (lastType == ShiftType.GENERAL && curType == ShiftType.EVENING) ||
                               (lastType == ShiftType.EVENING && curType == ShiftType.GENERAL)) {
                        continuityPoints += 75.0;
                    } else {
                        continuityPoints += 40.0;
                    }

                    if (prevPrevType != null && prevPrevType == curType && lastType != curType) {
                        continuityIssues.add(new RosterHealthReport.ContinuityIssueItem(
                                empName, prevPrevType.name().substring(0, 1) + " → " + lastType.name().substring(0, 1) + " → " + curType.name().substring(0, 1),
                                "Frequent switching pattern on " + a.getRosterDate()
                        ));
                    }
                }
                prevPrevType = lastType;
                lastType = curType;
            }
        }
        double shiftContinuityScore = totalContinuityTransitions > 0
                ? Math.round((continuityPoints / totalContinuityTransitions) * 10.0) / 10.0
                : 100.0;

        // 6. Workload Balance Score
        List<Integer> workingDaysList = workloadItems.stream().map(RosterHealthReport.WorkloadEmployeeItem::dutyDays).toList();
        int minWorking = workingDaysList.stream().mapToInt(Integer::intValue).min().orElse(6);
        int maxWorking = workingDaysList.stream().mapToInt(Integer::intValue).max().orElse(6);
        int spread = maxWorking - minWorking;
        double workloadBalanceScore = spread <= 1 ? 100.0 : Math.max(60.0, 100.0 - (spread - 1) * 15.0);

        // 7. Preference Compliance Details
        int totalWorkingAssignments = 0;
        int prefCompatibleCount = 0;
        int prefConflictCount = 0;
        List<RosterHealthReport.PreferenceDetailItem> prefItems = new ArrayList<>();

        for (RosterAssignment a : assignments) {
            if (a.isWeeklyOff() || a.isOnLeave() || a.getShift() == null || a.getShift().getShiftType() == ShiftType.OFF) {
                continue;
            }
            totalWorkingAssignments++;
            String empCode = a.getEmployee() != null ? a.getEmployee().getEmployeeCode() : "EMP";
            String empName = a.getEmployee() != null ? a.getEmployee().getFirstName() + " " + a.getEmployee().getLastName() : "Employee";
            String stName = a.getShift().getShiftType().name();
            String reason = a.getAssignmentReason() != null ? a.getAssignmentReason() : "";

            if (reason.toLowerCase().contains("preferred")) {
                prefCompatibleCount++;
                prefItems.add(new RosterHealthReport.PreferenceDetailItem(empName, empCode, a.getRosterDate(), stName, "PREFERRED", "✓ Preferred shift"));
            } else if (reason.toLowerCase().contains("avoid")) {
                prefConflictCount++;
                prefItems.add(new RosterHealthReport.PreferenceDetailItem(empName, empCode, a.getRosterDate(), stName, "AVOIDED", "⚠ Avoided shift assigned for coverage"));
            } else {
                prefCompatibleCount++;
                prefItems.add(new RosterHealthReport.PreferenceDetailItem(empName, empCode, a.getRosterDate(), stName, "COMPATIBLE", "Operational duty within skills"));
            }
        }
        double prefScore = totalWorkingAssignments > 0
                ? Math.round((prefCompatibleCount * 100.0 / totalWorkingAssignments) * 10.0) / 10.0
                : 100.0;

        // 8. Male Night Distribution & OFF Distribution Percentages
        long compliantMales = nightItems.stream().filter(RosterHealthReport.NightEmployeeItem::compliant).count();
        double nightDistributionPercentage = nightItems.isEmpty() ? 100.0 : Math.round((compliantMales * 100.0 / nightItems.size()) * 10.0) / 10.0;

        long compliantOffs = offItems.stream().filter(RosterHealthReport.OffEmployeeItem::compliant).count();
        double offDistributionPercentage = offItems.isEmpty() ? 100.0 : Math.round((compliantOffs * 100.0 / offItems.size()) * 10.0) / 10.0;

        // Hard Constraints Evaluation
        int criticalCount = (int) conflicts.stream().filter(c -> "CRITICAL".equalsIgnoreCase(c.severity())).count();
        int highCount = (int) conflicts.stream().filter(c -> "HIGH".equalsIgnoreCase(c.severity())).count();
        int mediumCount = (int) conflicts.stream().filter(c -> "MEDIUM".equalsIgnoreCase(c.severity())).count();
        int lowCount = (int) conflicts.stream().filter(c -> "LOW".equalsIgnoreCase(c.severity())).count();
        int infoCount = (int) conflicts.stream().filter(c -> "INFO".equalsIgnoreCase(c.severity())).count();

        boolean hasHardConstraintFailure = (!coverageOk || !restOk || !genderOk || !nightLimitOk || !leaveOk || !duplicatesOk || criticalCount > 0);

        // Quality Health Score (Weighted Formula: 25% Pref, 20% Workload, 20% Continuity, 15% Night, 10% OFF, 10% Safety)
        double healthScore = Math.round((
                (prefScore * 0.25) +
                (workloadBalanceScore * 0.20) +
                (shiftContinuityScore * 0.20) +
                (nightDistributionPercentage * 0.15) +
                (offDistributionPercentage * 0.10) +
                ((hasHardConstraintFailure ? 0.0 : 100.0) * 0.10)
        ) * 10.0) / 10.0;

        String healthScoreStatus;
        String overallValidationStatus;
        if (hasHardConstraintFailure) {
            overallValidationStatus = "INVALID";
            healthScoreStatus = "INVALID — HARD CONSTRAINT FAILURE";
        } else {
            overallValidationStatus = highCount > 0 ? "WARNING" : "VALID";
            if (healthScore >= 90.0) {
                healthScoreStatus = "Excellent";
            } else if (healthScore >= 80.0) {
                healthScoreStatus = "Good";
            } else if (healthScore >= 70.0) {
                healthScoreStatus = "Needs Improvement";
            } else {
                healthScoreStatus = "Poor";
            }
        }

        boolean readyToPublish = !hasHardConstraintFailure;
        String summaryStatus = hasHardConstraintFailure
                ? "NOT READY TO PUBLISH (" + criticalCount + " Hard Constraint Failure" + (criticalCount > 1 ? "s" : "") + ")"
                : (highCount > 0 ? "READY TO PUBLISH (With " + highCount + " Warnings)" : "READY TO PUBLISH (All Rules Passed)");

        Map<String, String> hardConstraintsMap = new LinkedHashMap<>();
        hardConstraintsMap.put("Coverage", coverageOk ? "PASSED" : "FAILED");
        hardConstraintsMap.put("12-hour Rest", restOk ? "PASSED" : "FAILED");
        hardConstraintsMap.put("Night Rule", nightLimitOk ? "PASSED" : "FAILED");
        hardConstraintsMap.put("Female Shift Restrictions", genderOk ? "PASSED" : "FAILED");
        hardConstraintsMap.put("Approved Leave", leaveOk ? "PASSED" : "FAILED");
        hardConstraintsMap.put("Employee Restrictions", duplicatesOk ? "PASSED" : "FAILED");

        String maleNightCoverageStr = nightItems.size() + " male staff scheduled (" + compliantMales + "/" + nightItems.size() + " compliant)";

        RosterHealthReport.PreferenceHealthDetails preferenceDetails = new RosterHealthReport.PreferenceHealthDetails(
                totalWorkingAssignments, prefCompatibleCount, prefConflictCount, prefItems
        );

        String continuityDesc = shiftContinuityScore >= 85.0 ? "Excellent. Most assignments are grouped into continuous shift blocks." : "Fair continuity with some shift rotation.";
        RosterHealthReport.ContinuityHealthDetails continuityDetails = new RosterHealthReport.ContinuityHealthDetails(
                shiftContinuityScore >= 80.0 ? "Excellent" : "Needs Improvement", continuityDesc, (int) totalContinuityTransitions, continuousBlocksCount, continuityIssues
        );

        RosterHealthReport.WorkloadHealthDetails workloadDetails = new RosterHealthReport.WorkloadHealthDetails(workloadItems);

        String nightMsg = femaleNightCount == 0
                ? "✓ Eligible male employees received required Night allocation. Female staff have 0 night duties."
                : "🔴 Night allocation conflict: Female employee assigned to night shift.";
        RosterHealthReport.NightHealthDetails nightDetails = new RosterHealthReport.NightHealthDetails(
                totalNightDuties, nightItems, femaleNightCount, femaleNightCount == 0 && compliantMales == nightItems.size(), nightMsg
        );

        RosterHealthReport.OffHealthDetails offDetails = new RosterHealthReport.OffHealthDetails(
                offItems.size(), (int) compliantOffs, offItems
        );

        return new RosterHealthReport(
                cycle.getId(), cycle.getStartDate(), cycle.getEndDate(),
                cycle.getStatus() != null ? cycle.getStatus() : RosterStatus.GENERATED,
                readyToPublish, summaryStatus,
                coverageOk ? "PASSED" : "FAILED",
                restOk ? "PASSED" : "FAILED",
                nightLimitOk ? "PASSED" : "FAILED",
                genderOk ? "PASSED" : "FAILED",
                leaveOk ? "PASSED" : "FAILED",
                "PASSED",
                duplicatesOk ? "PASSED" : "FAILED",
                weeklyOffOk ? "PASSED" : "WARNING",
                shiftContinuityScore >= 70.0 ? "PASSED" : "WARNING",
                criticalCount, highCount, mediumCount, lowCount, infoCount,
                conflicts, healthScore, prefScore, maleNightCoverageStr,
                overallValidationStatus, shiftContinuityScore, workloadBalanceScore,
                coveragePercentage, restCompliancePercentage, nightDistributionPercentage, offDistributionPercentage,
                healthScoreStatus, hardConstraintsMap,
                preferenceDetails, continuityDetails, workloadDetails, nightDetails, offDetails
        );
    }
}
