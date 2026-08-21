package com.weeklyroster.service;

import com.weeklyroster.dto.response.EmployeeActivityPageResponse;
import com.weeklyroster.entity.ActivityCategory;
import com.weeklyroster.entity.ActivityStatus;
import com.weeklyroster.entity.EmployeeActivityLog;
import com.weeklyroster.repository.EmployeeActivityLogRepository;
import com.weeklyroster.repository.EmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmployeeActivityLogServiceTest {

    @Mock
    private EmployeeActivityLogRepository activityLogRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    @InjectMocks
    private EmployeeActivityLogService activityLogService;

    private EmployeeActivityLog sampleLog;

    @BeforeEach
    void setUp() {
        sampleLog = new EmployeeActivityLog();
        sampleLog.setId(100L);
        sampleLog.setEmployeeId(1L);
        sampleLog.setUsername("emp001");
        sampleLog.setCategory(ActivityCategory.ACCOUNT);
        sampleLog.setAction("LOGIN");
        sampleLog.setStatus(ActivityStatus.SUCCESS);
        sampleLog.setDescription("User signed in successfully");
        sampleLog.setSource("WEB");
        sampleLog.setCreatedAt(LocalDateTime.now());
    }

    @Test
    @DisplayName("logActivity saves entry with sanitized secrets and proper status")
    void testLogActivitySanitization() {
        when(activityLogRepository.save(any(EmployeeActivityLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EmployeeActivityLog result = activityLogService.logActivity(
                1L,
                "emp001",
                ActivityCategory.SECURITY,
                "PASSWORD_CHANGED",
                ActivityStatus.SUCCESS,
                "Password changed for user with password: SecretPassword123 and token=BearerXYZ",
                "WEB"
        );

        assertNotNull(result);
        assertEquals("emp001", result.getUsername());
        assertEquals(ActivityCategory.SECURITY, result.getCategory());
        assertEquals("PASSWORD_CHANGED", result.getAction());
        assertEquals(ActivityStatus.SUCCESS, result.getStatus());
        // Verify secret sanitization
        assertFalse(result.getDescription().contains("SecretPassword123"));
        assertFalse(result.getDescription().contains("BearerXYZ"));
        assertTrue(result.getDescription().contains("password=***"));
        assertTrue(result.getDescription().contains("token=***"));
    }

    @Test
    @DisplayName("getMyActivities retrieves paginated records filtered by category and isolated to requesting user")
    void testGetMyActivitiesFilterAndPagination() {
        Page<EmployeeActivityLog> page = new PageImpl<>(List.of(sampleLog));
        when(activityLogRepository.findByUsernameAndCategoryOrderByCreatedAtDesc(
                eq("emp001"), eq(ActivityCategory.ACCOUNT), any(Pageable.class)
        )).thenReturn(page);

        EmployeeActivityPageResponse response = activityLogService.getMyActivities("emp001", "ACCOUNT", 0, 20);

        assertNotNull(response);
        assertEquals(1, response.content().size());
        assertEquals("emp001", response.content().get(0).username());
        assertEquals(ActivityCategory.ACCOUNT, response.content().get(0).category());
        assertEquals("LOGIN", response.content().get(0).action());
        assertEquals(ActivityStatus.SUCCESS, response.content().get(0).status());
        assertFalse(response.hasMore());
    }

    @Test
    @DisplayName("getMyActivities without category fetches all categories for user")
    void testGetMyActivitiesAll() {
        Page<EmployeeActivityLog> page = new PageImpl<>(List.of(sampleLog));
        when(activityLogRepository.findByUsernameOrderByCreatedAtDesc(
                eq("emp001"), any(Pageable.class)
        )).thenReturn(page);

        EmployeeActivityPageResponse response = activityLogService.getMyActivities("emp001", "ALL", 0, 10);

        assertNotNull(response);
        assertEquals(1, response.content().size());
        verify(activityLogRepository, times(1)).findByUsernameOrderByCreatedAtDesc(eq("emp001"), any(Pageable.class));
    }
}
