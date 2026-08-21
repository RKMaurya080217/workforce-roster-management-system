package com.weeklyroster.controller;

import com.weeklyroster.dto.request.UpdateShiftRequest;
import com.weeklyroster.dto.response.ShiftResponse;
import com.weeklyroster.service.ShiftService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shifts")
public class ShiftController {
    private final ShiftService shiftService;

    public ShiftController(ShiftService shiftService) {
        this.shiftService = shiftService;
    }

    @GetMapping
    public ResponseEntity<List<ShiftResponse>> all() {
        return ResponseEntity.ok(shiftService.allActive());
    }

    @PutMapping("/{id}")
    public ResponseEntity<ShiftResponse> update(
            @PathVariable("id") Long id,
            @Valid @RequestBody UpdateShiftRequest request) {
        return ResponseEntity.ok(shiftService.update(id, request));
    }
}
