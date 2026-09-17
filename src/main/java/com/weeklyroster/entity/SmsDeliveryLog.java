package com.weeklyroster.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "sms_delivery_logs", indexes = {
    @Index(name = "idx_sms_emp", columnList = "employee_id"),
    @Index(name = "idx_sms_cycle", columnList = "roster_cycle_id"),
    @Index(name = "idx_sms_status", columnList = "status"),
    @Index(name = "idx_sms_created", columnList = "created_at")
})
public class SmsDeliveryLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "employee_code", length = 40)
    private String employeeCode;

    @Column(name = "mobile_masked", length = 30)
    private String mobileMasked;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 40)
    private SmsMessageType messageType;

    @Column(name = "roster_cycle_id")
    private Long rosterCycleId;

    @Column(name = "provider", nullable = false, length = 40)
    private String provider;

    @Column(name = "provider_message_id", length = 100)
    private String providerMessageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private SmsDeliveryStatus status;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public SmsDeliveryLog() {
    }

    public SmsDeliveryLog(Long employeeId, String employeeCode, String mobileMasked,
                          SmsMessageType messageType, Long rosterCycleId,
                          String provider, String providerMessageId,
                          SmsDeliveryStatus status, String failureReason) {
        this.employeeId = employeeId;
        this.employeeCode = employeeCode;
        this.mobileMasked = mobileMasked;
        this.messageType = messageType;
        this.rosterCycleId = rosterCycleId;
        this.provider = provider;
        this.providerMessageId = providerMessageId;
        this.status = status;
        this.failureReason = failureReason;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getEmployeeId() { return employeeId; }
    public void setEmployeeId(Long employeeId) { this.employeeId = employeeId; }

    public String getEmployeeCode() { return employeeCode; }
    public void setEmployeeCode(String employeeCode) { this.employeeCode = employeeCode; }

    public String getMobileMasked() { return mobileMasked; }
    public void setMobileMasked(String mobileMasked) { this.mobileMasked = mobileMasked; }

    public SmsMessageType getMessageType() { return messageType; }
    public void setMessageType(SmsMessageType messageType) { this.messageType = messageType; }

    public Long getRosterCycleId() { return rosterCycleId; }
    public void setRosterCycleId(Long rosterCycleId) { this.rosterCycleId = rosterCycleId; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getProviderMessageId() { return providerMessageId; }
    public void setProviderMessageId(String providerMessageId) { this.providerMessageId = providerMessageId; }

    public SmsDeliveryStatus getStatus() { return status; }
    public void setStatus(SmsDeliveryStatus status) { this.status = status; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
