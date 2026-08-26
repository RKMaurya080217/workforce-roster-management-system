package com.weeklyroster.service;

import com.weeklyroster.dto.response.NotificationResponse;
import com.weeklyroster.entity.ActivityCategory;
import com.weeklyroster.entity.ActivityStatus;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.Notification;
import com.weeklyroster.entity.NotificationType;
import com.weeklyroster.entity.Role;
import com.weeklyroster.entity.User;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.NotificationRepository;
import com.weeklyroster.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final EmployeeActivityLogService activityLogService;

    public NotificationService(NotificationRepository notificationRepository,
                               EmployeeRepository employeeRepository,
                               UserRepository userRepository,
                               EmployeeActivityLogService activityLogService) {
        this.notificationRepository = notificationRepository;
        this.employeeRepository = employeeRepository;
        this.userRepository = userRepository;
        this.activityLogService = activityLogService;
    }

    @Transactional
    public Notification createNotification(String recipientUsername,
                                           Long recipientEmployeeId,
                                           String title,
                                           String message,
                                           NotificationType type,
                                           String linkPage,
                                           Long linkId) {
        if (recipientUsername == null || title == null) return null;

        // B8: Duplicate Notification Protection
        // If an identical notification was sent to the same user in the last 15 minutes, suppress duplicate
        if (linkId != null && type != null) {
            LocalDateTime cutoff = LocalDateTime.now().minusMinutes(15);
            if (notificationRepository.existsByRecipientUsernameAndTypeAndLinkIdAndCreatedAtAfter(
                    recipientUsername, type, linkId, cutoff)) {
                log.info("Duplicate notification suppressed for user {} (event: {}, linkId: {})", recipientUsername, type, linkId);
                return null;
            }
        }

        Notification notification = new Notification();
        notification.setRecipientUsername(recipientUsername);
        notification.setRecipientEmployeeId(recipientEmployeeId);
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setType(type);
        notification.setLinkPage(linkPage);
        notification.setLinkId(linkId);
        notification.setCreatedAt(LocalDateTime.now());
        notification.setReadStatus(false);

        try {
            Notification saved = notificationRepository.save(notification);
            if (activityLogService != null && recipientUsername != null) {
                if (type == NotificationType.ROSTER_PUBLISHED) {
                    activityLogService.logActivity(recipientEmployeeId, recipientUsername,
                            ActivityCategory.ROSTER, "ROSTER_PUBLISHED", ActivityStatus.SUCCESS,
                            "Weekly roster published notification received", "SYSTEM");
                } else if (type == NotificationType.SHIFT_CHANGED || type == NotificationType.SWAP_EXECUTED) {
                    activityLogService.logActivity(recipientEmployeeId, recipientUsername,
                            ActivityCategory.ROSTER, "SHIFT_CHANGED", ActivityStatus.SUCCESS,
                            "Shift update notification received: " + title, "SYSTEM");
                }
            }
            return saved;
        } catch (Exception e) {
            log.error("Failed to save notification for user {}: {}", recipientUsername, e.getMessage());
            return notification;
        }
    }

    @Transactional
    public void notifyAllActiveEmployees(String title,
                                         String message,
                                         NotificationType type,
                                         String linkPage,
                                         Long linkId) {
        List<Employee> activeEmployees = employeeRepository.findByActiveTrueOrderByIdAsc();
        List<Notification> notifications = new ArrayList<>();
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(15);

        for (Employee emp : activeEmployees) {
            String username = emp.getUser() != null ? emp.getUser().getUsername() : emp.getEmployeeCode().toLowerCase();
            
            // B8: Duplicate protection for broadcast events
            if (linkId != null && type != null && notificationRepository.existsByRecipientUsernameAndTypeAndLinkIdAndCreatedAtAfter(username, type, linkId, cutoff)) {
                continue;
            }

            Notification n = new Notification();
            n.setRecipientUsername(username);
            n.setRecipientEmployeeId(emp.getId());
            n.setTitle(title);
            n.setMessage(message);
            n.setType(type);
            n.setLinkPage(linkPage);
            n.setLinkId(linkId);
            n.setCreatedAt(LocalDateTime.now());
            n.setReadStatus(false);
            notifications.add(n);

            if (activityLogService != null) {
                if (type == NotificationType.ROSTER_PUBLISHED) {
                    activityLogService.logActivity(emp.getId(), username,
                            ActivityCategory.ROSTER, "ROSTER_PUBLISHED", ActivityStatus.SUCCESS,
                            "Weekly roster published notification received", "SYSTEM");
                }
            }
        }
        if (!notifications.isEmpty()) {
            notificationRepository.saveAll(notifications);
            log.info("Dispatched {} notifications to active employees for event: {}", notifications.size(), type);
        }
    }

    @Transactional
    public void notifyAdmins(String title,
                             String message,
                             NotificationType type,
                             String linkPage,
                             Long linkId) {
        List<User> admins = userRepository.findByRole(Role.ROLE_ADMIN);
        List<Notification> notifications = new ArrayList<>();
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(15);

        for (User admin : admins) {
            if (linkId != null && type != null && notificationRepository.existsByRecipientUsernameAndTypeAndLinkIdAndCreatedAtAfter(admin.getUsername(), type, linkId, cutoff)) {
                continue;
            }

            Notification n = new Notification();
            n.setRecipientUsername(admin.getUsername());
            n.setRecipientEmployeeId(null);
            n.setTitle(title);
            n.setMessage(message);
            n.setType(type);
            n.setLinkPage(linkPage);
            n.setLinkId(linkId);
            n.setCreatedAt(LocalDateTime.now());
            n.setReadStatus(false);
            notifications.add(n);
        }
        if (!notifications.isEmpty()) {
            notificationRepository.saveAll(notifications);
            log.info("Dispatched {} notifications to admins for event: {}", notifications.size(), type);
        }
    }

    @Transactional
    public void notifyEmployee(Employee employee,
                               String title,
                               String message,
                               NotificationType type,
                               String linkPage,
                               Long linkId) {
        if (employee == null) return;
        String username = null;
        try {
            if (employee.getUser() != null) {
                username = employee.getUser().getUsername();
            }
        } catch (Exception ignored) {}
        if (username == null && employee.getEmployeeCode() != null) {
            username = employee.getEmployeeCode().toLowerCase();
        }
        if (username != null) {
            createNotification(username, employee.getId(), title, message, type, linkPage, linkId);
        }
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> getMyNotifications(String username) {
        return getMyNotificationsFiltered(username, "ALL", 50);
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> getMyNotificationsFiltered(String username, String filter, int limit) {
        if (username == null) return List.of();
        int safeLimit = (limit > 0 && limit <= 200) ? limit : 50;
        String normFilter = filter != null ? filter.trim().toUpperCase() : "ALL";

        List<Notification> list;
        switch (normFilter) {
            case "UNREAD" -> list = notificationRepository.findByRecipientUsernameAndReadStatusOrderByCreatedAtDesc(username, false);
            case "READ" -> list = notificationRepository.findByRecipientUsernameAndReadStatusOrderByCreatedAtDesc(username, true);
            case "ROSTER" -> list = notificationRepository.findByRecipientUsernameAndTypeInOrderByCreatedAtDesc(
                    username, List.of(NotificationType.ROSTER_PUBLISHED, NotificationType.SHIFT_CHANGED,
                            NotificationType.OVERRIDE_APPLIED, NotificationType.SWAP_EXECUTED,
                            NotificationType.ROSTER_LOCKED, NotificationType.ROSTER_UNLOCKED,
                            NotificationType.ROSTER_VALIDATION_ALERT));
            case "LEAVE" -> list = notificationRepository.findByRecipientUsernameAndTypeInOrderByCreatedAtDesc(
                    username, List.of(NotificationType.LEAVE_DECISION));
            case "PROFILE" -> list = notificationRepository.findByRecipientUsernameAndTypeInOrderByCreatedAtDesc(
                    username, List.of(NotificationType.PROFILE_CHANGE_REQUESTED, NotificationType.PROFILE_CHANGE_DECISION,
                            NotificationType.PREFERENCE_SUBMITTED, NotificationType.PREFERENCE_DECISION));
            case "SYSTEM" -> list = notificationRepository.findByRecipientUsernameAndTypeInOrderByCreatedAtDesc(
                    username, List.of(NotificationType.ADMIN_ALERT, NotificationType.SYSTEM_ANNOUNCEMENT,
                            NotificationType.HANDOVER_CREATED, NotificationType.HANDOVER_ASSIGNED));
            default -> list = notificationRepository.findByRecipientUsernameOrderByCreatedAtDesc(username);
        }

        return list.stream()
                .limit(safeLimit)
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(String username) {
        return notificationRepository.countByRecipientUsernameAndReadStatusFalse(username);
    }

    @Transactional
    public NotificationResponse markAsRead(Long id, String username) {
        Notification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found with id: " + id));
        if (notification.getRecipientUsername().equalsIgnoreCase(username)) {
            notification.setReadStatus(true);
            notification = notificationRepository.save(notification);

            if (activityLogService != null) {
                activityLogService.logUserActivity(username, ActivityCategory.NOTIFICATION,
                        "NOTIFICATION_VIEWED", ActivityStatus.SUCCESS,
                        "Notification viewed: " + notification.getTitle());
            }
        }
        return toResponse(notification);
    }

    @Transactional
    public void markAllAsRead(String username) {
        List<Notification> list = notificationRepository.findByRecipientUsernameOrderByCreatedAtDesc(username);
        for (Notification n : list) {
            n.setReadStatus(true);
        }
        notificationRepository.saveAll(list);

        if (activityLogService != null && !list.isEmpty()) {
            activityLogService.logUserActivity(username, ActivityCategory.NOTIFICATION,
                    "NOTIFICATION_VIEWED", ActivityStatus.SUCCESS,
                    "Marked all notifications as read (" + list.size() + " notifications)");
        }
    }

    public NotificationResponse toResponse(Notification n) {
        return new NotificationResponse(
                n.getId(),
                n.getRecipientUsername(),
                n.getRecipientEmployeeId(),
                n.getTitle(),
                n.getMessage(),
                n.getType(),
                n.getLinkPage(),
                n.getLinkId(),
                n.getCreatedAt(),
                n.isReadStatus()
        );
    }
}
