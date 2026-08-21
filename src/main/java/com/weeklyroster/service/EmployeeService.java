package com.weeklyroster.service;

import com.weeklyroster.dto.request.EmployeeRequest;
import com.weeklyroster.dto.response.EmployeeResponse;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.Role;
import com.weeklyroster.entity.User;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.UserRepository;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmployeeService {
	private final EmployeeRepository employeeRepository;
	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final DevCredentialMirrorService devCredentialMirrorService;

	public EmployeeService(EmployeeRepository employeeRepository, UserRepository userRepository,
			PasswordEncoder passwordEncoder) {
		this(employeeRepository, userRepository, passwordEncoder, null);
	}

	@org.springframework.beans.factory.annotation.Autowired
	public EmployeeService(EmployeeRepository employeeRepository, UserRepository userRepository,
			PasswordEncoder passwordEncoder,
			@org.springframework.beans.factory.annotation.Autowired(required = false) DevCredentialMirrorService devCredentialMirrorService) {
		this.employeeRepository = employeeRepository;
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.devCredentialMirrorService = devCredentialMirrorService;
	}

	@Transactional(readOnly = true)
	public List<EmployeeResponse> all() {
		return employeeRepository.findAllByOrderByIdAsc().stream().map(this::toResponse).toList();
	}

	@Transactional(readOnly = true)
	public List<EmployeeResponse> active() {
		return employeeRepository.findByActiveTrueOrderByIdAsc().stream().map(this::toResponse).toList();
	}

	@Transactional
	public EmployeeResponse create(EmployeeRequest request) {
		if (employeeRepository.existsByEmployeeCode(request.employeeCode())) {
			throw new BusinessException("Employee code already exists");
		}
		if (employeeRepository.existsByEmail(request.email())) {
			throw new BusinessException("Employee email already exists");
		}

		User user = null;
		if (request.username() != null && !request.username().isBlank()) {
			if (userRepository.existsByUsername(request.username())) {
				throw new BusinessException("Username already exists");
			}
			user = new User();
			user.setUsername(request.username());
			user.setPassword(passwordEncoder.encode(request.password() == null ? "password123" : request.password()));
			user.setRole(Role.ROLE_EMPLOYEE);
			user = userRepository.save(user);
		}

		Employee employee = new Employee();
		apply(employee, request);
		employee.setUser(user);
		Employee saved = employeeRepository.save(employee);
		if (devCredentialMirrorService != null) {
			devCredentialMirrorService.updateProfile(saved, request.password() == null ? "password123" : request.password());
		}
		return toResponse(saved);
	}

	@Transactional
	public EmployeeResponse update(Long id, EmployeeRequest request) {
		Employee employee = employeeRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Employee not found"));
		if (!employee.getEmployeeCode().equals(request.employeeCode())
				&& employeeRepository.existsByEmployeeCode(request.employeeCode())) {
			throw new BusinessException("Employee code already exists");
		}
		if (!employee.getEmail().equals(request.email()) && employeeRepository.existsByEmail(request.email())) {
			throw new BusinessException("Employee email already exists");
		}
		apply(employee, request);
		Employee saved = employeeRepository.save(employee);
		if (devCredentialMirrorService != null) {
			devCredentialMirrorService.updateProfile(saved, request.password());
		}
		return toResponse(saved);
	}

	@Transactional(readOnly = true)
	public EmployeeResponse getMyProfile() {
		org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !auth.isAuthenticated()) {
			throw new org.springframework.security.access.AccessDeniedException("Authentication required to access profile");
		}
		String username = auth.getName();
		Employee employee = employeeRepository.findByUserUsername(username)
				.orElseThrow(() -> new ResourceNotFoundException("Employee profile not found for user: " + username));
		return toResponse(employee);
	}

	@Transactional
	public EmployeeResponse updateMyProfile(com.weeklyroster.dto.request.UpdateMyProfileRequest request) {
		org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !auth.isAuthenticated()) {
			throw new org.springframework.security.access.AccessDeniedException("Authentication required to update profile");
		}
		String username = auth.getName();
		Employee employee = employeeRepository.findByUserUsername(username)
				.orElseThrow(() -> new ResourceNotFoundException("Employee profile not found for user: " + username));

		if (request.email() != null && !request.email().trim().equalsIgnoreCase(employee.getEmail())) {
			String newEmail = request.email().trim().toLowerCase();
			if (employeeRepository.existsByEmail(newEmail)) {
				throw new BusinessException("Email address already in use by another employee");
			}
			employee.setEmail(newEmail);
		}

		if (request.firstName() != null && !request.firstName().trim().isEmpty()) {
			employee.setFirstName(request.firstName().trim());
		}
		if (request.lastName() != null) {
			employee.setLastName(request.lastName().trim());
		}
		if (request.contactNumber() != null) {
			employee.setContactNumber(request.contactNumber().trim());
		}

		Employee saved = employeeRepository.save(employee);
		if (devCredentialMirrorService != null) {
			devCredentialMirrorService.updateProfile(saved, null);
		}
		return toResponse(saved);
	}

	@Transactional(readOnly = true)
	public EmployeeResponse getById(Long id) {
		return employeeRepository.findById(id)
				.map(this::toResponse)
				.orElseThrow(() -> new ResourceNotFoundException("Employee not found"));
	}

	@Transactional
	public void delete(Long id) {
		Employee emp = employeeRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Employee not found"));
		emp.setActive(false);
		if (emp.getUser() != null) {
			emp.getUser().setEnabled(false);
		}
	}

	@Transactional
	public void toggleStatus(Long id) {
		Employee emp = employeeRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Employee not found"));

		emp.setActive(!emp.isActive());

		if (emp.getUser() != null) {
			emp.getUser().setEnabled(emp.isActive());
		}
	}

	private void apply(Employee employee, EmployeeRequest request) {
		employee.setEmployeeCode(request.employeeCode());
		employee.setFirstName(request.firstName());
		employee.setLastName(request.lastName() == null ? "" : request.lastName().trim());
		employee.setEmail(request.email());
		employee.setGender(request.gender());
		if (request.contactNumber() != null) {
			employee.setContactNumber(request.contactNumber().trim());
		}
	}

	public EmployeeResponse toResponse(Employee employee) {
		return new EmployeeResponse(employee.getId(), employee.getEmployeeCode(), employee.getFirstName(),
				employee.getLastName(), employee.getEmail(), employee.getGender(), employee.isActive(),
				employee.getUser() == null ? null : employee.getUser().getUsername(),
				employee.getContactNumber());
	}
}
