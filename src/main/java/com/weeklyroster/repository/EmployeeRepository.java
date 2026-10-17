package com.weeklyroster.repository;

import com.weeklyroster.entity.Employee;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {
	@org.springframework.data.jpa.repository.Query("SELECT e FROM Employee e LEFT JOIN FETCH e.user WHERE e.active = true ORDER BY e.id ASC")
	List<Employee> findByActiveTrueOrderByIdAsc();

	@org.springframework.data.jpa.repository.Query("SELECT e FROM Employee e LEFT JOIN FETCH e.user ORDER BY e.id ASC")
	List<Employee> findAllByOrderByIdAsc();

	Optional<Employee> findByUserUsername(String username);

	Optional<Employee> findByUserUsernameIgnoreCase(String username);

	Optional<Employee> findByEmployeeCodeIgnoreCase(String employeeCode);

	Optional<Employee> findByEmployeeCode(String employeeCode);

	boolean existsByEmployeeCode(String employeeCode);

	boolean existsByEmail(String email);

	Optional<Employee> findByEmailIgnoreCase(String email);

	Optional<Employee> findByUserId(Long userId);

	long countByActiveTrue();

	long countByActiveFalse();

	@org.springframework.data.jpa.repository.Query("SELECT e.employeeCode FROM Employee e")
	List<String> findAllEmployeeCodes();
}
