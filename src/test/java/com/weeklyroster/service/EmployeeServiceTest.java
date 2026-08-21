package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.weeklyroster.dto.request.EmployeeRequest;
import com.weeklyroster.dto.response.EmployeeResponse;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.Gender;
import com.weeklyroster.entity.User;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class EmployeeServiceTest {

    @Mock
    private EmployeeRepository employeeRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private EmployeeService employeeService;

    private Employee employee;

    @BeforeEach
    void setUp() {
        employee = new Employee();
        employee.setId(1L);
        employee.setEmployeeCode("EMP001");
        employee.setFirstName("Rajat");
        employee.setLastName("Maurya");
        employee.setEmail("rajat@cris.com");
        employee.setGender(Gender.MALE);
        employee.setActive(true);
    }

    @Test
    void testGetById_ReturnsEmployee() {
        when(employeeRepository.findById(1L)).thenReturn(Optional.of(employee));

        EmployeeResponse response = employeeService.getById(1L);

        assertNotNull(response);
        assertEquals("EMP001", response.employeeCode());
        assertEquals("Rajat", response.firstName());
    }

    @Test
    void testCreate_SupportsSingleNameEmployee() {
        EmployeeRequest request = new EmployeeRequest("EMP003", "Shriram", null, "shriram@cris.com", Gender.MALE, null, null);

        when(employeeRepository.existsByEmployeeCode("EMP003")).thenReturn(false);
        when(employeeRepository.existsByEmail("shriram@cris.com")).thenReturn(false);
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> {
            Employee e = inv.getArgument(0);
            e.setId(3L);
            return e;
        });

        EmployeeResponse response = employeeService.create(request);

        assertNotNull(response);
        assertEquals("Shriram", response.firstName());
        assertEquals("", response.lastName());
    }

    @Test
    void testDelete_DeactivatesEmployeeAndUser() {
        User user = new User();
        user.setId(5L);
        user.setEnabled(true);
        employee.setUser(user);

        when(employeeRepository.findById(1L)).thenReturn(Optional.of(employee));

        employeeService.delete(1L);

        assertFalse(employee.isActive());
        assertFalse(user.isEnabled());
    }
}
