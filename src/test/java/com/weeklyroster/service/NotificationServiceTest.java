package com.weeklyroster.service;

import com.weeklyroster.dto.response.NotificationResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.NotificationRepository;
import com.weeklyroster.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private EmployeeRepository employeeRepository;

    @InjectMocks
    private NotificationService notificationService;

    private User adminUser;
    private User employeeUser;
    private Employee employee;

    @BeforeEach
    void setUp() {
        adminUser = new User();
        adminUser.setId(1L);
        adminUser.setUsername("admin");
        adminUser.setRole(Role.ROLE_ADMIN);

        employeeUser = new User();
        employeeUser.setId(2L);
        employeeUser.setUsername("emp001");
        employeeUser.setRole(Role.ROLE_EMPLOYEE);

        employee = new Employee();
        employee.setId(10L);
        employee.setUser(employeeUser);
        employee.setEmployeeCode("EMP001");
        employee.setFirstName("Alice");
        employee.setLastName("Smith");
        employee.setActive(true);
    }

    @Test
    @DisplayName("notifyEmployee creates notification for valid employee user")
    void testNotifyEmployee() {
        when(notificationRepository.save(any(Notification.class))).thenAnswer(i -> {
            Notification n = i.getArgument(0);
            n.setId(100L);
            return n;
        });

        notificationService.notifyEmployee(
                employee,
                "Shift Changed",
                "Your shift was changed to MORNING",
                NotificationType.SHIFT_CHANGED,
                "employeeWorkspace",
                1L
        );

        verify(notificationRepository).save(any(Notification.class));
    }

    @Test
    @DisplayName("notifyAdmins dispatches notifications to all admin users")
    void testNotifyAdmins() {
        when(userRepository.findByRole(Role.ROLE_ADMIN)).thenReturn(List.of(adminUser));
        when(notificationRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        notificationService.notifyAdmins(
                "Critical Conflict Alert",
                "Coverage rule violated",
                NotificationType.ADMIN_ALERT,
                "health",
                1L
        );

        verify(notificationRepository).saveAll(any());
    }

    @Test
    @DisplayName("markAsRead marks notification as read when owned by user")
    void testMarkAsRead() {
        Notification notification = new Notification();
        notification.setId(1L);
        notification.setRecipientUsername("emp001");
        notification.setReadStatus(false);
        notification.setCreatedAt(LocalDateTime.now());

        when(notificationRepository.findById(1L)).thenReturn(Optional.of(notification));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(i -> i.getArgument(0));

        NotificationResponse response = notificationService.markAsRead(1L, "emp001");

        assertTrue(response.readStatus());
    }

    @Test
    @DisplayName("markAllAsRead marks all unread notifications as read for recipient")
    void testMarkAllAsRead() {
        Notification n1 = new Notification();
        n1.setId(1L);
        n1.setRecipientUsername("emp001");
        n1.setReadStatus(false);

        when(notificationRepository.findByRecipientUsernameOrderByCreatedAtDesc("emp001")).thenReturn(List.of(n1));

        notificationService.markAllAsRead("emp001");

        assertTrue(n1.isReadStatus());
        verify(notificationRepository).saveAll(List.of(n1));
    }
}
