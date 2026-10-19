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
			ShiftCapacityProperties capacityProperties, CsvEmployeeLoader csvEmployeeLoader,
			org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
		return args -> {
			fixSqliteAuditActionCheckConstraint(jdbcTemplate);
			migrateWorkingDaysColumns(jdbcTemplate);
			System.out.println(">>> SEED DATA EXECUTING <<<");
			capacityProperties.asMap().forEach((type, defaultCapacity) -> {
				var existingOpt = shiftRepository.findByShiftType(type);
				if (existingOpt.isPresent()) {
					Shift existing = existingOpt.get();
					boolean needsSave = false;
					if (existing.getStartTime() == null || existing.getEndTime() == null) {
						setShiftTimingDefaults(existing, type);
						needsSave = true;
					}
					if (needsSave) {
						shiftRepository.save(existing);
					}
				} else {
					Shift created = new Shift();
					created.setShiftType(type);
					created.setCapacity(defaultCapacity);
					created.setActive(true);
					setShiftTimingDefaults(created, type);
					shiftRepository.save(created);
				}
			});
			shiftRepository.findByShiftType(ShiftType.OFF).orElseGet(() -> {
				Shift shift = new Shift();
				shift.setShiftType(ShiftType.OFF);
				shift.setCapacity(0);
				shift.setActive(true);
				shift.setOvernight(false);
				return shiftRepository.save(shift);
			});

			List<User> existingAdmins = userRepository.findByRole(Role.ROLE_ADMIN);
			String adminUsername = System.getenv().getOrDefault("ADMIN_USERNAME", "admin").toLowerCase();
			String adminPassword = System.getenv().getOrDefault("ADMIN_PASSWORD", "Admin@123");
			if (existingAdmins.isEmpty()) {
				User admin = new User();
				admin.setUsername(adminUsername);
				admin.setPassword(passwordEncoder.encode(adminPassword));
				admin.setRole(Role.ROLE_ADMIN);
				admin.setEnabled(true);
				userRepository.save(admin);
			} else {
				User primaryAdmin = existingAdmins.get(0);
				if (!adminUsername.equalsIgnoreCase(primaryAdmin.getUsername())) {
					System.out.println(">>> Normalizing existing admin username from '" + primaryAdmin.getUsername() + "' to '" + adminUsername + "' <<<");
					primaryAdmin.setUsername(adminUsername);
					userRepository.save(primaryAdmin);
				}
			}

			// Clean up any stray ephemeral test employees left in local database
			employeeRepository.findAll().stream()
					.filter(e -> (e.getEmployeeCode() != null && e.getEmployeeCode().toUpperCase().startsWith("B73LOCAL"))
							|| "SQLite".equalsIgnoreCase(e.getFirstName())
							|| "Tester".equalsIgnoreCase(e.getLastName()))
					.forEach(e -> {
						if (e.getEmployeeCode() != null) {
							userRepository.findByUsernameIgnoreCase(e.getEmployeeCode()).ifPresent(userRepository::delete);
						}
						employeeRepository.delete(e);
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
				if (emp.getEmail() != null && emp.getEmail().contains("rajat.b13.")) {
					emp.setEmail("rkmaurya080217@gmail.com");
				}
				if (emp.getLastName() == null) {
					emp.setLastName("");
				}
				employeeRepository.save(emp);
				System.out.println("  -> Synchronized user credentials for: " + uname);
			}
		};
	}

	private void setShiftTimingDefaults(Shift shift, ShiftType type) {
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
	}

	private void fixSqliteAuditActionCheckConstraint(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
		try {
			String driverName = jdbcTemplate.execute((java.sql.Connection conn) -> conn.getMetaData().getDriverName());
			if (driverName == null || !driverName.toLowerCase().contains("sqlite")) {
				return;
			}
			List<String> ddlList = jdbcTemplate.query(
					"SELECT sql FROM sqlite_master WHERE type='table' AND name='system_audit_logs'",
					(rs, rowNum) -> rs.getString("sql"));
			if (ddlList.isEmpty()) {
				return;
			}
			String ddl = ddlList.get(0);
			if (ddl == null) {
				return;
			}
			if (ddl.contains("audit_action") && ddl.toLowerCase().contains("check") && !ddl.contains("ADMIN_RECOVERY_REQUESTED")) {
				System.out.println(">>> Migrating SQLite system_audit_logs to remove restrictive CHECK constraint <<<");
				List<String> indexSqls = jdbcTemplate.query(
						"SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name='system_audit_logs' AND sql IS NOT NULL",
						(rs, rowNum) -> rs.getString("sql"));

				jdbcTemplate.execute("PRAGMA foreign_keys = OFF;");
				jdbcTemplate.execute("DROP TABLE IF EXISTS system_audit_logs_backup;");
				jdbcTemplate.execute("CREATE TABLE system_audit_logs_backup AS SELECT * FROM system_audit_logs;");
				jdbcTemplate.execute("DROP TABLE system_audit_logs;");

				String cleanDdl = ddl.replaceAll("(?is)\\s*check\\s*\\(\\s*audit_action\\s+in\\s*\\([^)]*\\)\\s*\\)", "");
				jdbcTemplate.execute(cleanDdl);
				jdbcTemplate.execute("INSERT INTO system_audit_logs SELECT * FROM system_audit_logs_backup;");
				jdbcTemplate.execute("DROP TABLE system_audit_logs_backup;");

				for (String idxSql : indexSqls) {
					try {
						jdbcTemplate.execute(idxSql);
					} catch (Exception ignored) {
					}
				}
				jdbcTemplate.execute("PRAGMA foreign_keys = ON;");
				System.out.println(">>> SQLite system_audit_logs constraint migration completed successfully! <<<");
			}
		} catch (Exception e) {
			System.err.println("Notice: SQLite check constraint migration skipped or encountered: " + e.getMessage());
		}
	}

	private void migrateWorkingDaysColumns(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
		try {
			jdbcTemplate.execute("ALTER TABLE roster_cycles ADD COLUMN working_days INT DEFAULT 6");
			System.out.println(">>> Added working_days column to roster_cycles <<<");
		} catch (Exception ignored) {
			// Column already exists or table not yet created
		}
		try {
			jdbcTemplate.execute("ALTER TABLE roster_generation_history ADD COLUMN working_days INT DEFAULT 6");
			System.out.println(">>> Added working_days column to roster_generation_history <<<");
		} catch (Exception ignored) {
			// Column already exists or table not yet created
		}
	}
}
