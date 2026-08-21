package com.weeklyroster.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.Gender;
import com.weeklyroster.entity.Role;
import com.weeklyroster.entity.User;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
class WorkspaceE2EIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long employeeId;

    @BeforeEach
    void setup() {
        User user = userRepository.findByUsername("emp001").orElseGet(() -> {
            User u = new User();
            u.setUsername("emp001");
            u.setRole(Role.ROLE_EMPLOYEE);
            u.setEnabled(true);
            return u;
        });
        user.setPassword(passwordEncoder.encode("password123"));
        user.setEnabled(true);
        user = userRepository.save(user);

        final User finalUser = user;
        Employee employee = employeeRepository.findByUserUsername("emp001").orElseGet(() -> {
            Employee emp = new Employee();
            emp.setEmployeeCode("EMP001");
            emp.setFirstName("Rajat");
            emp.setLastName("Maurya");
            emp.setEmail("rajat@example.com");
            emp.setGender(Gender.MALE);
            return emp;
        });
        employee.setUser(finalUser);
        employee = employeeRepository.save(employee);
        this.employeeId = employee.getId();
    }

    @Test
    @WithMockUser(username = "emp001", authorities = {"ROLE_EMPLOYEE"})
    @DisplayName("Employee My Workspace Data Endpoints Work Without Reflection Parameter Exception")
    void testMyWorkspaceEndpoints_WithMockMvc() throws Exception {
        // 1. Employee roster endpoint (/api/rosters/employee/{employeeId})
        mockMvc.perform(get("/api/rosters/employee/" + employeeId).accept(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // 2. Employee leaves endpoint (/api/leaves/my/{employeeId})
        mockMvc.perform(get("/api/leaves/my/" + employeeId).accept(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
