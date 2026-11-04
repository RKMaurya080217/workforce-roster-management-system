package com.weeklyroster.security;

import com.weeklyroster.entity.User;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class CustomUserDetailsService implements UserDetailsService {
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this(userRepository, null);
    }

    @Autowired
    public CustomUserDetailsService(UserRepository userRepository,
                                    @Autowired(required = false) EmployeeRepository employeeRepository) {
        this.userRepository = userRepository;
        this.employeeRepository = employeeRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if (username == null || username.isBlank()) {
            throw new UsernameNotFoundException("Username cannot be empty");
        }
        String clean = username.trim();

        // 1. Direct username lookup (e.g. admin, emp001)
        Optional<User> userOpt = userRepository.findByUsernameIgnoreCase(clean);

        // 2. Fallback: Lookup by employeeCode (e.g. EMP001) if not found directly
        if (userOpt.isEmpty() && employeeRepository != null) {
            userOpt = employeeRepository.findByEmployeeCodeIgnoreCase(clean)
                    .map(emp -> emp.getUser())
                    .filter(u -> u != null);
        }

        // 3. Fallback: Lookup by email address if still not found
        if (userOpt.isEmpty() && employeeRepository != null) {
            userOpt = employeeRepository.findByEmailIgnoreCase(clean)
                    .map(emp -> emp.getUser())
                    .filter(u -> u != null);
        }

        return userOpt
                .map(UserPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }
}
