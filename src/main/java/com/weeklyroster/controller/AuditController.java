package com.weeklyroster.controller;

import com.weeklyroster.dto.response.AuditLogResponse;
import com.weeklyroster.entity.AuditAction;
import com.weeklyroster.service.AuditService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/audit-logs")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
public class AuditController {

	private final AuditService auditService;

	public AuditController(AuditService auditService) {
		this.auditService = auditService;
	}

	@GetMapping
	public ResponseEntity<List<AuditLogResponse>> getAuditLogs(
			@RequestParam(name = "cycleId", required = false) Long cycleId,
			@RequestParam(name = "action", required = false) String action,
			@RequestParam(name = "actor", required = false) String actor,
			@RequestParam(name = "employeeId", required = false) Long employeeId,
			@RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
			@RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
		AuditAction auditAction = null;
		if (action != null && !action.trim().isEmpty()) {
			try {
				auditAction = AuditAction.valueOf(action.trim().toUpperCase());
			} catch (IllegalArgumentException ignored) {
			}
		}
		return ResponseEntity.ok(auditService.searchAuditLogs(cycleId, auditAction, actor, employeeId, from, to));
	}
}
