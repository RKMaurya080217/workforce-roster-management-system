package com.weeklyroster.controller;

import com.weeklyroster.dto.request.HolidayRequest;
import com.weeklyroster.dto.response.HolidayResponse;
import com.weeklyroster.service.HolidayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/holidays")
@Tag(name = "Holiday Calendar", description = "Endpoints for viewing official holidays")
public class HolidayController {

    private final HolidayService holidayService;

    public HolidayController(HolidayService holidayService) {
        this.holidayService = holidayService;
    }

    @GetMapping
    @Operation(summary = "Get all active holidays")
    public ResponseEntity<List<HolidayResponse>> getActiveHolidays() {
        return ResponseEntity.ok(holidayService.getActiveHolidays());
    }

    @GetMapping("/upcoming")
    @Operation(summary = "Get upcoming active holidays")
    public ResponseEntity<List<HolidayResponse>> getUpcomingHolidays() {
        return ResponseEntity.ok(holidayService.getUpcomingHolidays());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get holiday by ID")
    public ResponseEntity<HolidayResponse> getHolidayById(@PathVariable Long id) {
        return ResponseEntity.ok(holidayService.getHolidayById(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Operation(summary = "Create a new holiday")
    public ResponseEntity<HolidayResponse> createHoliday(@Valid @RequestBody HolidayRequest req, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(holidayService.createHoliday(req, auth != null ? auth.getName() : "ADMIN"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Operation(summary = "Update an existing holiday")
    public ResponseEntity<HolidayResponse> updateHoliday(@PathVariable Long id, @Valid @RequestBody HolidayRequest req, Authentication auth) {
        return ResponseEntity.ok(holidayService.updateHoliday(id, req, auth != null ? auth.getName() : "ADMIN"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Operation(summary = "Delete a holiday")
    public ResponseEntity<Void> deleteHoliday(@PathVariable Long id, Authentication auth) {
        holidayService.deleteHoliday(id, auth != null ? auth.getName() : "ADMIN");
        return ResponseEntity.noContent().build();
    }
}
