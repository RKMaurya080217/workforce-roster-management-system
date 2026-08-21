package com.weeklyroster.controller;

import com.weeklyroster.dto.request.EmployeeRequest;
import com.weeklyroster.dto.response.EmployeeResponse;
import com.weeklyroster.service.EmployeeService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/employees")
public class EmployeeController {
	private final EmployeeService employeeService;

	public EmployeeController(EmployeeService employeeService) {
		this.employeeService = employeeService;
	}

	@GetMapping
	public ResponseEntity<List<EmployeeResponse>> all() {
		return ResponseEntity.ok(employeeService.all());
	}

	@GetMapping("/active")
	public ResponseEntity<List<EmployeeResponse>> active() {
		return ResponseEntity.ok(employeeService.active());
	}

	@GetMapping("/me")
	public ResponseEntity<EmployeeResponse> getMyProfile() {
		return ResponseEntity.ok(employeeService.getMyProfile());
	}

	@PutMapping("/me")
	public ResponseEntity<EmployeeResponse> updateMyProfile(@Valid @RequestBody com.weeklyroster.dto.request.UpdateMyProfileRequest request) {
		return ResponseEntity.ok(employeeService.updateMyProfile(request));
	}

	@GetMapping("/{id}")
	public ResponseEntity<EmployeeResponse> getById(@PathVariable("id") Long id) {
		return ResponseEntity.ok(employeeService.getById(id));
	}

	@PostMapping
	public ResponseEntity<EmployeeResponse> create(@Valid @RequestBody EmployeeRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(employeeService.create(request));
	}

	@PutMapping("/{id}")
	public ResponseEntity<EmployeeResponse> update(@PathVariable("id") Long id, @Valid @RequestBody EmployeeRequest request) {
		return ResponseEntity.ok(employeeService.update(id, request));
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
		employeeService.delete(id);
		return ResponseEntity.noContent().build();
	}

	@PutMapping("/{id}/toggle")
	public ResponseEntity<Void> toggle(@PathVariable("id") Long id) {
		employeeService.toggleStatus(id);
		return ResponseEntity.ok().build();
	}
}
