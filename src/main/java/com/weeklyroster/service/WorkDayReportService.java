package com.weeklyroster.service;

import com.weeklyroster.dto.response.EmployeeDailyRecord;
import com.weeklyroster.dto.response.EmployeeWorkDaySummary;
import com.weeklyroster.dto.response.WeeklyEmployeeWorkSummary;
import com.weeklyroster.dto.response.WeeklyWorkSummaryResponse;
import com.weeklyroster.dto.response.WorkDayReportResponse;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.Holiday;
import com.weeklyroster.entity.LeaveRequest;
import com.weeklyroster.entity.LeaveStatus;
import com.weeklyroster.entity.RosterAssignment;
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.HolidayRepository;
import com.weeklyroster.repository.LeaveRequestRepository;
import com.weeklyroster.repository.RosterAssignmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class WorkDayReportService {

    private static final ZoneId KOLKATA_ZONE = ZoneId.of("Asia/Kolkata");

    private final EmployeeRepository employeeRepository;
    private final RosterAssignmentRepository assignmentRepository;
    private final LeaveRequestRepository leaveRepository;
    private final HolidayRepository holidayRepository;

    public WorkDayReportService(EmployeeRepository employeeRepository,
                                RosterAssignmentRepository assignmentRepository,
                                LeaveRequestRepository leaveRepository,
                                @org.springframework.beans.factory.annotation.Autowired(required = false) HolidayRepository holidayRepository) {
        this.employeeRepository = employeeRepository;
        this.assignmentRepository = assignmentRepository;
        this.leaveRepository = leaveRepository;
        this.holidayRepository = holidayRepository;
    }

    public WorkDayReportResponse generateReport(LocalDate startDate, LocalDate endDate, Long employeeId, ShiftType shiftFilter) {
        LocalDate todayInKolkata = LocalDate.now(KOLKATA_ZONE);

        final LocalDate effectiveStartDate = (startDate != null) ? startDate : todayInKolkata.with(TemporalAdjusters.firstDayOfMonth());
        final LocalDate effectiveEndDate = (endDate != null) ? endDate : todayInKolkata.with(TemporalAdjusters.lastDayOfMonth());

        if (effectiveStartDate.isAfter(effectiveEndDate)) {
            throw new BusinessException("Start date cannot be after end date (received: " + effectiveStartDate + " to " + effectiveEndDate + ")");
        }

        int totalPeriodDays = (int) (ChronoUnit.DAYS.between(effectiveStartDate, effectiveEndDate) + 1);

        // 1. Fetch all active employees (or filtered employee)
        List<Employee> employees;
        if (employeeId != null) {
            Employee emp = employeeRepository.findById(employeeId)
                    .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + employeeId));
            employees = List.of(emp);
        } else {
            employees = employeeRepository.findByActiveTrueOrderByIdAsc();
        }

        // 2. Fetch all active holidays in date range
        List<Holiday> holidays = holidayRepository != null
                ? holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(effectiveStartDate, effectiveEndDate)
                : Collections.emptyList();

        Map<LocalDate, Holiday> holidayMap = new HashMap<>();
        for (Holiday h : holidays) {
            if (h.isActive() && h.getHolidayDate() != null) {
                holidayMap.put(h.getHolidayDate(), h);
            }
        }

        // 3. Fetch all approved leaves for all employees in date range
        List<LeaveRequest> approvedLeaves = leaveRepository.findAll().stream()
                .filter(l -> l.getStatus() == LeaveStatus.APPROVED)
                .filter(l -> !l.getEndDate().isBefore(effectiveStartDate) && !l.getStartDate().isAfter(effectiveEndDate))
                .toList();

        Map<Long, List<LeaveRequest>> leavesByEmp = approvedLeaves.stream()
                .collect(Collectors.groupingBy(l -> l.getEmployee().getId()));

        // 4. Fetch all assignments in date range
        List<RosterAssignment> assignments = assignmentRepository.findByRosterDateBetweenOrderByRosterDateAsc(effectiveStartDate, effectiveEndDate);
        Map<Long, Map<LocalDate, RosterAssignment>> assignmentsByEmpAndDate = new HashMap<>();
        for (RosterAssignment a : assignments) {
            if (a.getEmployee() != null && a.getRosterDate() != null) {
                assignmentsByEmpAndDate
                        .computeIfAbsent(a.getEmployee().getId(), k -> new HashMap<>())
                        .put(a.getRosterDate(), a);
            }
        }

        List<EmployeeWorkDaySummary> summaries = new ArrayList<>();
        int totalWorked = 0;
        int totalHolidays = 0;
        int totalLeaves = 0;
        int totalWeeklyOffs = 0;

        for (Employee emp : employees) {
            List<LeaveRequest> empLeaves = leavesByEmp.getOrDefault(emp.getId(), Collections.emptyList());
            Map<LocalDate, RosterAssignment> empDateMap = assignmentsByEmpAndDate.getOrDefault(emp.getId(), Collections.emptyMap());

            int empWorked = 0;
            int empHolidays = 0;
            int empLeavesCount = 0;
            int empWeeklyOffs = 0;

            List<EmployeeDailyRecord> dailyRecords = new ArrayList<>();

            for (LocalDate d = effectiveStartDate; !d.isAfter(effectiveEndDate); d = d.plusDays(1)) {
                LocalDate currentDate = d;
                String dow = currentDate.getDayOfWeek().name();

                // Categorize day with strict precedence:
                // Rule 1: Official Holiday recognized in WRMS
                if (holidayMap.containsKey(currentDate)) {
                    Holiday h = holidayMap.get(currentDate);
                    empHolidays++;
                    dailyRecords.add(new EmployeeDailyRecord(
                            currentDate, dow, "HOLIDAY", "Official Holiday", null, h.getName()
                    ));
                    continue;
                }

                // Rule 2: Approved Leave
                boolean onLeave = empLeaves.stream().anyMatch(l -> !currentDate.isBefore(l.getStartDate()) && !currentDate.isAfter(l.getEndDate()));
                if (onLeave) {
                    empLeavesCount++;
                    LeaveRequest matchedLeave = empLeaves.stream()
                            .filter(l -> !currentDate.isBefore(l.getStartDate()) && !currentDate.isAfter(l.getEndDate()))
                            .findFirst().orElse(null);
                    dailyRecords.add(new EmployeeDailyRecord(
                            currentDate, dow, "LEAVE", "Approved Leave", null, matchedLeave != null ? matchedLeave.getReason() : "Approved Absence"
                    ));
                    continue;
                }

                // Rule 3: Roster Assignment check
                RosterAssignment assignment = empDateMap.get(currentDate);
                if (assignment != null) {
                    if (assignment.isWeeklyOff()) {
                        empWeeklyOffs++;
                        dailyRecords.add(new EmployeeDailyRecord(
                                currentDate, dow, "WEEKLY_OFF", "Weekly Off", null, "Scheduled Rest Day"
                        ));
                    } else if (assignment.isOnLeave()) {
                        empLeavesCount++;
                        dailyRecords.add(new EmployeeDailyRecord(
                                currentDate, dow, "LEAVE", "On Leave", null, "Roster Leave Record"
                        ));
                    } else if (assignment.getShift() != null && assignment.getShift().getShiftType() != ShiftType.OFF) {
                        ShiftType st = assignment.getShift().getShiftType();
                        if (shiftFilter == null || shiftFilter == st) {
                            empWorked++;
                            dailyRecords.add(new EmployeeDailyRecord(
                                    currentDate, dow, "WORKED", st.name(), st, "Assigned Duty"
                            ));
                        } else {
                            dailyRecords.add(new EmployeeDailyRecord(
                                    currentDate, dow, "FILTERED_OUT", st.name(), st, "Excluded by shift filter"
                            ));
                        }
                    } else {
                        dailyRecords.add(new EmployeeDailyRecord(
                                currentDate, dow, "OFF", "Duty Not Scheduled", ShiftType.OFF, "Roster Off"
                        ));
                    }
                } else {
                    dailyRecords.add(new EmployeeDailyRecord(
                            currentDate, dow, "UNASSIGNED", "Standby / Unassigned", null, "No Roster Entry"
                    ));
                }
            }

            summaries.add(new EmployeeWorkDaySummary(
                    emp.getId(),
                    emp.getEmployeeCode(),
                    emp.getFirstName() + " " + (emp.getLastName() == null ? "" : emp.getLastName()).trim(),
                    emp.getGender(),
                    empWorked,
                    empHolidays,
                    empLeavesCount,
                    empWeeklyOffs,
                    totalPeriodDays,
                    dailyRecords
            ));

            totalWorked += empWorked;
            totalHolidays += empHolidays;
            totalLeaves += empLeavesCount;
            totalWeeklyOffs += empWeeklyOffs;
        }

        return new WorkDayReportResponse(
                effectiveStartDate,
                effectiveEndDate,
                totalPeriodDays,
                summaries.size(),
                totalWorked,
                totalHolidays,
                totalLeaves,
                totalWeeklyOffs,
                summaries,
                LocalDateTime.now(KOLKATA_ZONE)
        );
    }

    public WeeklyWorkSummaryResponse generateWeeklyWorkSummary(LocalDate weekStart, Long employeeId) {
        LocalDate todayInKolkata = LocalDate.now(KOLKATA_ZONE);
        final LocalDate monday;
        if (weekStart != null) {
            monday = weekStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        } else {
            monday = todayInKolkata.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        }
        final LocalDate sunday = monday.plusDays(6);
        final int totalPeriodDays = 7;

        // 1. Fetch active employees (or specific filtered employee)
        List<Employee> employees;
        if (employeeId != null) {
            Employee emp = employeeRepository.findById(employeeId)
                    .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + employeeId));
            employees = List.of(emp);
        } else {
            employees = employeeRepository.findByActiveTrueOrderByIdAsc();
        }

        // 2. Fetch all active holidays in week [monday, sunday]
        List<Holiday> holidays = holidayRepository != null
                ? holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(monday, sunday)
                : Collections.emptyList();

        Map<LocalDate, Holiday> holidayMap = new HashMap<>();
        for (Holiday h : holidays) {
            if (h.isActive() && h.getHolidayDate() != null) {
                holidayMap.put(h.getHolidayDate(), h);
            }
        }

        // 3. Fetch approved leaves overlapping [monday, sunday]
        List<LeaveRequest> approvedLeaves = leaveRepository.findAll().stream()
                .filter(l -> l.getStatus() == LeaveStatus.APPROVED)
                .filter(l -> !l.getEndDate().isBefore(monday) && !l.getStartDate().isAfter(sunday))
                .toList();

        Map<Long, List<LeaveRequest>> leavesByEmp = approvedLeaves.stream()
                .collect(Collectors.groupingBy(l -> l.getEmployee().getId()));

        // 4. Fetch assignments in week with duplicate protection
        List<RosterAssignment> assignments = assignmentRepository.findByRosterDateBetweenOrderByRosterDateAsc(monday, sunday);
        Map<Long, Map<LocalDate, RosterAssignment>> assignmentsByEmpAndDate = new HashMap<>();
        for (RosterAssignment a : assignments) {
            if (a.getEmployee() != null && a.getRosterDate() != null) {
                Map<LocalDate, RosterAssignment> empMap = assignmentsByEmpAndDate.computeIfAbsent(a.getEmployee().getId(), k -> new HashMap<>());
                RosterAssignment current = empMap.get(a.getRosterDate());
                // Select authoritative assignment if duplicates exist
                if (current == null || a.isOverridden() || (a.getCycle() != null && a.getCycle().getStatus() == com.weeklyroster.entity.RosterStatus.PUBLISHED)) {
                    empMap.put(a.getRosterDate(), a);
                }
            }
        }

        List<WeeklyEmployeeWorkSummary> summaries = new ArrayList<>();
        int totalWorked = 0;
        int totalLeaves = 0;
        int totalHolidays = 0;
        int totalWeeklyOffs = 0;
        int totalAbsent = 0;

        for (Employee emp : employees) {
            List<LeaveRequest> empLeaves = leavesByEmp.getOrDefault(emp.getId(), Collections.emptyList());
            Map<LocalDate, RosterAssignment> empDateMap = assignmentsByEmpAndDate.getOrDefault(emp.getId(), Collections.emptyMap());

            int empWorked = 0;
            int empLeavesCount = 0;
            int empHolidays = 0;
            int empWeeklyOffs = 0;
            int empAbsent = 0;

            List<EmployeeDailyRecord> dailyRecords = new ArrayList<>();

            for (LocalDate d = monday; !d.isAfter(sunday); d = d.plusDays(1)) {
                LocalDate currentDate = d;
                String dow = currentDate.getDayOfWeek().name();

                // Priority 1: Holiday
                if (holidayMap.containsKey(currentDate)) {
                    Holiday h = holidayMap.get(currentDate);
                    empHolidays++;
                    dailyRecords.add(new EmployeeDailyRecord(
                            currentDate, dow, "HOLIDAY", "Official Holiday", null, h.getName()
                    ));
                    continue;
                }

                // Priority 2: Approved Leave
                boolean onLeave = empLeaves.stream().anyMatch(l -> !currentDate.isBefore(l.getStartDate()) && !currentDate.isAfter(l.getEndDate()));
                if (onLeave) {
                    empLeavesCount++;
                    LeaveRequest matchedLeave = empLeaves.stream()
                            .filter(l -> !currentDate.isBefore(l.getStartDate()) && !currentDate.isAfter(l.getEndDate()))
                            .findFirst().orElse(null);
                    dailyRecords.add(new EmployeeDailyRecord(
                            currentDate, dow, "LEAVE", "Approved Leave", null, matchedLeave != null ? matchedLeave.getReason() : "Approved Absence"
                    ));
                    continue;
                }

                // Priority 3 & 4: Roster Assignment (Weekly Off or Worked Shift)
                RosterAssignment assignment = empDateMap.get(currentDate);
                if (assignment != null) {
                    if (assignment.isWeeklyOff() || (assignment.getShift() != null && assignment.getShift().getShiftType() == ShiftType.OFF)) {
                        empWeeklyOffs++;
                        dailyRecords.add(new EmployeeDailyRecord(
                                currentDate, dow, "WEEKLY_OFF", "Weekly Off", ShiftType.OFF, "Scheduled Rest Day"
                        ));
                    } else if (assignment.isOnLeave()) {
                        empLeavesCount++;
                        dailyRecords.add(new EmployeeDailyRecord(
                                currentDate, dow, "LEAVE", "On Leave", null, "Roster Leave Record"
                        ));
                    } else if (assignment.getShift() != null && assignment.getShift().getShiftType() != ShiftType.OFF) {
                        ShiftType st = assignment.getShift().getShiftType();
                        empWorked++;
                        dailyRecords.add(new EmployeeDailyRecord(
                                currentDate, dow, "WORKED", st.name(), st, assignment.getAssignmentReason() != null ? assignment.getAssignmentReason() : "Assigned Working Shift"
                        ));
                    } else {
                        empWeeklyOffs++;
                        dailyRecords.add(new EmployeeDailyRecord(
                                currentDate, dow, "WEEKLY_OFF", "Weekly Off", ShiftType.OFF, "Scheduled Rest Day"
                        ));
                    }
                } else {
                    // Priority 5: Absent / Unassigned
                    empAbsent++;
                    dailyRecords.add(new EmployeeDailyRecord(
                            currentDate, dow, "ABSENT", "Unassigned", null, "No Roster Assignment"
                    ));
                }
            }

            int empTotal = empWorked + empLeavesCount + empHolidays + empWeeklyOffs + empAbsent;

            summaries.add(new WeeklyEmployeeWorkSummary(
                    emp.getId(),
                    emp.getEmployeeCode(),
                    emp.getFirstName() + " " + (emp.getLastName() == null ? "" : emp.getLastName()).trim(),
                    emp.getGender(),
                    empWorked,
                    empLeavesCount,
                    empHolidays,
                    empWeeklyOffs,
                    empAbsent,
                    empTotal,
                    dailyRecords
            ));

            totalWorked += empWorked;
            totalLeaves += empLeavesCount;
            totalHolidays += empHolidays;
            totalWeeklyOffs += empWeeklyOffs;
            totalAbsent += empAbsent;
        }

        int totalDaysAccounted = totalWorked + totalLeaves + totalHolidays + totalWeeklyOffs + totalAbsent;

        return new WeeklyWorkSummaryResponse(
                monday,
                sunday,
                totalPeriodDays,
                summaries.size(),
                totalWorked,
                totalLeaves,
                totalHolidays,
                totalWeeklyOffs,
                totalAbsent,
                totalDaysAccounted,
                summaries,
                LocalDateTime.now(KOLKATA_ZONE)
        );
    }
}
