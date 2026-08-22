package com.weeklyroster.repository;

import com.weeklyroster.entity.Employee;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {
	List<Employee> findByActiveTrueOrderByIdAsc();

	List<Employee> findAllByOrderByIdAsc();

	Optional<Employee> findByUserUsername(String username);

	Optional<Employee> findByUserUsernameIgnoreCase(String username);

	Optional<Employee> findByEmployeeCodeIgnoreCase(String employeeCode);

	Optional<Employee> findByEmployeeCode(String employeeCode);

	boolean existsByEmployeeCode(String employeeCode);

	boolean existsByEmail(String email);

	long countByActiveTrue();

	long countByActiveFalse();
}
