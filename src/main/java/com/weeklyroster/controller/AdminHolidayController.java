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
@RequestMapping("/api/admin/holidays")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@Tag(name = "Admin Holiday Management", description = "Admin endpoints for managing holiday calendar")
public class AdminHolidayController {

    private final HolidayService holidayService;

    public AdminHolidayController(HolidayService holidayService) {
        this.holidayService = holidayService;
    }

    @GetMapping
    @Operation(summary = "Get all holidays (active & inactive)")
    public ResponseEntity<List<HolidayResponse>> getAllHolidays() {
        return ResponseEntity.ok(holidayService.getAllHolidays());
    }

    @PostMapping
    @Operation(summary = "Create a new holiday")
    public ResponseEntity<HolidayResponse> createHoliday(@Valid @RequestBody HolidayRequest req, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(holidayService.createHoliday(req, auth.getName()));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an existing holiday")
    public ResponseEntity<HolidayResponse> updateHoliday(@PathVariable Long id, @Valid @RequestBody HolidayRequest req, Authentication auth) {
        return ResponseEntity.ok(holidayService.updateHoliday(id, req, auth.getName()));
    }

    @PatchMapping("/{id}/toggle-active")
    @Operation(summary = "Toggle active status of a holiday")
    public ResponseEntity<HolidayResponse> toggleActive(@PathVariable Long id, Authentication auth) {
        return ResponseEntity.ok(holidayService.toggleActive(id, auth.getName()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a holiday")
    public ResponseEntity<Void> deleteHoliday(@PathVariable Long id, Authentication auth) {
        holidayService.deleteHoliday(id, auth.getName());
        return ResponseEntity.noContent().build();
    }
}
