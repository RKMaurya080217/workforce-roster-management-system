package com.weeklyroster.service;

import com.weeklyroster.dto.request.BulkDateShiftPreferenceRequest;
import com.weeklyroster.dto.request.DateShiftPreferenceRequest;
import com.weeklyroster.dto.response.DatePreferencePreviewResponse;
import com.weeklyroster.dto.response.DateShiftPreferenceResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.DateShiftPreferenceRepository;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.LeaveRequestRepository;
import com.weeklyroster.repository.RosterAssignmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Transactional
public class DateShiftPreferenceService {

    private static final Logger log = LoggerFactory.getLogger(DateShiftPreferenceService.class);

    private final DateShiftPreferenceRepository dateShiftPreferenceRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeActivityLogService activityLogService;
    private final AuditService auditService;
    private final LeaveRequestRepository leaveRequestRepository;
    private final RosterAssignmentRepository rosterAssignmentRepository;

    public DateShiftPreferenceService(DateShiftPreferenceRepository dateShiftPreferenceRepository,
                                      EmployeeRepository employeeRepository,
                                      EmployeeActivityLogService activityLogService,
                                      AuditService auditService,
                                      LeaveRequestRepository leaveRequestRepository,
                                      RosterAssignmentRepository rosterAssignmentRepository) {
        this.dateShiftPreferenceRepository = dateShiftPreferenceRepository;
        this.employeeRepository = employeeRepository;
        this.activityLogService = activityLogService;
        this.auditService = auditService;
        this.leaveRequestRepository = leaveRequestRepository;
        this.rosterAssignmentRepository = rosterAssignmentRepository;
    }

    public DateShiftPreferenceResponse createOrUpdate(DateShiftPreferenceRequest req, String adminUsername) {
        if (req == null) {
            throw new BusinessException("Request body cannot be null");
        }
        if (req.employeeId() == null) {
            throw new BusinessException("Employee ID is required");
        }
        if (req.preferenceDate() == null) {
            throw new BusinessException("Preference date is required");
        }
        if (req.shiftType() == null || req.shiftType() == ShiftType.OFF) {
            throw new BusinessException("Invalid shift type: 'OFF'. Valid shift preferences: MORNING, GENERAL, EVENING, NIGHT.");
        }

        Employee employee = employeeRepository.findById(req.employeeId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + req.employeeId()));

        Optional<DateShiftPreference> existingOpt = dateShiftPreferenceRepository
                .findByEmployeeIdAndPreferenceDate(req.employeeId(), req.preferenceDate());

        DateShiftPreference pref;
        String oldShift = null;
        if (existingOpt.isPresent()) {
            pref = existingOpt.get();
            oldShift = pref.getShiftType().name();
            pref.setShiftType(req.shiftType());
            pref.setReason(req.reason() != null ? req.reason().trim() : null);
            pref.setCreatedBy(adminUsername);
            pref.setUpdatedAt(LocalDateTime.now());
            log.info("[BATCH 80] Updated date-specific preference for employee {} on {}: {} -> {}",
                    employee.getEmployeeCode(), req.preferenceDate(), oldShift, req.shiftType());
        } else {
            pref = new DateShiftPreference(
                    employee,
                    req.preferenceDate(),
                    req.shiftType(),
                    req.reason() != null ? req.reason().trim() : null,
                    adminUsername
            );
            log.info("[BATCH 80] Created date-specific preference for employee {} on {}: {}",
                    employee.getEmployeeCode(), req.preferenceDate(), req.shiftType());
        }

        DateShiftPreference saved = dateShiftPreferenceRepository.save(pref);

        if (activityLogService != null) {
            activityLogService.logActivity(employee.getId(), adminUsername, ActivityCategory.PREFERENCE,
                    "DATE_PREFERENCE_CONFIGURED", ActivityStatus.SUCCESS,
                    "Admin configured " + req.shiftType() + " preference for date " + req.preferenceDate());
        }

        if (auditService != null) {
            auditService.log(AuditAction.PREFERENCE_APPROVED, "DATE_SHIFT_PREFERENCE", saved.getId(), null,
                    employee.getId(), employee.getFirstName() + " " + (employee.getLastName() != null ? employee.getLastName() : ""),
                    oldShift != null ? oldShift : "NONE", req.shiftType().name(),
                    "Admin configured date-specific shift preference for " + req.preferenceDate(), "MANUAL");
        }

