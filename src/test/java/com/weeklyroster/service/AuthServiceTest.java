package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.weeklyroster.dto.request.ChangePasswordRequest;
import com.weeklyroster.entity.ActivityCategory;
import com.weeklyroster.entity.ActivityStatus;
import com.weeklyroster.entity.Role;
import com.weeklyroster.entity.User;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.UserRepository;
import com.weeklyroster.security.JwtService;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtService jwtService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private EmployeeActivityLogService activityLogService;

    @InjectMocks
    private AuthService authService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setUsername("emp001");
        testUser.setPassword("$2a$10$encodedOldPasswordHash");
        testUser.setRole(Role.ROLE_EMPLOYEE);
        testUser.setEnabled(true);

        Authentication auth = new UsernamePasswordAuthenticationToken(
                "emp001", null, java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_EMPLOYEE"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Password Change - Success with BCrypt Encoding and Activity Log")
    void testChangePassword_Success() {
        ChangePasswordRequest request = new ChangePasswordRequest(
                "oldPassword123", "newPassword456", "newPassword456"
        );

        when(userRepository.findByUsername("emp001")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("oldPassword123", "$2a$10$encodedOldPasswordHash")).thenReturn(true);
        when(passwordEncoder.matches("newPassword456", "$2a$10$encodedOldPasswordHash")).thenReturn(false);
        when(passwordEncoder.encode("newPassword456")).thenReturn("$2a$10$encodedNewPasswordHash");

        Map<String, Object> result = authService.changePassword(request);

        assertNotNull(result);
        assertEquals(true, result.get("success"));
        assertEquals("$2a$10$encodedNewPasswordHash", testUser.getPassword());
        verify(userRepository, times(1)).save(testUser);
        verify(activityLogService, times(1)).logUserActivity(
                eq("emp001"),
                eq(ActivityCategory.SECURITY),
                eq("PASSWORD_CHANGED"),
                eq(ActivityStatus.SUCCESS),
                contains("successfully")
        );
    }

    @Test
    @DisplayName("Password Change - Fails when Confirm Password does not match")
    void testChangePassword_MismatchConfirmation() {
        ChangePasswordRequest request = new ChangePasswordRequest(
                "oldPassword123", "newPassword456", "mismatchPassword"
        );

        when(userRepository.findByUsername("emp001")).thenReturn(Optional.of(testUser));

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.changePassword(request));
        assertTrue(ex.getMessage().contains("New password and confirmation do not match"));

        verify(activityLogService, times(1)).logUserActivity(
                eq("emp001"),
                eq(ActivityCategory.SECURITY),
                eq("PASSWORD_CHANGED"),
                eq(ActivityStatus.FAILED),
                contains("do not match")
        );
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Password Change - Fails when Current Password is incorrect")
    void testChangePassword_IncorrectCurrentPassword() {
        ChangePasswordRequest request = new ChangePasswordRequest(
                "wrongOldPassword", "newPassword456", "newPassword456"
        );

        when(userRepository.findByUsername("emp001")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("wrongOldPassword", "$2a$10$encodedOldPasswordHash")).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.changePassword(request));
        assertTrue(ex.getMessage().contains("Current password is incorrect"));

        verify(activityLogService, times(1)).logUserActivity(
                eq("emp001"),
                eq(ActivityCategory.SECURITY),
                eq("PASSWORD_CHANGED"),
                eq(ActivityStatus.FAILED),
                contains("current password incorrect")
        );
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Password Change - Fails when New Password is same as Current Password")
    void testChangePassword_SameAsOldPassword() {
        ChangePasswordRequest request = new ChangePasswordRequest(
                "oldPassword123", "oldPassword123", "oldPassword123"
        );

        when(userRepository.findByUsername("emp001")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("oldPassword123", "$2a$10$encodedOldPasswordHash")).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.changePassword(request));
        assertTrue(ex.getMessage().contains("New password cannot be the same as current password"));

        verify(activityLogService, times(1)).logUserActivity(
                eq("emp001"),
                eq(ActivityCategory.SECURITY),
                eq("PASSWORD_CHANGED"),
                eq(ActivityStatus.FAILED),
                contains("cannot be the same")
        );
        verify(userRepository, never()).save(any());
    }
}
