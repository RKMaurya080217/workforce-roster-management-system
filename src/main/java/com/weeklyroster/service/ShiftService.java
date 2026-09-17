package com.weeklyroster.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.weeklyroster.dto.request.UpdateShiftRequest;
import com.weeklyroster.dto.response.ShiftResponse;
import com.weeklyroster.entity.Shift;
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.entity.AuditAction;
import com.weeklyroster.repository.ShiftRepository;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Map;

@Service
public class ShiftService {
    private final ShiftRepository shiftRepository;
    private final EmployeeRepository employeeRepository;
    private final AuditService auditService;

    @Autowired
    public ShiftService(ShiftRepository shiftRepository,
                        EmployeeRepository employeeRepository,
                        @Autowired(required = false) AuditService auditService) {
        this.shiftRepository = shiftRepository;
        this.employeeRepository = employeeRepository;
        this.auditService = auditService;
    }

    public ShiftService(ShiftRepository shiftRepository, EmployeeRepository employeeRepository) {
        this(shiftRepository, employeeRepository, null);
    }

    @Transactional(readOnly = true)
    public List<ShiftResponse> allActive() {
        long activeEmployees = employeeRepository != null ? employeeRepository.countByActiveTrue() : 7;
        return shiftRepository.findByActiveTrueOrderByIdAsc().stream()
                .map(s -> toResponse(s, (int) activeEmployees))
                .toList();
    }

    @Transactional
    public ShiftResponse updateCapacity(Long id, int capacity) {
        return update(id, new UpdateShiftRequest(capacity, null, null, null));
    }

    @Transactional
    public List<ShiftResponse> updateBulkCapacities(Map<String, Integer> capacities) {
        if (capacities == null || capacities.isEmpty()) {
            return allActive();
        }
        for (Map.Entry<String, Integer> entry : capacities.entrySet()) {
            String key = entry.getKey();
            Integer cap = entry.getValue();
            if (cap == null) continue;
            try {
                ShiftType type = ShiftType.valueOf(key.trim().toUpperCase());
                shiftRepository.findByShiftType(type).ifPresent(shift -> {
                    update(shift.getId(), new UpdateShiftRequest(cap, null, null, null));
                });
            } catch (IllegalArgumentException ignored) {
                // Ignore unknown shift type keys gracefully
            }
        }
        return allActive();
    }

    @Transactional
    public ShiftResponse update(Long id, UpdateShiftRequest request) {
        if (request.capacity() != null) {
            if (request.capacity() < 0) {
                throw new BusinessException("Shift capacity cannot be negative");
            }
            if (request.capacity() > 50) {
                throw new BusinessException("Shift capacity cannot exceed 50 employees per shift");
            }
        }

        Shift shift = shiftRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Shift not found with id: " + id));

        if (request.capacity() != null) {
            int oldCap = shift.getCapacity();
            shift.setCapacity(request.capacity());
            if (auditService != null && oldCap != request.capacity()) {
                try {
                    auditService.log(
                            AuditAction.SHIFT_CAPACITY_UPDATED,
                            "SHIFT",
                            shift.getId(),
                            null,
                            null,
                            null,
                            "capacity=" + oldCap,
                            "capacity=" + request.capacity(),
                            "Shift " + shift.getShiftType() + " capacity updated to " + request.capacity(),
                            "MANUAL"
                    );
                } catch (Exception ignored) {
                    // Non-fatal audit log protection
                }
            }
        }
        if (request.startTime() != null) {
            shift.setStartTime(request.startTime());
        }
        if (request.endTime() != null) {
            shift.setEndTime(request.endTime());
        }
        if (request.overnight() != null) {
            shift.setOvernight(request.overnight());
        }

        long activeEmployees = employeeRepository != null ? employeeRepository.countByActiveTrue() : 7;
        return toResponse(shift, (int) activeEmployees);
    }

    public ShiftResponse toResponse(Shift shift) {
        return toResponse(shift, 7);
    }

    public ShiftResponse toResponse(Shift shift, int activeEmployees) {
        int feasible = calculateFeasibleCapacity(shift.getShiftType(), shift.getCapacity(), activeEmployees);
        return new ShiftResponse(
                shift.getId(),
                shift.getShiftType(),
                shift.getCapacity(),
                feasible,
                shift.isActive(),
                shift.getStartTime(),
                shift.getEndTime(),
                shift.isOvernight(),
                shift.getTimingDisplay()
        );
    }

    private int calculateFeasibleCapacity(ShiftType type, int configuredCapacity, int activeEmployees) {
        if (type == ShiftType.OFF || configuredCapacity <= 0) return 0;
        int dailyWorking = Math.max(1, activeEmployees - 1);
        return Math.min(configuredCapacity, dailyWorking);
    }
}
