package com.weeklyroster.security;

import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.Role;
import com.weeklyroster.entity.User;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    @InjectMocks
    private CustomUserDetailsService userDetailsService;

    private User adminUser;
    private User employeeUser;
    private Employee employee;

    @BeforeEach
    void setUp() {
        adminUser = new User();
        adminUser.setId(1L);
        adminUser.setUsername("admin");
        adminUser.setPassword("encodedPassword");
        adminUser.setRole(Role.ROLE_ADMIN);
        adminUser.setEnabled(true);

        employeeUser = new User();
        employeeUser.setId(2L);
        employeeUser.setUsername("emp001");
        employeeUser.setPassword("encodedPassword");
        employeeUser.setRole(Role.ROLE_EMPLOYEE);
        employeeUser.setEnabled(true);

        employee = new Employee();
        employee.setId(2L);
        employee.setEmployeeCode("EMP001");
        employee.setFirstName("Rajat");
        employee.setLastName("Maurya");
        employee.setEmail("rajat@example.com");
        employee.setUser(employeeUser);
    }

    @Test
    @DisplayName("Admin Login: Load by direct username succeeds")
    void testLoadByUsername_AdminDirect() {
        when(userRepository.findByUsernameIgnoreCase("admin")).thenReturn(Optional.of(adminUser));

        UserDetails details = userDetailsService.loadUserByUsername("admin");
        assertNotNull(details);
        assertEquals("admin", details.getUsername());
        assertTrue(details.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
        verify(userRepository).findByUsernameIgnoreCase("admin");
        verifyNoInteractions(employeeRepository);
    }

    @Test
    @DisplayName("Employee Login: Load by direct username succeeds")
    void testLoadByUsername_EmployeeDirect() {
        when(userRepository.findByUsernameIgnoreCase("emp001")).thenReturn(Optional.of(employeeUser));

        UserDetails details = userDetailsService.loadUserByUsername("emp001");
        assertNotNull(details);
        assertEquals("emp001", details.getUsername());
        assertTrue(details.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_EMPLOYEE")));
    }

    @Test
    @DisplayName("Employee Login: Fallback by Employee Code succeeds when username lookup misses")
    void testLoadByUsername_FallbackByEmployeeCode() {
        when(userRepository.findByUsernameIgnoreCase("EMP001")).thenReturn(Optional.empty());
        when(employeeRepository.findByEmployeeCodeIgnoreCase("EMP001")).thenReturn(Optional.of(employee));

        UserDetails details = userDetailsService.loadUserByUsername("EMP001");
        assertNotNull(details);
        assertEquals("emp001", details.getUsername());
        assertTrue(details.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_EMPLOYEE")));
    }

    @Test
    @DisplayName("Employee Login: Fallback by Email succeeds when username and code miss")
    void testLoadByUsername_FallbackByEmail() {
        when(userRepository.findByUsernameIgnoreCase("rajat@example.com")).thenReturn(Optional.empty());
        when(employeeRepository.findByEmployeeCodeIgnoreCase("rajat@example.com")).thenReturn(Optional.empty());
        when(employeeRepository.findByEmailIgnoreCase("rajat@example.com")).thenReturn(Optional.of(employee));

        UserDetails details = userDetailsService.loadUserByUsername("rajat@example.com");
        assertNotNull(details);
        assertEquals("emp001", details.getUsername());
        assertTrue(details.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_EMPLOYEE")));
    }

    @Test
    @DisplayName("Blank username throws UsernameNotFoundException")
    void testLoadByUsername_BlankThrows() {
        assertThrows(UsernameNotFoundException.class, () -> userDetailsService.loadUserByUsername(""));
        assertThrows(UsernameNotFoundException.class, () -> userDetailsService.loadUserByUsername("   "));
        assertThrows(UsernameNotFoundException.class, () -> userDetailsService.loadUserByUsername(null));
    }

    @Test
    @DisplayName("Non-existent user throws UsernameNotFoundException")
    void testLoadByUsername_NotFoundThrows() {
        when(userRepository.findByUsernameIgnoreCase("unknown")).thenReturn(Optional.empty());
        when(employeeRepository.findByEmployeeCodeIgnoreCase("unknown")).thenReturn(Optional.empty());
        when(employeeRepository.findByEmailIgnoreCase("unknown")).thenReturn(Optional.empty());

        assertThrows(UsernameNotFoundException.class, () -> userDetailsService.loadUserByUsername("unknown"));
    }
}
