package com.weeklyroster.service;

import com.weeklyroster.dto.request.HolidayRequest;
import com.weeklyroster.dto.response.HolidayResponse;
import com.weeklyroster.entity.AuditAction;
import com.weeklyroster.entity.Holiday;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.HolidayRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional
public class HolidayService {

    private final HolidayRepository holidayRepository;
    private final AuditService auditService;

    public HolidayService(HolidayRepository holidayRepository, AuditService auditService) {
        this.holidayRepository = holidayRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<HolidayResponse> getAllHolidays() {
        return holidayRepository.findAllByOrderByHolidayDateDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<HolidayResponse> getActiveHolidays() {
        return holidayRepository.findByActiveTrueOrderByHolidayDateAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<HolidayResponse> getUpcomingHolidays() {
        LocalDate today = LocalDate.now();
        return holidayRepository.findByHolidayDateGreaterThanEqualAndActiveTrueOrderByHolidayDateAsc(today).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public HolidayResponse getHolidayById(Long id) {
        Holiday h = holidayRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Holiday not found with id: " + id));
        return toResponse(h);
    }

    public HolidayResponse createHoliday(HolidayRequest req, String adminUsername) {
        if (holidayRepository.existsByHolidayDate(req.holidayDate())) {
            throw new BusinessException("A holiday is already configured for date: " + req.holidayDate());
        }

        Holiday holiday = new Holiday(
                req.name().trim(),
                req.holidayDate(),
                req.description() != null ? req.description().trim() : null
        );
        if (req.active() != null) {
            holiday.setActive(req.active());
        }
        Holiday saved = holidayRepository.save(holiday);

        auditService.log(AuditAction.HOLIDAY_CREATED, "HOLIDAY", saved.getId(), null,
                null, null, null, saved.getName(), "Created holiday: " + saved.getName(), "MANUAL");

        return toResponse(saved);
    }

    public HolidayResponse updateHoliday(Long id, HolidayRequest req, String adminUsername) {
        Holiday holiday = holidayRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Holiday not found with id: " + id));

        if (holidayRepository.existsByHolidayDateAndIdNot(req.holidayDate(), id)) {
            throw new BusinessException("Another holiday already exists on date: " + req.holidayDate());
        }

        String oldVal = holiday.getName() + " (" + holiday.getHolidayDate() + ")";
        holiday.setName(req.name().trim());
        holiday.setHolidayDate(req.holidayDate());
        holiday.setDescription(req.description() != null ? req.description().trim() : null);
        if (req.active() != null) {
            holiday.setActive(req.active());
        }
        holiday.setUpdatedAt(LocalDateTime.now());
        Holiday updated = holidayRepository.save(holiday);

        auditService.log(AuditAction.HOLIDAY_MODIFIED, "HOLIDAY", updated.getId(), null,
                null, null, oldVal, updated.getName() + " (" + updated.getHolidayDate() + ")",
                "Updated holiday #" + updated.getId(), "MANUAL");

        return toResponse(updated);
    }

    public HolidayResponse toggleActive(Long id, String adminUsername) {
        Holiday holiday = holidayRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Holiday not found with id: " + id));
        boolean newActive = !holiday.isActive();
        holiday.setActive(newActive);
        holiday.setUpdatedAt(LocalDateTime.now());
        Holiday updated = holidayRepository.save(holiday);

        auditService.log(AuditAction.HOLIDAY_MODIFIED, "HOLIDAY", updated.getId(), null,
                null, null, String.valueOf(!newActive), String.valueOf(newActive),
                "Toggled holiday active state to " + newActive, "MANUAL");

        return toResponse(updated);
    }

    public void deleteHoliday(Long id, String adminUsername) {
        Holiday holiday = holidayRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Holiday not found with id: " + id));
        holidayRepository.delete(holiday);

        auditService.log(AuditAction.HOLIDAY_DELETED, "HOLIDAY", id, null,
                null, null, holiday.getName(), "DELETED", "Deleted holiday: " + holiday.getName(), "MANUAL");
    }

    @Transactional(readOnly = true)
    public boolean isHoliday(LocalDate date) {
        if (date == null) return false;
        return holidayRepository.findByHolidayDate(date)
                .map(Holiday::isActive)
                .orElse(false);
    }

    private HolidayResponse toResponse(Holiday h) {
        return new HolidayResponse(
                h.getId(),
                h.getName(),
                h.getHolidayDate(),
                h.getDescription(),
                h.isActive(),
                h.getCreatedAt(),
                h.getUpdatedAt()
        );
    }
}
