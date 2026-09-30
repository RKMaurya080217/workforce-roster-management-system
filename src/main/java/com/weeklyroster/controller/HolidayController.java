package com.weeklyroster.controller;

import com.weeklyroster.dto.response.HolidayResponse;
import com.weeklyroster.service.HolidayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
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
}
