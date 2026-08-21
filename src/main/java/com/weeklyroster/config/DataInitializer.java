package com.weeklyroster.config;

import java.util.List;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.Role;
import com.weeklyroster.entity.Shift;
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.entity.User;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.ShiftRepository;
import com.weeklyroster.repository.UserRepository;

@Configuration
@EnableConfigurationProperties(ShiftCapacityProperties.class)
public class DataInitializer {
	@Bean
	CommandLineRunner seedData(UserRepository userRepository, EmployeeRepository employeeRepository,
			ShiftRepository shiftRepository, PasswordEncoder passwordEncoder,
			ShiftCapacityProperties capacityProperties, CsvEmployeeLoader csvEmployeeLoader) {
		return args -> {
			System.out.println(">>> SEED DATA EXECUTING <<<");
			capacityProperties.asMap().forEach((type, capacity) -> {
				Shift shift = shiftRepository.findByShiftType(type).orElseGet(() -> {
					Shift created = new Shift();
					created.setShiftType(type);
					return created;
				});
				shift.setCapacity(type == ShiftType.NIGHT ? Math.min(capacity, 1) : capacity);
				shift.setActive(true);
				if (type == ShiftType.MORNING) {
					shift.setStartTime(java.time.LocalTime.of(7, 0));
					shift.setEndTime(java.time.LocalTime.of(15, 0));
					shift.setOvernight(false);
				} else if (type == ShiftType.GENERAL) {
					shift.setStartTime(java.time.LocalTime.of(9, 30));
					shift.setEndTime(java.time.LocalTime.of(18, 0));
					shift.setOvernight(false);
				} else if (type == ShiftType.EVENING) {
					shift.setStartTime(java.time.LocalTime.of(14, 0));
					shift.setEndTime(java.time.LocalTime.of(22, 0));
					shift.setOvernight(false);
				} else if (type == ShiftType.NIGHT) {
					shift.setStartTime(java.time.LocalTime.of(22, 0));
					shift.setEndTime(java.time.LocalTime.of(7, 0));
					shift.setOvernight(true);
				}
				shiftRepository.save(shift);
			});
			shiftRepository.findByShiftType(ShiftType.OFF).orElseGet(() -> {
				Shift shift = new Shift();
				shift.setShiftType(ShiftType.OFF);
				shift.setCapacity(0);
				shift.setActive(true);
				shift.setOvernight(false);
				return shiftRepository.save(shift);
			});

			String adminUsername = System.getenv().getOrDefault("ADMIN_USERNAME", "Admin");
			String adminPassword = System.getenv().getOrDefault("ADMIN_PASSWORD", "Admin@123");

			userRepository.findByUsername(adminUsername).ifPresentOrElse(admin -> {
				admin.setPassword(passwordEncoder.encode(adminPassword));
				userRepository.save(admin);
			}, () -> {
				User admin = new User();
				admin.setUsername(adminUsername);
				admin.setPassword(passwordEncoder.encode(adminPassword));
				admin.setRole(Role.ROLE_ADMIN);
				admin.setEnabled(true);
				userRepository.save(admin);
			});

			List<SeedEmployee> employees = csvEmployeeLoader.loadEmployees();
			for (SeedEmployee seed : employees) {
				if (!employeeRepository.existsByEmployeeCode(seed.getEmployeeCode())) {
					Employee emp = new Employee();
					emp.setEmployeeCode(seed.getEmployeeCode());
					emp.setFirstName(seed.getFirstName());
					emp.setLastName(seed.getLastName() == null ? "" : seed.getLastName());
					emp.setEmail(seed.getEmail());
					emp.setGender(seed.getGender());
					if (seed.getContactNumber() != null) {
						emp.setContactNumber(seed.getContactNumber());
					}
					employeeRepository.save(emp);
				}
			}

			List<User> allUsers = userRepository.findAll();
			for (Employee emp : employeeRepository.findAll()) {
				String uname = emp.getEmployeeCode().toLowerCase();
				User user = allUsers.stream()
						.filter(u -> u.getUsername() != null && u.getUsername().equalsIgnoreCase(uname))
						.findFirst()
						.orElseGet(() -> {
							User u = new User();
							u.setUsername(uname);
							u.setRole(Role.ROLE_EMPLOYEE);
							return u;
						});
				user.setPassword(passwordEncoder.encode("password123"));
				user.setEnabled(true);
				user = userRepository.save(user);
				emp.setUser(user);
				if (emp.getLastName() == null) {
					emp.setLastName("");
				}
				employeeRepository.save(emp);
				System.out.println("  -> Synchronized user credentials for: " + uname);
			}
		};
	}

}
