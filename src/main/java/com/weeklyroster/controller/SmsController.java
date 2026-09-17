package com.weeklyroster.controller;

import com.weeklyroster.dto.response.SmsDiagnosticsResponse;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.SmsDeliveryLog;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.service.sms.SmsDeliveryResult;
import com.weeklyroster.service.sms.SmsService;
import com.weeklyroster.util.PhoneUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/sms", "/api/notifications/sms"})
public class SmsController {

    private final SmsService smsService;
    private final EmployeeRepository employeeRepository;

    @Autowired
    public SmsController(SmsService smsService, EmployeeRepository employeeRepository) {
        this.smsService = smsService;
        this.employeeRepository = employeeRepository;
    }

    @GetMapping("/diagnostics")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SmsDiagnosticsResponse> getDiagnostics() {
        return ResponseEntity.ok(smsService.getDiagnostics());
    }

    @PostMapping("/admin-test")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> sendAdminTest(@RequestBody(required = false) Map<String, Object> body) {
        Long employeeId = null;
        String phone = null;

        if (body != null) {
            if (body.get("employeeId") != null) {
                try {
                    employeeId = Long.valueOf(body.get("employeeId").toString());
                } catch (Exception ignored) {}
            }
            if (body.get("phone") != null) {
                phone = body.get("phone").toString().trim();
            }
        }

        if (employeeId != null && (phone == null || phone.isBlank())) {
            Employee emp = employeeRepository.findById(employeeId).orElse(null);
            if (emp != null && emp.getContactNumber() != null) {
                phone = emp.getContactNumber();
            }
        }

        SmsDeliveryResult result = smsService.sendAdminTestSms(phone, employeeId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", result.success());
        response.put("status", result.deliveryStatus() != null ? result.deliveryStatus().name() : (result.success() ? "REQUEST_ACCEPTED" : "REQUEST_FAILED"));
        response.put("provider", result.provider());
        response.put("messageId", result.messageId());
        response.put("errorMessage", result.errorMessage());
        response.put("recipientPhoneMasked", phone != null ? PhoneUtils.maskPhone(phone) : "******");

        return ResponseEntity.ok(response);
    }

    @GetMapping("/logs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<SmsDeliveryLog>> getRecentLogs() {
        return ResponseEntity.ok(smsService.getRecentLogs());
    }
}
