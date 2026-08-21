package com.weeklyroster.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "notifications", indexes = {
    @Index(name = "idx_notif_user", columnList = "recipient_username"),
    @Index(name = "idx_notif_emp", columnList = "recipient_employee_id"),
    @Index(name = "idx_notif_created", columnList = "created_at")
})
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipient_username", nullable = false, length = 100)
    private String recipientUsername;

    @Column(name = "recipient_employee_id")
    private Long recipientEmployeeId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 1000)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private NotificationType type;

    @Column(name = "link_page", length = 50)
    private String linkPage; // e.g., "roster", "employeeWorkspace", "leaves", "health"

    @Column(name = "link_id")
    private Long linkId; // e.g., cycleId or leaveId

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "read_status", nullable = false)
    private boolean readStatus = false;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRecipientUsername() { return recipientUsername; }
    public void setRecipientUsername(String recipientUsername) { this.recipientUsername = recipientUsername; }
    public Long getRecipientEmployeeId() { return recipientEmployeeId; }
    public void setRecipientEmployeeId(Long recipientEmployeeId) { this.recipientEmployeeId = recipientEmployeeId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public NotificationType getType() { return type; }
    public void setType(NotificationType type) { this.type = type; }
    public String getLinkPage() { return linkPage; }
    public void setLinkPage(String linkPage) { this.linkPage = linkPage; }
    public Long getLinkId() { return linkId; }
    public void setLinkId(Long linkId) { this.linkId = linkId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public boolean isReadStatus() { return readStatus; }
    public void setReadStatus(boolean readStatus) { this.readStatus = readStatus; }
}
