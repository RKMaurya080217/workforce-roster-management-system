package com.weeklyroster.service;

import com.weeklyroster.dto.response.EmployeeWorkloadMetric;
import com.weeklyroster.dto.response.WorkloadReportResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.HolidayRepository;
import com.weeklyroster.repository.RosterAssignmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class WorkloadAnalyticsService {

    private final RosterAssignmentRepository assignmentRepository;
    private final EmployeeRepository employeeRepository;
    private final HolidayRepository holidayRepository;

    public WorkloadAnalyticsService(RosterAssignmentRepository assignmentRepository,
                                  EmployeeRepository employeeRepository,
                                  HolidayRepository holidayRepository) {
        this.assignmentRepository = assignmentRepository;
        this.employeeRepository = employeeRepository;
        this.holidayRepository = holidayRepository;
    }

    public WorkloadReportResponse calculateWorkload(LocalDate startDate, LocalDate endDate, Long employeeIdFilter) {
        if (startDate == null) {
            startDate = LocalDate.now().minusWeeks(2);
        }
        if (endDate == null) {
            endDate = LocalDate.now().plusWeeks(2);
        }

        List<Employee> employees;
        if (employeeIdFilter != null) {
            employees = employeeRepository.findById(employeeIdFilter)
                    .map(List::of)
                    .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + employeeIdFilter));
        } else {
            employees = employeeRepository.findByActiveTrueOrderByIdAsc();
        }

        List<RosterAssignment> assignments = assignmentRepository.findByRosterDateBetweenOrderByRosterDateAsc(startDate, endDate);
        Map<Long, List<RosterAssignment>> byEmp = assignments.stream()
                .collect(Collectors.groupingBy(a -> a.getEmployee().getId()));

        Set<LocalDate> holidayDates = holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(startDate, endDate).stream()
                .filter(Holiday::isActive)
                .map(Holiday::getHolidayDate)
                .collect(Collectors.toSet());

        List<EmployeeWorkloadMetric> metrics = new ArrayList<>();
        double totalScoreSum = 0;

        for (Employee emp : employees) {
            List<RosterAssignment> empAssignments = byEmp.getOrDefault(emp.getId(), Collections.emptyList()).stream()
                    .sorted(Comparator.comparing(RosterAssignment::getRosterDate))
                    .toList();

            int total = empAssignments.size();
            int workingDays = 0;
            int offDays = 0;
            int morning = 0;
            int general = 0;
            int evening = 0;
            int night = 0;
            int weekendDuties = 0;
            int holidayDuties = 0;
            int shiftChanges = 0;

            int maxConsecutiveWork = 0;
            int currentConsecutiveWork = 0;
            int maxConsecutiveNight = 0;
            int currentConsecutiveNight = 0;

            ShiftType prevShift = null;

            for (RosterAssignment a : empAssignments) {
                if (a.isWeeklyOff() || a.isOnLeave()) {
                    offDays++;
                    currentConsecutiveWork = 0;
                    currentConsecutiveNight = 0;
                    prevShift = null;
                } else if (a.getShift() != null) {
                    workingDays++;
                    currentConsecutiveWork++;
                    maxConsecutiveWork = Math.max(maxConsecutiveWork, currentConsecutiveWork);

                    ShiftType st = a.getShift().getShiftType();
                    switch (st) {
                        case MORNING -> morning++;
                        case GENERAL -> general++;
                        case EVENING -> evening++;
                        case NIGHT -> {
                            night++;
                            currentConsecutiveNight++;
                            maxConsecutiveNight = Math.max(maxConsecutiveNight, currentConsecutiveNight);
                        }
                    }

                    if (st != ShiftType.NIGHT) {
                        currentConsecutiveNight = 0;
                    }

                    DayOfWeek dow = a.getRosterDate().getDayOfWeek();
                    if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
                        weekendDuties++;
                    }

                    if (holidayDates.contains(a.getRosterDate())) {
                        holidayDuties++;
                    }

                    if (prevShift != null && prevShift != st) {
                        shiftChanges++;
                    }
                    prevShift = st;
                }
            }

            int consecutiveOver5 = Math.max(0, maxConsecutiveWork - 5);
            double score = (workingDays * 1.0)
                    + (night * 2.5)
                    + (evening * 1.5)
                    + (weekendDuties * 1.8)
                    + (holidayDuties * 2.0)
                    + (consecutiveOver5 * 1.5)
                    + (shiftChanges * 0.5);

            score = Math.round(score * 10.0) / 10.0;
            totalScoreSum += score;

            String rating;
            if (score > 35) rating = "OVERLOADED";
            else if (score > 24) rating = "HIGH";
            else if (score >= 12) rating = "OPTIMAL";
            else if (score > 0) rating = "LIGHT";
            else rating = "INACTIVE";

            metrics.add(new EmployeeWorkloadMetric(
                    emp.getId(),
                    emp.getEmployeeCode(),
                    (emp.getFirstName() + " " + (emp.getLastName() != null ? emp.getLastName() : "")).trim(),
                    emp.getGender() != null ? emp.getGender().name() : "MALE",
                    total,
                    workingDays,
                    offDays,
                    morning,
                    general,
                    evening,
                    night,
                    maxConsecutiveWork,
                    maxConsecutiveNight,
                    weekendDuties,
                    holidayDuties,
                    shiftChanges,
                    score,
                    rating
            ));
        }

        double avgScore = employees.isEmpty() ? 0 : Math.round((totalScoreSum / employees.size()) * 10.0) / 10.0;

        metrics.sort(Comparator.comparingDouble(EmployeeWorkloadMetric::workloadScore).reversed());

        return new WorkloadReportResponse(
                startDate,
                endDate,
                employees.size(),
                avgScore,
                metrics,
                LocalDateTime.now()
        );
    }
}
