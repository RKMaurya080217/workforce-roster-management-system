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
import com.weeklyroster.repository.ShiftRepository;

@Service
public class ShiftService {
    private final ShiftRepository shiftRepository;
    private final EmployeeRepository employeeRepository;

    public ShiftService(ShiftRepository shiftRepository, EmployeeRepository employeeRepository) {
        this.shiftRepository = shiftRepository;
        this.employeeRepository = employeeRepository;
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
    public ShiftResponse update(Long id, UpdateShiftRequest request) {
        if (request.capacity() != null && request.capacity() < 0) {
            throw new BusinessException("Shift capacity cannot be negative");
        }

        Shift shift = shiftRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Shift not found with id: " + id));

        if (request.capacity() != null) {
            if (shift.getShiftType() == ShiftType.NIGHT && request.capacity() > 1) {
                throw new BusinessException("Night shift target cannot exceed 1 employee per day.");
            }
            shift.setCapacity(request.capacity());
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
        if (type == ShiftType.OFF || configuredCapacity == 0) return 0;
        int dailyWorking = Math.max(1, activeEmployees - 1);
        if (type == ShiftType.NIGHT) {
            return Math.min(configuredCapacity, 1);
        }
        if (type == ShiftType.EVENING) {
            return Math.min(configuredCapacity, 1);
        }
        if (type == ShiftType.MORNING) {
            return Math.min(configuredCapacity, dailyWorking >= 6 ? 2 : 1);
        }
        if (type == ShiftType.GENERAL) {
            return Math.min(configuredCapacity, dailyWorking >= 6 ? 2 : 1);
        }
        return Math.min(configuredCapacity, 1);
    }
}
