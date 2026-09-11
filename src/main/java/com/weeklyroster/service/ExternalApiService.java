package com.weeklyroster.service;

import com.weeklyroster.dto.external.v1.*;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class ExternalApiService {

    private final RosterCycleRepository cycleRepository;
    private final RosterAssignmentRepository assignmentRepository;
    private final EmployeeRepository employeeRepository;
    private final ShiftRepository shiftRepository;
    private final LeaveRequestRepository leaveRequestRepository;

    public ExternalApiService(RosterCycleRepository cycleRepository,
                              RosterAssignmentRepository assignmentRepository,
                              EmployeeRepository employeeRepository,
                              ShiftRepository shiftRepository,
                              LeaveRequestRepository leaveRequestRepository) {
        this.cycleRepository = cycleRepository;
        this.assignmentRepository = assignmentRepository;
        this.employeeRepository = employeeRepository;
        this.shiftRepository = shiftRepository;
        this.leaveRequestRepository = leaveRequestRepository;
    }

    public ExternalRosterCycleResponse getCurrentRosterCycle() {
        RosterCycle cycle = cycleRepository.findTopByOrderByStartDateDesc()
                .orElseThrow(() -> new ResourceNotFoundException("No published roster cycle found"));
        return mapToExternalRosterCycleResponse(cycle);
    }

    public ExternalRosterCycleResponse getRosterCycleByStartDate(LocalDate startDate) {
        RosterCycle cycle = cycleRepository.findByStartDate(startDate)
                .orElseThrow(() -> new ResourceNotFoundException("Roster cycle not found for start date: " + startDate));
        return mapToExternalRosterCycleResponse(cycle);
    }

    public List<ExternalRosterAssignmentResponse> getAssignmentsByDate(LocalDate date) {
        List<RosterAssignment> assignments = assignmentRepository.findByRosterDate(date);
        return assignments.stream()
                .map(this::mapToAssignmentResponse)
                .toList();
    }

    public List<ExternalRosterAssignmentResponse> getAssignmentsForEmployee(String employeeCode, Long cycleId) {
        Employee employee = employeeRepository.findByEmployeeCodeIgnoreCase(employeeCode)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with code: " + employeeCode));

        List<RosterAssignment> assignments;
        if (cycleId != null) {
            assignments = assignmentRepository.findByCycleIdOrderByRosterDateAsc(cycleId).stream()
                    .filter(a -> a.getEmployee().getId().equals(employee.getId()))
                    .toList();
        } else {
            assignments = assignmentRepository.findByEmployeeIdOrderByRosterDateAsc(employee.getId());
        }

        return assignments.stream()
                .map(this::mapToAssignmentResponse)
                .toList();
    }

    public List<ExternalEmployeeResponse> getEmployees(boolean activeOnly) {
        List<Employee> list = activeOnly
                ? employeeRepository.findByActiveTrueOrderByIdAsc()
                : employeeRepository.findAllByOrderByIdAsc();

        return list.stream()
                .map(this::mapToEmployeeResponse)
                .toList();
    }

    public ExternalEmployeeResponse getEmployeeByCode(String employeeCode) {
        Employee employee = employeeRepository.findByEmployeeCodeIgnoreCase(employeeCode)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with code: " + employeeCode));
        return mapToEmployeeResponse(employee);
    }

    public List<ExternalShiftResponse> getActiveShifts() {
        return shiftRepository.findByActiveTrueOrderByIdAsc().stream()
                .map(s -> new ExternalShiftResponse(
                        s.getId(),
                        s.getShiftType() != null ? s.getShiftType().name() : "UNKNOWN",
                        s.getCapacity(),
                        s.isActive()
                ))
                .toList();
    }

    public List<ExternalLeaveResponse> getApprovedLeaves(LocalDate startDate, LocalDate endDate, String employeeCode) {
        List<LeaveRequest> leaves = leaveRequestRepository.findByStatusOrderByRequestedAtAsc(LeaveStatus.APPROVED);

        return leaves.stream()
                .filter(l -> {
                    if (employeeCode != null && !employeeCode.isBlank()) {
                        if (!l.getEmployee().getEmployeeCode().equalsIgnoreCase(employeeCode.trim())) {
                            return false;
                        }
                    }
                    if (startDate != null && l.getEndDate().isBefore(startDate)) {
                        return false;
                    }
                    if (endDate != null && l.getStartDate().isAfter(endDate)) {
                        return false;
                    }
                    return true;
                })
                .map(l -> new ExternalLeaveResponse(
                        l.getId(),
                        l.getEmployee().getEmployeeCode(),
                        l.getEmployee().getFirstName() + " " + l.getEmployee().getLastName(),
                        l.getStartDate(),
                        l.getEndDate(),
                        l.getReason(),
                        l.getStatus() != null ? l.getStatus().name() : "UNKNOWN",
                        l.getReviewedAt()
                ))
                .toList();
    }

    private ExternalRosterCycleResponse mapToExternalRosterCycleResponse(RosterCycle cycle) {
        List<RosterAssignment> assignments = assignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.getId());
        List<ExternalRosterAssignmentResponse> assignmentResponses = assignments.stream()
                .map(this::mapToAssignmentResponse)
                .toList();

        return new ExternalRosterCycleResponse(
                cycle.getId(),
                cycle.getStartDate(),
                cycle.getEndDate(),
                cycle.getGeneratedAt(),
                assignmentResponses.size(),
                assignmentResponses
        );
    }

    private ExternalRosterAssignmentResponse mapToAssignmentResponse(RosterAssignment a) {
        return new ExternalRosterAssignmentResponse(
                a.getId(),
                a.getRosterDate(),
                a.getRosterDate() != null ? a.getRosterDate().getDayOfWeek().name() : null,
                a.getShift() != null && a.getShift().getShiftType() != null ? a.getShift().getShiftType().name() : "OFF",
                a.getEmployee() != null ? a.getEmployee().getEmployeeCode() : null,
                a.getEmployee() != null ? a.getEmployee().getFirstName() + " " + a.getEmployee().getLastName() : null,
                a.isWeeklyOff(),
                a.isOnLeave()
        );
    }

    private ExternalEmployeeResponse mapToEmployeeResponse(Employee e) {
        return new ExternalEmployeeResponse(
                e.getId(),
                e.getEmployeeCode(),
                e.getFirstName(),
                e.getLastName(),
                e.getEmail(),
                e.getGender() != null ? e.getGender().name() : null,
                e.isActive()
        );
    }
}
