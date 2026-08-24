package com.weeklyroster.service;

import com.weeklyroster.dto.response.DashboardDayViewResponse;
import com.weeklyroster.dto.response.DashboardDetailResponse;
import com.weeklyroster.dto.response.DashboardEmployeeViewResponse;
import com.weeklyroster.dto.response.DashboardResponse;
import com.weeklyroster.dto.response.EmployeeResponse;
import com.weeklyroster.dto.response.LeaveResponse;
import com.weeklyroster.dto.response.RosterAssignmentResponse;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.LeaveRequest;
import com.weeklyroster.entity.LeaveStatus;
import com.weeklyroster.entity.RosterAssignment;
import com.weeklyroster.entity.RosterCycle;
import com.weeklyroster.entity.Shift;
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.LeaveRequestRepository;
import com.weeklyroster.repository.RosterAssignmentRepository;
import com.weeklyroster.repository.RosterCycleRepository;
import com.weeklyroster.repository.ShiftRepository;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DashboardService {
    private final EmployeeRepository employeeRepository;
    private final RosterAssignmentRepository assignmentRepository;
    private final LeaveRequestRepository leaveRepository;
    private final RosterCycleRepository cycleRepository;
    private final ShiftRepository shiftRepository;

    public DashboardService(EmployeeRepository employeeRepository,
                            RosterAssignmentRepository assignmentRepository,
                            LeaveRequestRepository leaveRepository,
                            RosterCycleRepository cycleRepository,
                            ShiftRepository shiftRepository) {
        this.employeeRepository = employeeRepository;
        this.assignmentRepository = assignmentRepository;
        this.leaveRepository = leaveRepository;
        this.cycleRepository = cycleRepository;
        this.shiftRepository = shiftRepository;
    }

    public DashboardResponse dashboard() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        return new DashboardResponse(
                employeeRepository.count(),
                employeeRepository.countByActiveTrue(),
                employeeRepository.countByActiveFalse(),
                assignmentRepository.countByRosterDateAndShiftShiftTypeAndWeeklyOffFalseAndOnLeaveFalse(today, ShiftType.MORNING),
                assignmentRepository.countByRosterDateAndShiftShiftTypeAndWeeklyOffFalseAndOnLeaveFalse(today, ShiftType.GENERAL),
                assignmentRepository.countByRosterDateAndShiftShiftTypeAndWeeklyOffFalseAndOnLeaveFalse(today, ShiftType.EVENING),
                assignmentRepository.countByRosterDateAndShiftShiftTypeAndWeeklyOffFalseAndOnLeaveFalse(today, ShiftType.NIGHT),
                assignmentRepository.countByRosterDateAndWeeklyOffTrue(today),
                assignmentRepository.countByRosterDateAndOnLeaveTrue(today),
                leaveRepository.countByStatus(LeaveStatus.PENDING)
        );
    }

    public DashboardDetailResponse dashboardDetails() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        DashboardResponse summary = dashboard();

        List<RosterAssignmentResponse> todaysAssignments = assignmentRepository.findByRosterDate(today)
                .stream().map(this::toAssignmentResponse).toList();

        List<LeaveResponse> pendingLeaves = leaveRepository.findByStatusOrderByRequestedAtAsc(LeaveStatus.PENDING)
                .stream().map(this::toLeaveResponse).toList();

        List<Employee> allEmployees = employeeRepository.findAllByOrderByIdAsc();
        List<EmployeeResponse> activeEmployees = allEmployees.stream().filter(Employee::isActive).map(this::toEmployeeResponse).toList();
        List<EmployeeResponse> inactiveEmployees = allEmployees.stream().filter(e -> !e.isActive()).map(this::toEmployeeResponse).toList();

        RosterCycleResponse currentCycle = cycleRepository.findAllByOrderByStartDateDesc().stream().findFirst()
                .map(c -> toCycleResponse(c, assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(c)))
                .orElse(null);

        return new DashboardDetailResponse(
                summary,
                todaysAssignments,
                pendingLeaves,
                activeEmployees,
                inactiveEmployees,
                currentCycle
        );
    }

    public DashboardDayViewResponse dayView(Long cycleId) {
        RosterCycle cycle = findTargetCycle(cycleId);
        if (cycle == null) {
            return new DashboardDayViewResponse(null, null, null, Collections.emptyList());
        }

        List<RosterAssignment> assignments = assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(cycle);
        List<Shift> shifts = shiftRepository.findByActiveTrueOrderByIdAsc();
        Map<ShiftType, Shift> shiftMap = shifts.stream().collect(Collectors.toMap(Shift::getShiftType, s -> s, (a, b) -> a));

        Map<LocalDate, List<RosterAssignment>> assignmentsByDate = assignments.stream()
                .collect(Collectors.groupingBy(RosterAssignment::getRosterDate));

        List<DashboardDayViewResponse.DayScheduleDto> days = new ArrayList<>();
        LocalDate start = cycle.getStartDate();
        LocalDate end = cycle.getEndDate();

        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            List<RosterAssignment> dayAssignments = assignmentsByDate.getOrDefault(d, Collections.emptyList());
            String dayName = d.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);

            List<DashboardDayViewResponse.StaffItemDto> morningStaff = new ArrayList<>();
            List<DashboardDayViewResponse.StaffItemDto> generalStaff = new ArrayList<>();
            List<DashboardDayViewResponse.StaffItemDto> eveningStaff = new ArrayList<>();
            List<DashboardDayViewResponse.StaffItemDto> nightStaff = new ArrayList<>();
            List<DashboardDayViewResponse.StaffItemDto> offStaff = new ArrayList<>();
            List<DashboardDayViewResponse.StaffItemDto> leaveStaff = new ArrayList<>();

            for (RosterAssignment a : dayAssignments) {
                DashboardDayViewResponse.StaffItemDto item = toStaffItem(a);
                if (a.isOnLeave()) {
                    leaveStaff.add(item);
                } else if (a.isWeeklyOff()) {
                    offStaff.add(item);
                } else if (a.getShift() != null && a.getShift().getShiftType() != null) {
                    switch (a.getShift().getShiftType()) {
                        case MORNING -> morningStaff.add(item);
                        case GENERAL -> generalStaff.add(item);
                        case EVENING -> eveningStaff.add(item);
                        case NIGHT -> nightStaff.add(item);
                        default -> offStaff.add(item);
                    }
                } else {
                    offStaff.add(item);
                }
            }

            int workingCount = morningStaff.size() + generalStaff.size() + eveningStaff.size() + nightStaff.size();

            DashboardDayViewResponse.ShiftGroupDto morningGroup = createShiftGroup(ShiftType.MORNING, shiftMap, morningStaff);
            DashboardDayViewResponse.ShiftGroupDto generalGroup = createShiftGroup(ShiftType.GENERAL, shiftMap, generalStaff);
            DashboardDayViewResponse.ShiftGroupDto eveningGroup = createShiftGroup(ShiftType.EVENING, shiftMap, eveningStaff);
            DashboardDayViewResponse.ShiftGroupDto nightGroup = createShiftGroup(ShiftType.NIGHT, shiftMap, nightStaff);

            days.add(new DashboardDayViewResponse.DayScheduleDto(
                    d,
                    dayName,
                    workingCount,
                    offStaff.size(),
                    leaveStaff.size(),
                    morningGroup,
                    generalGroup,
                    eveningGroup,
                    nightGroup,
                    offStaff,
                    leaveStaff
            ));
        }

        return new DashboardDayViewResponse(cycle.getId(), start, end, days);
    }

    public DashboardEmployeeViewResponse employeeView(Long cycleId) {
        RosterCycle cycle = findTargetCycle(cycleId);
        if (cycle == null) {
            return new DashboardEmployeeViewResponse(null, null, null, Collections.emptyList());
        }

        List<RosterAssignment> assignments = assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(cycle);
        List<Employee> activeEmployees = employeeRepository.findByActiveTrueOrderByIdAsc();
        List<Shift> shifts = shiftRepository.findByActiveTrueOrderByIdAsc();
        Map<ShiftType, Shift> shiftMap = shifts.stream().collect(Collectors.toMap(Shift::getShiftType, s -> s, (a, b) -> a));

        Map<Long, Map<LocalDate, RosterAssignment>> empMap = new HashMap<>();
        for (RosterAssignment a : assignments) {
            empMap.computeIfAbsent(a.getEmployee().getId(), k -> new HashMap<>()).put(a.getRosterDate(), a);
        }

        List<DashboardEmployeeViewResponse.EmployeeScheduleDto> employeeSchedules = new ArrayList<>();
        LocalDate start = cycle.getStartDate();
        LocalDate end = cycle.getEndDate();

        for (Employee emp : activeEmployees) {
            Map<LocalDate, RosterAssignment> myDays = empMap.getOrDefault(emp.getId(), Collections.emptyMap());
            List<DashboardEmployeeViewResponse.EmployeeDaySlotDto> slots = new ArrayList<>();

            int workDays = 0;
            int offDays = 0;
            int leaveDays = 0;
            int nights = 0;

            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                String dayName = d.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
                RosterAssignment a = myDays.get(d);
                if (a == null) {
                    slots.add(new DashboardEmployeeViewResponse.EmployeeDaySlotDto(
                            d, dayName, "OFF", "No working hours", true, false, false, null
                    ));
                    offDays++;
                } else if (a.isOnLeave()) {
                    slots.add(new DashboardEmployeeViewResponse.EmployeeDaySlotDto(
                            d, dayName, "LEAVE", "Approved Leave", false, true, a.isOverridden(), a.getId()
                    ));
                    leaveDays++;
                } else if (a.isWeeklyOff()) {
                    slots.add(new DashboardEmployeeViewResponse.EmployeeDaySlotDto(
                            d, dayName, "OFF", "Weekly Off", true, false, a.isOverridden(), a.getId()
                    ));
                    offDays++;
                } else {
                    ShiftType st = a.getShift() != null ? a.getShift().getShiftType() : ShiftType.OFF;
                    String timing = a.getShift() != null ? a.getShift().getTimingDisplay() : getShiftTiming(shiftMap, st);
                    if (st == ShiftType.NIGHT) {
                        nights++;
                    }
                    workDays++;
                    slots.add(new DashboardEmployeeViewResponse.EmployeeDaySlotDto(
                            d, dayName, st.name(), timing, false, false, a.isOverridden(), a.getId()
                    ));
                }
            }

            String fullName = emp.getFirstName() + " " + (emp.getLastName() == null ? "" : emp.getLastName()).trim();
            employeeSchedules.add(new DashboardEmployeeViewResponse.EmployeeScheduleDto(
                    emp.getId(),
                    emp.getEmployeeCode(),
                    fullName,
                    emp.getGender() != null ? emp.getGender().name() : "MALE",
                    workDays,
                    offDays,
                    leaveDays,
                    nights,
                    slots
            ));
        }

        return new DashboardEmployeeViewResponse(cycle.getId(), start, end, employeeSchedules);
    }

    private RosterCycle findTargetCycle(Long cycleId) {
        if (cycleId != null) {
            return cycleRepository.findById(cycleId).orElse(null);
        }
        return cycleRepository.findAllByOrderByStartDateDesc().stream().findFirst().orElse(null);
    }

    private DashboardDayViewResponse.StaffItemDto toStaffItem(RosterAssignment a) {
        Employee e = a.getEmployee();
        String name = e.getFirstName() + " " + (e.getLastName() == null ? "" : e.getLastName()).trim();
        return new DashboardDayViewResponse.StaffItemDto(
                e.getId(),
                e.getEmployeeCode(),
                name,
                e.getGender() != null ? e.getGender().name() : "MALE",
                a.getId(),
                a.isOverridden()
        );
    }

    private DashboardDayViewResponse.ShiftGroupDto createShiftGroup(
            ShiftType type,
            Map<ShiftType, Shift> shiftMap,
            List<DashboardDayViewResponse.StaffItemDto> staffList) {
        Shift s = shiftMap.get(type);
        String timing = s != null ? s.getTimingDisplay() : getShiftTiming(shiftMap, type);
        int required = s != null ? s.getCapacity() : 1;
        return new DashboardDayViewResponse.ShiftGroupDto(
                type.name(),
                timing,
                required,
                staffList.size(),
                staffList
        );
    }

    private String getShiftTiming(Map<ShiftType, Shift> shiftMap, ShiftType type) {
        Shift s = shiftMap.get(type);
        if (s != null && s.getStartTime() != null && s.getEndTime() != null) {
            return s.getTimingDisplay();
        }
        return switch (type) {
            case MORNING -> "07:00 - 15:00";
            case GENERAL -> "09:30 - 18:00";
            case EVENING -> "14:00 - 22:00";
            case NIGHT -> "22:00 - 07:00 next day";
            default -> "No working hours";
        };
    }

    private RosterAssignmentResponse toAssignmentResponse(RosterAssignment assignment) {
        return new RosterAssignmentResponse(
                assignment.getId(),
                assignment.getCycle() != null ? assignment.getCycle().getId() : null,
                assignment.getRosterDate(),
                assignment.getEmployee().getId(),
                assignment.getEmployee().getEmployeeCode(),
                assignment.getEmployee().getFirstName() + " " + (assignment.getEmployee().getLastName() == null ? "" : assignment.getEmployee().getLastName()).trim(),
                assignment.getEmployee().getGender(),
                assignment.getShift().getShiftType(),
                assignment.isWeeklyOff(),
                assignment.isOnLeave(),
                assignment.isOverridden()
        );
    }

    private LeaveResponse toLeaveResponse(LeaveRequest leave) {
        return new LeaveResponse(
                leave.getId(),
                leave.getEmployee().getId(),
                leave.getEmployee().getEmployeeCode(),
                leave.getEmployee().getFirstName() + " " + (leave.getEmployee().getLastName() == null ? "" : leave.getEmployee().getLastName()).trim(),
                leave.getStartDate(),
                leave.getEndDate(),
                leave.getReason(),
                leave.getStatus(),
                leave.getAdminRemarks(),
                leave.getRequestedAt(),
                leave.getReviewedAt(),
                leave.getOriginalStartDate(),
                leave.getOriginalEndDate(),
                leave.getPendingStartDate(),
                leave.getPendingEndDate(),
                leave.getModificationReason(),
                leave.getCancellationReason(),
                leave.getModifiedAt()
        );
    }

    private EmployeeResponse toEmployeeResponse(Employee employee) {
        return new EmployeeResponse(
                employee.getId(),
                employee.getEmployeeCode(),
                employee.getFirstName(),
                employee.getLastName(),
                employee.getEmail(),
                employee.getGender(),
                employee.isActive(),
                employee.getUser() == null ? null : employee.getUser().getUsername()
        );
    }

    private RosterCycleResponse toCycleResponse(RosterCycle cycle, List<RosterAssignment> assignments) {
        return new RosterCycleResponse(
                cycle.getId(),
                cycle.getStartDate(),
                cycle.getEndDate(),
                cycle.getGeneratedAt(),
                cycle.getGenerationMode(),
                "SENT",
                assignments.stream().map(this::toAssignmentResponse).toList()
        );
    }
}