        return toResponse(saved);
    }

    public List<DateShiftPreferenceResponse> createOrUpdateBulk(BulkDateShiftPreferenceRequest req, String adminUsername) {
        if (req == null) {
            throw new BusinessException("Request body cannot be null");
        }
        if (req.employeeIds() == null || req.employeeIds().isEmpty()) {
            throw new BusinessException("At least one employee must be selected");
        }
        if (req.preferenceDate() == null) {
            throw new BusinessException("Preference date is required");
        }
        if (req.shiftType() == null || req.shiftType() == ShiftType.OFF) {
            throw new BusinessException("Invalid shift type: 'OFF'. Valid shift preferences: MORNING, GENERAL, EVENING, NIGHT.");
        }

        List<DateShiftPreferenceResponse> responses = new ArrayList<>();
        for (Long empId : req.employeeIds()) {
            DateShiftPreferenceRequest singleReq = new DateShiftPreferenceRequest(
                    empId,
                    req.preferenceDate(),
                    req.shiftType(),
                    req.reason()
            );
            responses.add(createOrUpdate(singleReq, adminUsername));
        }
        return responses;
    }

    @Transactional(readOnly = true)
    public List<DateShiftPreferenceResponse> getAllDatePreferences() {
        return dateShiftPreferenceRepository.findAllByOrderByPreferenceDateDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DateShiftPreferenceResponse> getDatePreferencesByEmployee(Long employeeId) {
        return dateShiftPreferenceRepository.findByEmployeeIdOrderByPreferenceDateDesc(employeeId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DateShiftPreferenceResponse> getDatePreferencesForPeriod(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            return getAllDatePreferences();
        }
        return dateShiftPreferenceRepository.findByPreferenceDateBetween(startDate, endDate).stream()
                .map(this::toResponse)
                .toList();
    }

    public void deleteDatePreference(Long id, String adminUsername) {
        DateShiftPreference pref = dateShiftPreferenceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Date shift preference not found with id: " + id));

        Employee emp = pref.getEmployee();
        dateShiftPreferenceRepository.delete(pref);

        log.info("[BATCH 80] Deleted date-specific preference #{} for employee {} on {}",
                id, emp.getEmployeeCode(), pref.getPreferenceDate());

        if (activityLogService != null) {
            activityLogService.logActivity(emp.getId(), adminUsername, ActivityCategory.PREFERENCE,
                    "DATE_PREFERENCE_DELETED", ActivityStatus.SUCCESS,
                    "Admin deleted date preference for date " + pref.getPreferenceDate());
        }

        if (auditService != null) {
            auditService.log(AuditAction.PREFERENCE_APPROVED, "DATE_SHIFT_PREFERENCE", id, null,
                    emp.getId(), emp.getFirstName() + " " + (emp.getLastName() != null ? emp.getLastName() : ""),
                    pref.getShiftType().name(), "NONE",
                    "Admin deleted date-specific shift preference for " + pref.getPreferenceDate(), "MANUAL");
        }
    }

    public void deleteByEmployeeAndDate(Long employeeId, LocalDate date, String adminUsername) {
        Optional<DateShiftPreference> opt = dateShiftPreferenceRepository.findByEmployeeIdAndPreferenceDate(employeeId, date);
        opt.ifPresent(pref -> deleteDatePreference(pref.getId(), adminUsername));
    }

    @Transactional(readOnly = true)
    public Map<String, ShiftType> getDatePreferencesMap(LocalDate startDate, LocalDate endDate) {
        List<DateShiftPreference> list = (startDate != null && endDate != null)
                ? dateShiftPreferenceRepository.findByPreferenceDateBetween(startDate, endDate)
                : dateShiftPreferenceRepository.findAll();

        Map<String, ShiftType> map = new HashMap<>();
        for (DateShiftPreference p : list) {
            if (p.getEmployee() != null && p.getPreferenceDate() != null && p.getShiftType() != null) {
                map.put(p.getEmployee().getId() + "_" + p.getPreferenceDate().toString(), p.getShiftType());
            }
        }
        return map;
    }

    @Transactional(readOnly = true)
    public DatePreferencePreviewResponse previewPreference(Long employeeId, LocalDate date, ShiftType requestedShift) {
        if (employeeId == null || date == null) {
            throw new BusinessException("Employee ID and Date are required for preview");
        }
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + employeeId));

        String fullName = (employee.getFirstName() + " " + (employee.getLastName() != null ? employee.getLastName() : "")).trim();

        // 1. Current planned duty in active roster (if generated)
        List<RosterAssignment> currentAssignments = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(employeeId, date);
        String currentPlanned = "Not Yet Scheduled";
        if (!currentAssignments.isEmpty()) {
            RosterAssignment a = currentAssignments.get(0);
            if (a.isOnLeave()) {
                currentPlanned = "LEAVE";
            } else if (a.isWeeklyOff()) {
                currentPlanned = "OFF";
            } else if (a.getShift() != null) {
                currentPlanned = a.getShift().getShiftType().name();
            }
        }

        // 2. Check for approved leave conflict
        boolean hasLeave = leaveRequestRepository.existsByEmployeeIdAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                employeeId, LeaveStatus.APPROVED, date, date);
        if (hasLeave) {
            return new DatePreferencePreviewResponse(
                    employeeId,
                    employee.getEmployeeCode(),
                    fullName,
                    date,
                    requestedShift,
                    currentPlanned,
                    "LEAVE / OFF",
                    "CONFLICT",
                    "Existing approved leave prevents this preference from being safely applied. Final roster will preserve employee leave.",
                    false
            );
        }

        // 3. Check for female employee night shift safety restriction
        if (requestedShift == ShiftType.NIGHT && employee.getGender() == Gender.FEMALE) {
            return new DatePreferencePreviewResponse(
                    employeeId,
                    employee.getEmployeeCode(),
                    fullName,
                    date,
                    requestedShift,
                    currentPlanned,
                    currentPlanned.equals("Not Yet Scheduled") ? "GENERAL" : currentPlanned,
                    "CONFLICT",
                    "Female employees cannot be assigned to night shifts under workplace safety rules.",
                    false
            );
        }

        // 4. Check for rest interval conflict with previous day (< 12 hours rest)
        List<RosterAssignment> prevDayAssignments = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(employeeId, date.minusDays(1));
        if (!prevDayAssignments.isEmpty()) {
            RosterAssignment prev = prevDayAssignments.get(0);
            if (!prev.isWeeklyOff() && !prev.isOnLeave() && prev.getShift() != null && prev.getShift().getShiftType() == ShiftType.NIGHT) {
                if (requestedShift == ShiftType.MORNING) {
                    return new DatePreferencePreviewResponse(
                            employeeId,
                            employee.getEmployeeCode(),
                            fullName,
                            date,
                            requestedShift,
                            currentPlanned,
                            "GENERAL",
                            "CONFLICT",
                            "Insufficient rest interval (<12 hours) after previous night shift.",
                            false
                    );
                }
            }
        }

        // 5. Check for rest interval conflict with next day (< 12 hours rest)
        List<RosterAssignment> nextDayAssignments = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(employeeId, date.plusDays(1));
        if (!nextDayAssignments.isEmpty()) {
            RosterAssignment next = nextDayAssignments.get(0);
            if (!next.isWeeklyOff() && !next.isOnLeave() && next.getShift() != null && next.getShift().getShiftType() == ShiftType.MORNING) {
                if (requestedShift == ShiftType.NIGHT) {
                    return new DatePreferencePreviewResponse(
                            employeeId,
                            employee.getEmployeeCode(),
                            fullName,
                            date,
                            requestedShift,
                            currentPlanned,
                            "EVENING",
                            "CONFLICT",
                            "Insufficient rest interval (<12 hours) before next morning duty.",
                            false
                    );
                }
            }
        }

        // 6. Clean and valid preference
        return new DatePreferencePreviewResponse(
                employeeId,
                employee.getEmployeeCode(),
                fullName,
                date,
                requestedShift,
                currentPlanned,
                requestedShift.name(),
                "VALID",
                "Preference can be applied cleanly. Roster generator will automatically prioritize this shift.",
                true
        );
    }

    private DateShiftPreferenceResponse toResponse(DateShiftPreference p) {
        Employee e = p.getEmployee();
        String status = "VALID";
        String conflictReason = null;

        if (e != null && p.getPreferenceDate() != null) {
            boolean hasLeave = leaveRequestRepository.existsByEmployeeIdAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                    e.getId(), LeaveStatus.APPROVED, p.getPreferenceDate(), p.getPreferenceDate());
            if (hasLeave) {
                status = "CONFLICT";
                conflictReason = "Approved leave on this date";
            } else if (p.getShiftType() == ShiftType.NIGHT && e.getGender() == Gender.FEMALE) {
                status = "CONFLICT";
                conflictReason = "Female night shift restriction";
            } else {
                List<RosterAssignment> prev = rosterAssignmentRepository.findByEmployeeIdAndRosterDate(e.getId(), p.getPreferenceDate().minusDays(1));
                if (!prev.isEmpty() && !prev.get(0).isWeeklyOff() && !prev.get(0).isOnLeave() && prev.get(0).getShift() != null
                        && prev.get(0).getShift().getShiftType() == ShiftType.NIGHT && p.getShiftType() == ShiftType.MORNING) {
                    status = "CONFLICT";
                    conflictReason = "Insufficient rest interval (<12h) after night shift";
                }
            }
        }

        return new DateShiftPreferenceResponse(
                p.getId(),
                e != null ? e.getId() : null,
                e != null ? e.getEmployeeCode() : null,
                e != null ? (e.getFirstName() + " " + (e.getLastName() != null ? e.getLastName() : "")).trim() : null,
                p.getPreferenceDate(),
                p.getShiftType(),
                p.getReason(),
                p.getCreatedBy(),
                p.getCreatedAt(),
                p.getUpdatedAt(),
                status,
                conflictReason
        );
    }
}
