package com.weeklyroster.service;

import com.weeklyroster.dto.request.RecurringShiftPreferenceRequest;
import com.weeklyroster.dto.response.RecurringShiftPreferenceResponse;
import com.weeklyroster.entity.AuditAction;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.RecurringPreferenceType;
import com.weeklyroster.entity.RecurringShiftPreference;
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.RecurringShiftPreferenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@Transactional
public class RecurringShiftPreferenceService {

    private static final Logger log = LoggerFactory.getLogger(RecurringShiftPreferenceService.class);

    private final RecurringShiftPreferenceRepository recurringRepository;
    private final EmployeeRepository employeeRepository;
    private final AuditService auditService;

    public RecurringShiftPreferenceService(RecurringShiftPreferenceRepository recurringRepository,
                                           EmployeeRepository employeeRepository,
                                           AuditService auditService) {
        this.recurringRepository = recurringRepository;
        this.employeeRepository = employeeRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<RecurringShiftPreferenceResponse> getByEmployee(Long employeeId) {
        if (employeeId == null) return getAllActive();
        return recurringRepository.findByEmployeeId(employeeId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RecurringShiftPreferenceResponse> getAllActive() {
        return recurringRepository.findByActiveTrue().stream()
                .map(this::toResponse)
                .toList();
    }

    public RecurringShiftPreferenceResponse create(RecurringShiftPreferenceRequest req, String adminUsername) {
        return createOrUpdate(req, adminUsername);
    }

    public RecurringShiftPreferenceResponse createOrUpdate(RecurringShiftPreferenceRequest req, String adminUsername) {
        if (req == null) {
            throw new BusinessException("Request body cannot be null");
        }
        if (req.employeeId() == null) {
            throw new BusinessException("Employee ID is required");
        }
        if (req.dayOfWeek() == null) {
            throw new BusinessException("Day of week is required");
        }
        if (req.shiftType() == null) {
            throw new BusinessException("Shift type is required");
        }
        LocalDate effectiveStart = (req.startDate() != null) ? req.startDate() : LocalDate.now();
        if (req.endDate() != null && req.endDate().isBefore(effectiveStart)) {
            throw new BusinessException("End date cannot be before start date");
        }

        Employee employee = employeeRepository.findById(req.employeeId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee with ID " + req.employeeId() + " not found"));

        boolean isActive = req.active() == null || req.active();
        RecurringPreferenceType prefType = req.preferenceType() != null ? req.preferenceType() : RecurringPreferenceType.PREFERRED;

        // BATCH 124 Conflict Detection: Prevent logically contradictory rules
        // e.g. Saturday -> Prefer Morning and Saturday -> Do Not Assign Morning
        List<RecurringShiftPreference> existingList = recurringRepository.findByEmployeeIdAndDayOfWeek(employee.getId(), req.dayOfWeek());
        if (isActive) {
            checkConflict(null, employee, req.dayOfWeek(), req.shiftType(), prefType, effectiveStart, req.endDate());
        }

        // Look for matching rule to update (same employee, day, shift, and preference type, or same preference type if PREFERRED)
        Optional<RecurringShiftPreference> matchOpt = existingList.stream()
                .filter(r -> r.getPreferenceType() == prefType && (prefType == RecurringPreferenceType.PREFERRED || r.getShiftType() == req.shiftType()))
                .findFirst();

        RecurringShiftPreference pref;
        String oldShift = null;
        if (matchOpt.isPresent()) {
            pref = matchOpt.get();
            oldShift = pref.getShiftType() != null ? pref.getShiftType().name() : "NONE";
            pref.setShiftType(req.shiftType());
            pref.setPreferenceType(prefType);
            pref.setStartDate(effectiveStart);
            pref.setEndDate(req.endDate());
            pref.setActive(isActive);
            if (req.reason() != null) {
                pref.setReason(req.reason().trim());
            }
            pref.setUpdatedAt(LocalDateTime.now());
            log.info("[BATCH 124] Updated existing recurring rule for employee {} on Every {}: {} ({})",
                    employee.getEmployeeCode(), req.dayOfWeek(), req.shiftType(), prefType);
        } else {
            String creator = (adminUsername != null && !adminUsername.isBlank()) ? adminUsername : "Admin";
            pref = new RecurringShiftPreference(
                    employee,
                    req.dayOfWeek(),
                    req.shiftType(),
                    prefType,
                    effectiveStart,
                    req.endDate(),
                    isActive,
                    req.reason() != null ? req.reason().trim() : null,
                    creator
            );
            log.info("[BATCH 124] Created recurring rule for employee {} on Every {}: {} ({})",
                    employee.getEmployeeCode(), req.dayOfWeek(), req.shiftType(), prefType);
        }

        RecurringShiftPreference saved = recurringRepository.save(pref);

        if (auditService != null) {
            auditService.log(
                    AuditAction.PREFERENCE_APPROVED,
                    "RECURRING_SHIFT_PREFERENCE",
                    saved.getId(),
                    null,
                    employee.getId(),
                    employee.getFirstName() + " " + (employee.getLastName() != null ? employee.getLastName() : ""),
                    oldShift != null ? oldShift : "NONE",
                    req.shiftType().name(),
                    "Admin configured recurring weekly " + (prefType == RecurringPreferenceType.DO_NOT_ASSIGN ? "restriction" : "preference")
                            + " for Every " + req.dayOfWeek() + " -> " + req.shiftType(),
                    "MANUAL"
            );
        }

        return toResponse(saved);
    }

    public RecurringShiftPreferenceResponse update(Long id, RecurringShiftPreferenceRequest req, String adminUsername) {
        RecurringShiftPreference existing = recurringRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Recurring preference #" + id + " not found"));

        if (req.dayOfWeek() != null) existing.setDayOfWeek(req.dayOfWeek());
        if (req.shiftType() != null) existing.setShiftType(req.shiftType());
        if (req.preferenceType() != null) existing.setPreferenceType(req.preferenceType());
        if (req.startDate() != null) existing.setStartDate(req.startDate());
        if (req.endDate() != null) {
            if (req.endDate().isBefore(existing.getStartDate())) {
                throw new BusinessException("End date cannot be before start date");
            }
            existing.setEndDate(req.endDate());
        } else {
            existing.setEndDate(null);
        }
        if (req.active() != null) existing.setActive(req.active());
        if (req.reason() != null) existing.setReason(req.reason());
        existing.setUpdatedAt(LocalDateTime.now());

        if (existing.isActive()) {
            checkConflict(existing.getId(), existing.getEmployee(), existing.getDayOfWeek(), existing.getShiftType(),
                    existing.getPreferenceType(), existing.getStartDate(), existing.getEndDate());
        }

        RecurringShiftPreference saved = recurringRepository.save(existing);
        return toResponse(saved);
    }

    public void delete(Long id, String adminUsername) {
        RecurringShiftPreference pref = recurringRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Recurring preference #" + id + " not found"));

        recurringRepository.delete(pref);

        if (auditService != null) {
            auditService.log(
                    AuditAction.PREFERENCE_REJECTED,
                    "RECURRING_SHIFT_PREFERENCE",
                    id,
                    null,
                    pref.getEmployee().getId(),
                    pref.getEmployee().getFirstName(),
                    pref.getShiftType().name(),
                    null,
                    "Admin deleted recurring weekly preference for Every " + pref.getDayOfWeek(),
                    "MANUAL"
            );
        }
    }

    public RecurringShiftPreferenceResponse toggleActive(Long id, String adminUsername) {
        RecurringShiftPreference pref = recurringRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Recurring preference #" + id + " not found"));

        boolean newActive = !pref.isActive();
        if (newActive) {
            checkConflict(pref.getId(), pref.getEmployee(), pref.getDayOfWeek(), pref.getShiftType(),
                    pref.getPreferenceType(), pref.getStartDate(), pref.getEndDate());
        }
        pref.setActive(newActive);
        pref.setUpdatedAt(LocalDateTime.now());

        RecurringShiftPreference saved = recurringRepository.save(pref);
        return toResponse(saved);
    }

    private void checkConflict(Long excludeId, Employee employee, DayOfWeek dayOfWeek, ShiftType shiftType,
                               RecurringPreferenceType preferenceType, LocalDate startDate, LocalDate endDate) {
        List<RecurringShiftPreference> existingList = recurringRepository.findByEmployeeIdAndDayOfWeek(employee.getId(), dayOfWeek);
        for (RecurringShiftPreference ex : existingList) {
            if (excludeId != null && ex.getId().equals(excludeId)) continue;
            if (!ex.isActive()) continue;

            // Date range overlap check: [startDate, endDate] vs [ex.startDate, ex.endDate]
            boolean overlaps = (ex.getEndDate() == null || !startDate.isAfter(ex.getEndDate())) &&
                               (endDate == null || !endDate.isBefore(ex.getStartDate()));

            if (overlaps && ex.getShiftType() == shiftType && ex.getPreferenceType() != preferenceType) {
                String dayStr = dayOfWeek.name().charAt(0) + dayOfWeek.name().substring(1).toLowerCase();
                String shiftStr = shiftType.name().charAt(0) + shiftType.name().substring(1).toLowerCase();
                throw new BusinessException("Conflicting weekly preferences: "
                        + dayStr + " is configured as both Preferred " + shiftStr
                        + " and Do Not Assign " + shiftStr + ".");
            }
        }
    }

    @Transactional(readOnly = true)
    public Map<Long, Map<DayOfWeek, ShiftType>> getActiveRecurringMap(List<Employee> employees, LocalDate startDate, LocalDate endDate) {
        Map<Long, Map<DayOfWeek, ShiftType>> result = new HashMap<>();
        List<RecurringShiftPreference> allActive = recurringRepository.findByActiveTrue();

        for (RecurringShiftPreference rsp : allActive) {
            if (rsp.getEmployee() == null) continue;
            if (rsp.getPreferenceType() == RecurringPreferenceType.DO_NOT_ASSIGN) continue; // Positive preferences only
            Long empId = rsp.getEmployee().getId();

            // Check if active rule overlaps the target week
            boolean inRange = (rsp.getEndDate() == null || !rsp.getEndDate().isBefore(startDate))
                    && !rsp.getStartDate().isAfter(endDate);

            if (inRange) {
                result.computeIfAbsent(empId, k -> new EnumMap<>(DayOfWeek.class))
                        .put(rsp.getDayOfWeek(), rsp.getShiftType());
            }
        }

        return result;
    }

    @Transactional(readOnly = true)
    public Map<Long, Map<DayOfWeek, Set<ShiftType>>> getActiveRecurringAvoidMap(List<Employee> employees, LocalDate startDate, LocalDate endDate) {
        Map<Long, Map<DayOfWeek, Set<ShiftType>>> result = new HashMap<>();
        List<RecurringShiftPreference> allActive = recurringRepository.findByActiveTrue();

        for (RecurringShiftPreference rsp : allActive) {
            if (rsp.getEmployee() == null) continue;
            if (rsp.getPreferenceType() != RecurringPreferenceType.DO_NOT_ASSIGN) continue; // Negative restrictions only
            Long empId = rsp.getEmployee().getId();

            boolean inRange = (rsp.getEndDate() == null || !rsp.getEndDate().isBefore(startDate))
                    && !rsp.getStartDate().isAfter(endDate);

            if (inRange) {
                result.computeIfAbsent(empId, k -> new EnumMap<>(DayOfWeek.class))
                        .computeIfAbsent(rsp.getDayOfWeek(), k -> new HashSet<>())
                        .add(rsp.getShiftType());
            }
        }

        return result;
    }

    private RecurringShiftPreferenceResponse toResponse(RecurringShiftPreference r) {
        Employee e = r.getEmployee();
        String code = e != null ? e.getEmployeeCode() : "N/A";
        String name = e != null ? (e.getFirstName() + " " + (e.getLastName() != null ? e.getLastName() : "")).trim() : "N/A";
        return new RecurringShiftPreferenceResponse(
                r.getId(),
                e != null ? e.getId() : null,
                code,
                name,
                r.getDayOfWeek(),
                r.getShiftType(),
                r.getPreferenceType(),
                r.getStartDate(),
                r.getEndDate(),
                r.isActive(),
                r.getReason(),
                r.getCreatedBy(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}
