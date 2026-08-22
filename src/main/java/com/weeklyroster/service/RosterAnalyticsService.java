package com.weeklyroster.service;

import com.weeklyroster.dto.response.*;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class RosterAnalyticsService {

    private final RosterCycleRepository cycleRepository;
    private final RosterAssignmentRepository assignmentRepository;
    private final EmployeeRepository employeeRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final ShiftHandoverRepository handoverRepository;
    private final EmployeePreferenceRepository preferenceRepository;
    private final HolidayRepository holidayRepository;
    private final WorkloadAnalyticsService workloadAnalyticsService;

    public RosterAnalyticsService(RosterCycleRepository cycleRepository,
                                  RosterAssignmentRepository assignmentRepository,
                                  EmployeeRepository employeeRepository,
                                  LeaveRequestRepository leaveRequestRepository,
                                  ShiftHandoverRepository handoverRepository,
                                  EmployeePreferenceRepository preferenceRepository,
                                  HolidayRepository holidayRepository,
                                  WorkloadAnalyticsService workloadAnalyticsService) {
        this.cycleRepository = cycleRepository;
        this.assignmentRepository = assignmentRepository;
        this.employeeRepository = employeeRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.handoverRepository = handoverRepository;
        this.preferenceRepository = preferenceRepository;
        this.holidayRepository = holidayRepository;
        this.workloadAnalyticsService = workloadAnalyticsService;
    }

    public RosterAnalyticsResponse getAnalytics(LocalDate startDate, LocalDate endDate, Long cycleId) {
        if (cycleId != null) {
            RosterCycle cycle = cycleRepository.findById(cycleId)
                    .orElseThrow(() -> new ResourceNotFoundException("Cycle not found with id: " + cycleId));
            startDate = cycle.getStartDate();
            endDate = cycle.getEndDate();
        } else {
            if (startDate == null) {
                startDate = LocalDate.now().minusWeeks(1);
            }
            if (endDate == null) {
                endDate = LocalDate.now().plusWeeks(1);
            }
        }

        List<Employee> allEmployees = employeeRepository.findAll();
        List<Employee> activeEmployees = employeeRepository.findByActiveTrueOrderByIdAsc();
        int totalEmpCount = allEmployees.size();
        int activeEmpCount = activeEmployees.size();

        LocalDate today = LocalDate.now();
        List<RosterAssignment> todayAssignments = assignmentRepository.findByRosterDate(today);

        int workingToday = 0;
        int onLeaveToday = 0;
        int offToday = 0;
        int morningToday = 0;
        int generalToday = 0;
        int eveningToday = 0;
        int nightToday = 0;

        for (RosterAssignment a : todayAssignments) {
            if (a.isOnLeave()) {
                onLeaveToday++;
            } else if (a.isWeeklyOff()) {
                offToday++;
            } else if (a.getShift() != null) {
                workingToday++;
                switch (a.getShift().getShiftType()) {
                    case MORNING -> morningToday++;
                    case GENERAL -> generalToday++;
                    case EVENING -> eveningToday++;
                    case NIGHT -> nightToday++;
                }
            }
        }

        List<RosterAssignment> periodAssignments = assignmentRepository.findByRosterDateBetweenOrderByRosterDateAsc(startDate, endDate);

        int totalSlots = 0;
        int filledSlots = 0;
        int shiftChangesCount = 0;
        Map<ShiftType, Integer> shiftCounts = new EnumMap<>(ShiftType.class);
        for (ShiftType st : ShiftType.values()) shiftCounts.put(st, 0);

        Map<LocalDate, List<RosterAssignment>> byDate = periodAssignments.stream()
                .collect(Collectors.groupingBy(RosterAssignment::getRosterDate));

        List<DayCoverageItem> dailyBreakdown = new ArrayList<>();

        for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            List<RosterAssignment> dayList = byDate.getOrDefault(d, Collections.emptyList());
            int m = 0, g = 0, e = 0, n = 0, off = 0, leave = 0;
            for (RosterAssignment a : dayList) {
                totalSlots++;
                if (a.isOnLeave()) leave++;
                else if (a.isWeeklyOff()) off++;
                else if (a.getShift() != null) {
                    filledSlots++;
                    ShiftType st = a.getShift().getShiftType();
                    shiftCounts.put(st, shiftCounts.get(st) + 1);
                    switch (st) {
                        case MORNING -> m++;
                        case GENERAL -> g++;
                        case EVENING -> e++;
                        case NIGHT -> n++;
                    }
                }
                if (a.isOverridden()) {
                    shiftChangesCount++;
                }
            }
            dailyBreakdown.add(new DayCoverageItem(
                    d,
                    d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                    m, g, e, n, off, leave, dayList.size()
            ));
        }

        double coveragePercentage = totalSlots == 0 ? 100.0 : Math.round(((double) filledSlots / totalSlots) * 1000.0) / 10.0;

        List<ShiftDistributionItem> shiftDistList = new ArrayList<>();
        int totalShiftsFilled = shiftCounts.values().stream().mapToInt(Integer::intValue).sum();
        for (Map.Entry<ShiftType, Integer> entry : shiftCounts.entrySet()) {
            double pct = totalShiftsFilled == 0 ? 0.0 : Math.round(((double) entry.getValue() / totalShiftsFilled) * 1000.0) / 10.0;
            shiftDistList.add(new ShiftDistributionItem(entry.getKey().name(), entry.getValue(), pct));
        }

        List<LeaveRequest> periodLeaves = leaveRequestRepository.findPendingRequests();
        int pendingLeavesCount = periodLeaves.size();
        int totalLeavesInPeriod = (int) periodAssignments.stream().filter(RosterAssignment::isOnLeave).count();

        List<ShiftHandover> periodHandovers = handoverRepository.findByHandoverDateBetweenOrderByHandoverDateDescCreatedAtDesc(startDate, endDate);
        int totalHandovers = periodHandovers.size();
        int pendingHandovers = (int) periodHandovers.stream().filter(h -> h.getStatus() == HandoverStatus.OPEN || h.getStatus() == HandoverStatus.IN_PROGRESS).count();

        List<EmployeePreference> pendingPrefs = preferenceRepository.findByStatusOrderByCreatedAtDesc(PreferenceStatus.PENDING);
        int pendingPreferencesCount = pendingPrefs.size();

        List<Holiday> activeHolidays = holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(startDate, endDate).stream()
                .filter(Holiday::isActive)
                .toList();

        RosterAnalyticsSummary summary = new RosterAnalyticsSummary(
                totalEmpCount,
                activeEmpCount,
                workingToday,
                onLeaveToday,
                offToday,
                morningToday,
                generalToday,
                eveningToday,
                nightToday,
                coveragePercentage,
                totalLeavesInPeriod,
                pendingLeavesCount,
                shiftChangesCount,
                totalHandovers,
                pendingHandovers,
                pendingPreferencesCount,
                activeHolidays.size()
        );

        WorkloadReportResponse workloadReport = workloadAnalyticsService.calculateWorkload(startDate, endDate, null);

        return new RosterAnalyticsResponse(
                startDate,
                endDate,
                cycleId,
                summary,
                shiftDistList,
                dailyBreakdown,
                workloadReport.employeeWorkloads(),
                LocalDateTime.now()
        );
    }
}
