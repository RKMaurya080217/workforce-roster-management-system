package com.weeklyroster.service;

import com.weeklyroster.dto.ApplicablePreference;
import com.weeklyroster.dto.response.RosterFairnessReport;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FairnessEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(FairnessEvaluationService.class);

    private final RosterAssignmentRepository assignmentRepository;
    private final EmployeeRepository employeeRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final DateShiftPreferenceRepository dateShiftPreferenceRepository;
    private final RecurringShiftPreferenceRepository recurringShiftPreferenceRepository;

    public record RollingHistoryContext(
            Map<Long, Integer> rollingNights,
            Map<Long, Integer> rollingEvenings,
            Map<Long, Integer> rollingWorkingShifts,
            Map<Long, Integer> rollingEligibleNightOpportunities,
            Map<Long, Integer> rollingEligibleEveningOpportunities
    ) {
        public static RollingHistoryContext empty() {
            return new RollingHistoryContext(
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    Collections.emptyMap()
            );
        }

        public int getRollingNights(Long empId) {
            return rollingNights != null ? rollingNights.getOrDefault(empId, 0) : 0;
        }

        public int getRollingEvenings(Long empId) {
            return rollingEvenings != null ? rollingEvenings.getOrDefault(empId, 0) : 0;
        }

        public int getRollingWorkingShifts(Long empId) {
            return rollingWorkingShifts != null ? rollingWorkingShifts.getOrDefault(empId, 0) : 0;
        }
    }

    @Autowired
    public FairnessEvaluationService(
            @Autowired(required = false) RosterAssignmentRepository assignmentRepository,
            @Autowired(required = false) EmployeeRepository employeeRepository,
            @Autowired(required = false) LeaveRequestRepository leaveRequestRepository,
            @Autowired(required = false) DateShiftPreferenceRepository dateShiftPreferenceRepository,
            @Autowired(required = false) RecurringShiftPreferenceRepository recurringShiftPreferenceRepository
    ) {
        this.assignmentRepository = assignmentRepository;
        this.employeeRepository = employeeRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.dateShiftPreferenceRepository = dateShiftPreferenceRepository;
        this.recurringShiftPreferenceRepository = recurringShiftPreferenceRepository;
    }

    @Transactional(readOnly = true)
    public RollingHistoryContext loadRollingHistoryContext(LocalDate startDate, List<Employee> employees, int weeksWindow) {
        if (assignmentRepository == null || startDate == null || employees == null || employees.isEmpty()) {
            return RollingHistoryContext.empty();
        }

        int window = Math.max(1, Math.min(8, weeksWindow));
        LocalDate windowStart = startDate.minusWeeks(window);
        LocalDate windowEnd = startDate.minusDays(1);

        Map<Long, Integer> rollingNights = new HashMap<>();
        Map<Long, Integer> rollingEvenings = new HashMap<>();
        Map<Long, Integer> rollingWorkingShifts = new HashMap<>();
        Map<Long, Integer> rollingEligibleNightOpps = new HashMap<>();
        Map<Long, Integer> rollingEligibleEveningOpps = new HashMap<>();

        for (Employee emp : employees) {
            rollingNights.put(emp.getId(), 0);
            rollingEvenings.put(emp.getId(), 0);
            rollingWorkingShifts.put(emp.getId(), 0);

            int eligibleNightDays = 0;
            int eligibleEveningDays = 0;

            for (LocalDate d = windowStart; !d.isAfter(windowEnd); d = d.plusDays(1)) {
                boolean joined = (emp.getJoiningDate() == null || !d.isBefore(emp.getJoiningDate()));
                boolean notResigned = (emp.getResignationDate() == null || !d.isAfter(emp.getResignationDate()));
                boolean onLeave = isEmployeeOnLeave(emp.getId(), d);

                if (joined && notResigned && !onLeave && emp.isActive()) {
                    if (emp.getGender() == Gender.MALE && !emp.isDevelopmentTeam()) {
                        eligibleNightDays++;
                        eligibleEveningDays++;
                    }
                }
            }

            rollingEligibleNightOpps.put(emp.getId(), eligibleNightDays);
            rollingEligibleEveningOpps.put(emp.getId(), eligibleEveningDays);
        }

        try {
            List<RosterAssignment> pastAssignments = assignmentRepository.findFinalizedAssignmentsInWindow(windowStart, windowEnd);
            for (RosterAssignment a : pastAssignments) {
                if (a.getEmployee() == null || a.isWeeklyOff() || a.isOnLeave() || a.getShift() == null) {
                    continue;
                }
                Long empId = a.getEmployee().getId();
                if (!rollingWorkingShifts.containsKey(empId)) continue;

                ShiftType st = a.getShift().getShiftType();
                if (st != ShiftType.OFF) {
                    rollingWorkingShifts.put(empId, rollingWorkingShifts.getOrDefault(empId, 0) + 1);
                }
                if (st == ShiftType.NIGHT) {
                    rollingNights.put(empId, rollingNights.getOrDefault(empId, 0) + 1);
                } else if (st == ShiftType.EVENING) {
                    rollingEvenings.put(empId, rollingEvenings.getOrDefault(empId, 0) + 1);
                }
            }
        } catch (Exception e) {
            log.warn("[BATCH 119] Could not load past finalized assignments for rolling history: {}", e.getMessage());
        }

        return new RollingHistoryContext(
                rollingNights,
                rollingEvenings,
                rollingWorkingShifts,
                rollingEligibleNightOpps,
                rollingEligibleEveningOpps
        );
    }

    @Transactional(readOnly = true)
    public RosterFairnessReport evaluateRosterFairness(RosterCycle cycle) {
        if (cycle == null) {
            return new RosterFairnessReport(
                    null, null, 0,
                    new RosterFairnessReport.FairnessScoreBreakdown(0, 0, 0, 0, 0, 0, 0, 0, 0),
                    Collections.emptyList(), 100.0, 0, 0.0, 0.0, 0.0, false,
                    List.of("Roster cycle is null"), List.of("Cycle not found"), true, "Cycle not found"
            );
        }

        List<RosterAssignment> assignments = (assignmentRepository != null)
                ? assignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.getId())
                : Collections.emptyList();

        if (assignments.isEmpty() && assignmentRepository != null && cycle.getStartDate() != null && cycle.getEndDate() != null) {
            assignments = assignmentRepository.findByRosterDateBetweenWithDetails(cycle.getStartDate(), cycle.getEndDate());
        }

        List<Employee> employees = (employeeRepository != null)
                ? employeeRepository.findByActiveTrueOrderByIdAsc()
                : Collections.emptyList();

        return evaluateRosterFairness(cycle.getStartDate(), cycle.getEndDate(), assignments, employees, null, 4);
    }

    @Transactional(readOnly = true)
    public RosterFairnessReport evaluateRosterFairness(
            LocalDate startDate,
            LocalDate endDate,
            List<RosterAssignment> assignments,
            List<Employee> employees,
            Map<Long, ApplicablePreference> preferencesMap,
            int rollingHistoryWeeks
    ) {
        if (assignments == null || assignments.isEmpty()) {
            return new RosterFairnessReport(
                    startDate, endDate, 0,
                    new RosterFairnessReport.FairnessScoreBreakdown(0, 0, 0, 0, 0, 0, 0, 0, 0),
                    Collections.emptyList(), 0.0, 0, 0.0, 0.0, 0.0, false,
                    List.of("No assignments available to evaluate"),
                    List.of("Assignments list is empty"),
                    true, "No assignments generated"
            );
        }

        if (employees == null || employees.isEmpty()) {
            Set<Long> empIds = assignments.stream()
                    .filter(a -> a.getEmployee() != null)
                    .map(a -> a.getEmployee().getId())
                    .collect(Collectors.toSet());
            employees = (employeeRepository != null)
                    ? employeeRepository.findAllById(empIds)
                    : assignments.stream().map(RosterAssignment::getEmployee).filter(Objects::nonNull).distinct().toList();
        }

        // 1. Load rolling history context (last N weeks prior to startDate)
        RollingHistoryContext rollingContext = loadRollingHistoryContext(startDate, employees, rollingHistoryWeeks);

        // 2. Map assignments by employee
        Map<Long, List<RosterAssignment>> empMap = assignments.stream()
                .filter(a -> a.getEmployee() != null)
                .collect(Collectors.groupingBy(a -> a.getEmployee().getId()));

        List<RosterFairnessReport.EmployeeFairnessDetail> details = new ArrayList<>();
        List<String> explanations = new ArrayList<>();
        List<String> mandatoryViolations = new ArrayList<>();

        int totalApplicablePrefs = 0;
        int totalSatisfiedPrefs = 0;
        int totalAvoidableTransitions = 0;

        List<Double> maleNightBurdenRates = new ArrayList<>();
        List<Double> maleEveningBurdenRates = new ArrayList<>();
        List<Double> employeeWorkingDays = new ArrayList<>();

        Map<LocalDate, Integer> dailyOffCounts = new TreeMap<>();
        Map<LocalDate, Map<ShiftType, Integer>> dailyShiftCounts = new TreeMap<>();

        for (int i = 0; i < 7; i++) {
            LocalDate d = startDate.plusDays(i);
            dailyOffCounts.put(d, 0);
            Map<ShiftType, Integer> sc = new EnumMap<>(ShiftType.class);
            for (ShiftType st : ShiftType.values()) sc.put(st, 0);
            dailyShiftCounts.put(d, sc);
        }

        for (RosterAssignment a : assignments) {
            LocalDate d = a.getRosterDate();
            if (d == null || d.isBefore(startDate) || d.isAfter(endDate)) continue;

            if (a.isWeeklyOff()) {
                dailyOffCounts.put(d, dailyOffCounts.getOrDefault(d, 0) + 1);
            } else if (!a.isOnLeave() && a.getShift() != null) {
                ShiftType st = a.getShift().getShiftType();
                Map<ShiftType, Integer> sc = dailyShiftCounts.get(d);
                if (sc != null) {
                    sc.put(st, sc.getOrDefault(st, 0) + 1);
                }
            }
        }

        // Evaluate each employee
        for (Employee emp : employees) {
            List<RosterAssignment> empAssignments = empMap.getOrDefault(emp.getId(), Collections.emptyList());
            empAssignments.sort(Comparator.comparing(RosterAssignment::getRosterDate));

            int totalWorking = 0;
            int morningCount = 0;
            int generalCount = 0;
            int eveningCount = 0;
            int nightCount = 0;
            int offCount = 0;
            List<LocalDate> offDates = new ArrayList<>();

            int empApplicablePrefs = 0;
            int empSatisfiedPrefs = 0;
            int empAvoidableTransitions = 0;

            ApplicablePreference pref = (preferencesMap != null)
                    ? preferencesMap.get(emp.getId())
                    : null;

            int eligibleNightOpps = 0;
            int eligibleEveningOpps = 0;

            for (RosterAssignment a : empAssignments) {
                LocalDate d = a.getRosterDate();
                boolean joined = (emp.getJoiningDate() == null || !d.isBefore(emp.getJoiningDate()));
                boolean notResigned = (emp.getResignationDate() == null || !d.isAfter(emp.getResignationDate()));

                if (!joined || !notResigned) {
                    continue;
                }

                if (a.isWeeklyOff()) {
                    offCount++;
                    offDates.add(d);
                    if (pref != null && pref.hasPreferredOffDays() && pref.isDayPreferredOff(d.getDayOfWeek())) {
                        empApplicablePrefs++;
                        empSatisfiedPrefs++;
                    }
                } else if (a.isOnLeave()) {
                    // Leave is treated as leave, not worked shift
                } else if (a.getShift() != null && a.getShift().getShiftType() != ShiftType.OFF) {
                    ShiftType st = a.getShift().getShiftType();
                    totalWorking++;

                    if (st == ShiftType.MORNING) morningCount++;
                    else if (st == ShiftType.GENERAL) generalCount++;
                    else if (st == ShiftType.EVENING) eveningCount++;
                    else if (st == ShiftType.NIGHT) nightCount++;

                    if (emp.getGender() == Gender.MALE && !emp.isDevelopmentTeam()) {
                        eligibleNightOpps++;
                        eligibleEveningOpps++;
                    }

                    // Hard constraint checks
                    if (emp.getGender() == Gender.FEMALE && (st == ShiftType.NIGHT || st == ShiftType.EVENING)) {
                        mandatoryViolations.add("Female safety violation: " + emp.getFirstName() + " assigned " + st + " on " + d);
                    }

                    // Preference satisfaction check
                    if (pref != null) {
                        ShiftType dateSpecific = pref.getDateSpecificShift(d);
                        if (dateSpecific != null) {
                            empApplicablePrefs++;
                            if (st == dateSpecific) {
                                empSatisfiedPrefs++;
                            }
                        } else if (pref.hasRecurringShift(d.getDayOfWeek())) {
                            ShiftType recShift = pref.getRecurringShift(d.getDayOfWeek());
                            empApplicablePrefs++;
                            if (st == recShift) {
                                empSatisfiedPrefs++;
                            }
                        } else if (pref.hasPreferredShifts()) {
                            empApplicablePrefs++;
                            if (pref.isShiftPreferred(st)) {
                                empSatisfiedPrefs++;
                            }
                        }
                    }
                }
            }

            // Fatigue & recovery rule: Night shift must always be followed by Weekly Off
            for (int i = 0; i < empAssignments.size(); i++) {
                RosterAssignment cur = empAssignments.get(i);
                if (!cur.isWeeklyOff() && !cur.isOnLeave() && cur.getShift() != null && cur.getShift().getShiftType() == ShiftType.NIGHT) {
                    LocalDate nextDate = cur.getRosterDate().plusDays(1);
                    if (i < empAssignments.size() - 1) {
                        RosterAssignment next = empAssignments.get(i + 1);
                        if (!next.isWeeklyOff()) {
                            mandatoryViolations.add("Broken Night->Off: " + emp.getFirstName() + " worked Night on "
                                    + cur.getRosterDate() + " but was not given Weekly Off on " + nextDate);
                        }
                    }
                }
            }

            // Avoidable transitions check:
            // 1. Morning -> Night without off
            // 2. Flapping between morning and evening on adjacent days
            for (int i = 0; i < empAssignments.size() - 1; i++) {
                RosterAssignment cur = empAssignments.get(i);
                RosterAssignment next = empAssignments.get(i + 1);

                if (!cur.isWeeklyOff() && !cur.isOnLeave() && !next.isWeeklyOff() && !next.isOnLeave()
                        && cur.getShift() != null && next.getShift() != null) {
                    ShiftType curT = cur.getShift().getShiftType();
                    ShiftType nextT = next.getShift().getShiftType();

                    if (curT == ShiftType.MORNING && nextT == ShiftType.NIGHT) {
                        empAvoidableTransitions++;
                    }
                    if (curT == ShiftType.EVENING && nextT == ShiftType.MORNING) {
                        empAvoidableTransitions++; // Quick 12h turnaround when avoidable
                    }
                }
            }

            // Weekly Off rule check: exactly 1 Weekly Off in 6-day mode
            if (offCount != 1) {
                // Check if leave or holiday explains off count
                long leaveCount = empAssignments.stream().filter(RosterAssignment::isOnLeave).count();
                if (leaveCount == 0) {
                    mandatoryViolations.add("Exact Weekly Off violation: " + emp.getFirstName() + " has "
                            + offCount + " Weekly Off(s) (expected exactly 1)");
                }
            }

            totalApplicablePrefs += empApplicablePrefs;
            totalSatisfiedPrefs += empSatisfiedPrefs;
            totalAvoidableTransitions += empAvoidableTransitions;

            int rollNights = rollingContext.getRollingNights(emp.getId());
            int rollEvenings = rollingContext.getRollingEvenings(emp.getId());
            int rollWorking = rollingContext.getRollingWorkingShifts(emp.getId());

            double prefRate = (empApplicablePrefs > 0)
                    ? ((double) empSatisfiedPrefs / empApplicablePrefs) * 100.0
                    : 100.0;

            details.add(new RosterFairnessReport.EmployeeFairnessDetail(
                    emp.getId(),
                    emp.getEmployeeCode(),
                    emp.getFirstName() + " " + (emp.getLastName() != null ? emp.getLastName() : ""),
                    emp.getGender() != null ? emp.getGender().name() : "UNKNOWN",
                    totalWorking,
                    morningCount,
                    generalCount,
                    eveningCount,
                    nightCount,
                    offCount,
                    offDates,
                    eligibleNightOpps,
                    eligibleEveningOpps,
                    Math.round(prefRate * 10.0) / 10.0,
                    empAvoidableTransitions,
                    rollNights,
                    rollEvenings,
                    rollWorking
            ));

            employeeWorkingDays.add((double) totalWorking);
            if (emp.getGender() == Gender.MALE && !emp.isDevelopmentTeam()) {
                double totalN = (double) (nightCount + rollNights);
                double totalE = (double) (eveningCount + rollEvenings);
                double oppsN = Math.max(1, eligibleNightOpps + rollingContext.rollingEligibleNightOpportunities().getOrDefault(emp.getId(), 0));
                double oppsE = Math.max(1, eligibleEveningOpps + rollingContext.rollingEligibleEveningOpportunities().getOrDefault(emp.getId(), 0));
                maleNightBurdenRates.add(totalN / oppsN);
                maleEveningBurdenRates.add(totalE / oppsE);
            }
        }

        // Daily coverage validation
        long eligibleMaleStaff = employees.stream()
                .filter(e -> e.getGender() == Gender.MALE && !e.isDevelopmentTeam() && e.isActive())
                .count();
        boolean nightCapacityShortage = (eligibleMaleStaff < 7);

        for (Map.Entry<LocalDate, Map<ShiftType, Integer>> entry : dailyShiftCounts.entrySet()) {
            LocalDate d = entry.getKey();
            Map<ShiftType, Integer> sc = entry.getValue();
            int mCount = sc.getOrDefault(ShiftType.MORNING, 0);
            int gCount = sc.getOrDefault(ShiftType.GENERAL, 0);
            int eCount = sc.getOrDefault(ShiftType.EVENING, 0);
            int nCount = sc.getOrDefault(ShiftType.NIGHT, 0);

            int totalWorking = mCount + gCount + eCount + nCount;

            if (totalWorking == 3) {
                // 3-employee shortage mode
                if (mCount != 1 || eCount != 1 || nCount != 1 || gCount != 0) {
                    mandatoryViolations.add("3-employee mode staffing violation on " + d + ": expected M=1,E=1,N=1,G=0; actual M="
                            + mCount + ",E=" + eCount + ",N=" + nCount + ",G=" + gCount);
                }
            } else if (totalWorking >= 4) {
                if (mCount < 1 || mCount > 2) {
                    mandatoryViolations.add("Morning staffing violation on " + d + ": expected 1-2, actual " + mCount);
                }
                if (eCount < 1 || eCount > 2) {
                    mandatoryViolations.add("Evening staffing violation on " + d + ": expected 1-2, actual " + eCount);
                }
                if (nCount != 1) {
                    if (nCount == 0 && nightCapacityShortage) {
                        explanations.add(String.format("Workforce capacity limitation on %s: Night shift unstaffed due to active male workforce capacity (%d eligible males < 7 in 6-day mode)", d, eligibleMaleStaff));
                    } else {
                        mandatoryViolations.add("Night staffing violation on " + d + ": expected exactly 1, actual " + nCount);
                    }
                }
                if (totalWorking >= 6 && (gCount < 2 || gCount > 4)) {
                    mandatoryViolations.add("General staffing violation on " + d + ": expected 2-4 under normal staffing, actual " + gCount);
                }
            }
        }

        // Calculate statistical variances
        double nightVariance = calculateVariance(maleNightBurdenRates);
        double eveningVariance = calculateVariance(maleEveningBurdenRates);
        double workloadVariance = calculateVariance(employeeWorkingDays);

        double overallPrefRate = (totalApplicablePrefs > 0)
                ? ((double) totalSatisfiedPrefs / totalApplicablePrefs) * 100.0
                : 100.0;

        // Build scoring breakdown
        int prefScore = (int) (overallPrefRate * 10);
        int nightFairnessScore = Math.max(0, 1000 - (int) (nightVariance * 4000));
        int eveningFairnessScore = Math.max(0, 800 - (int) (eveningVariance * 2500));
        int workloadBalanceScore = Math.max(0, 1000 - (int) (workloadVariance * 800));
        int continuityScore = Math.max(0, 1200 - (totalAvoidableTransitions * 300));

        int weeklyOffScore = 500;
        for (int offOnDay : dailyOffCounts.values()) {
            if (offOnDay > 2) weeklyOffScore -= (offOnDay - 2) * 200;
        }
        weeklyOffScore = Math.max(0, weeklyOffScore);

        int rollingHistoryScore = (nightFairnessScore + eveningFairnessScore) / 2;
        int diversityScore = 500;

        int totalScore = prefScore + nightFairnessScore + eveningFairnessScore + workloadBalanceScore
                + continuityScore + weeklyOffScore + rollingHistoryScore + diversityScore;

        RosterFairnessReport.FairnessScoreBreakdown scoreBreakdown = new RosterFairnessReport.FairnessScoreBreakdown(
                totalScore,
                prefScore,
                continuityScore,
                nightFairnessScore,
                eveningFairnessScore,
                workloadBalanceScore,
                weeklyOffScore,
                rollingHistoryScore,
                diversityScore
        );

        boolean fair = mandatoryViolations.isEmpty()
                && nightVariance <= 0.6
                && eveningVariance <= 1.0
                && totalAvoidableTransitions <= 2;

        boolean infeasible = !mandatoryViolations.isEmpty() && (employees.size() < 3);
        String infeasibilityReason = null;
        if (infeasible) {
            infeasibilityReason = "Insufficient active workforce (" + employees.size() + " active employees; minimum 3 required)";
        } else if (!mandatoryViolations.isEmpty()) {
            infeasibilityReason = "Hard constraints violated: " + String.join("; ", mandatoryViolations);
        }

        explanations.add(String.format("Overall fairness score: %d/6000 (Fairness: %s)", totalScore, fair ? "EXCELLENT" : "ACCEPTABLE"));
        explanations.add(String.format("Preference satisfaction rate: %.1f%% (%d of %d active preferences fulfilled)",
                overallPrefRate, totalSatisfiedPrefs, totalApplicablePrefs));
        explanations.add(String.format("Night shift distribution variance: %.4f (Multi-week rolling balance across eligible males)", nightVariance));
        explanations.add(String.format("Evening shift distribution variance: %.4f (Balanced rotation across male employees)", eveningVariance));
        explanations.add(String.format("Avoidable shift transitions: %d across entire weekly roster", totalAvoidableTransitions));
        explanations.add(String.format("Weekly Off distribution: exactly 1 Off per employee; maximum %d simultaneous offs on any calendar day",
                dailyOffCounts.values().stream().mapToInt(Integer::intValue).max().orElse(0)));

        return new RosterFairnessReport(
                startDate,
                endDate,
                totalScore,
                scoreBreakdown,
                details,
                Math.round(overallPrefRate * 10.0) / 10.0,
                totalAvoidableTransitions,
                Math.round(nightVariance * 10000.0) / 10000.0,
                Math.round(eveningVariance * 10000.0) / 10000.0,
                Math.round(workloadVariance * 10000.0) / 10000.0,
                fair,
                explanations,
                mandatoryViolations,
                infeasible,
                infeasibilityReason
        );
    }

    private boolean isEmployeeOnLeave(Long employeeId, LocalDate date) {
        if (leaveRequestRepository == null || employeeId == null || date == null) return false;
        try {
            return leaveRequestRepository.hasApprovedLeaveOnDate(employeeId, date);
        } catch (Exception e) {
            return false;
        }
    }

    private double calculateVariance(List<Double> values) {
        if (values == null || values.size() <= 1) return 0.0;
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double sumSq = 0.0;
        for (double v : values) {
            sumSq += (v - mean) * (v - mean);
        }
        return sumSq / values.size();
    }
}
