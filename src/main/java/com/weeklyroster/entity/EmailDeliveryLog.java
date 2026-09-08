package com.weeklyroster.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@DiscriminatorValue("EMAIL_DELIVERY")
public class EmailDeliveryLog extends SystemAuditLogEntry {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cycle_id", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private RosterCycle cycle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Employee employee;

    @Column(name = "recipient_email", length = 160)
    private String recipientEmail;

    @Column(name = "sent_at")
    private LocalDateTime sentAt = LocalDateTime.now();

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", length = 20)
    private EmailDeliveryStatus status;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "generation_mode", length = 20)
    private GenerationMode mode = GenerationMode.MANUAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "email_type", length = 30)
    private EmailType emailType = EmailType.WEEKLY_ROSTER_DISTRIBUTION;

    public RosterCycle getCycle() { return cycle; }
    public void setCycle(RosterCycle cycle) { this.cycle = cycle; }
    public Employee getEmployee() { return employee; }
    public void setEmployee(Employee employee) { this.employee = employee; }
    public String getRecipientEmail() { return recipientEmail; }
    public void setRecipientEmail(String recipientEmail) { this.recipientEmail = recipientEmail; }
    public LocalDateTime getSentAt() { return sentAt; }
    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }
    public EmailDeliveryStatus getStatus() { return status; }
    public void setStatus(EmailDeliveryStatus status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public GenerationMode getMode() { return mode; }
    public void setMode(GenerationMode mode) { this.mode = mode; }
    public EmailType getEmailType() { return emailType; }
    public void setEmailType(EmailType emailType) { this.emailType = emailType; }
}
