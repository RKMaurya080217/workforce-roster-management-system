package com.weeklyroster.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "email_delivery_logs")
public class EmailDeliveryLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private RosterCycle cycle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Employee employee;

    @Column(nullable = false, length = 160)
    private String recipientEmail;

    @Column(nullable = false)
    private LocalDateTime sentAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EmailDeliveryStatus status;

    @Column(length = 500)
    private String errorMessage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GenerationMode mode = GenerationMode.MANUAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "email_type", length = 30)
    private EmailType emailType = EmailType.WEEKLY_ROSTER_DISTRIBUTION;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

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
