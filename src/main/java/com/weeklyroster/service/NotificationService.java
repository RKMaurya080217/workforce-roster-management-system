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
        for (Employee emp : activeEmployees) {
            String username = emp.getUser() != null ? emp.getUser().getUsername() : emp.getEmployeeCode().toLowerCase();
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
        for (User admin : admins) {
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
        String username = employee.getUser() != null ? employee.getUser().getUsername() : employee.getEmployeeCode().toLowerCase();
        createNotification(username, employee.getId(), title, message, type, linkPage, linkId);
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> getMyNotifications(String username) {
        return notificationRepository.findByRecipientUsernameOrderByCreatedAtDesc(username)
                .stream().map(this::toResponse).toList();
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
