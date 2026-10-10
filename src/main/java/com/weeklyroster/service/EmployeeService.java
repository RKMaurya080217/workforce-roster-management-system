package com.weeklyroster.service;

import com.weeklyroster.dto.request.EmployeeRequest;
import com.weeklyroster.dto.response.EmployeeResponse;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.Role;
import com.weeklyroster.entity.User;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.EmployeePreferenceRepository;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.UserRepository;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmployeeService {
	private final EmployeeRepository employeeRepository;
	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final DevCredentialMirrorService devCredentialMirrorService;
	private final EmployeePreferenceRepository preferenceRepository;

	public EmployeeService(EmployeeRepository employeeRepository, UserRepository userRepository,
			PasswordEncoder passwordEncoder) {
		this(employeeRepository, userRepository, passwordEncoder, null, null);
	}

	@org.springframework.beans.factory.annotation.Autowired
	public EmployeeService(EmployeeRepository employeeRepository, UserRepository userRepository,
			PasswordEncoder passwordEncoder,
			@org.springframework.beans.factory.annotation.Autowired(required = false) DevCredentialMirrorService devCredentialMirrorService,
			@org.springframework.beans.factory.annotation.Autowired(required = false) EmployeePreferenceRepository preferenceRepository) {
		this.employeeRepository = employeeRepository;
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.devCredentialMirrorService = devCredentialMirrorService;
		this.preferenceRepository = preferenceRepository;
	}

	private Map<Long, String> loadShiftPreferenceMap() {
		if (preferenceRepository == null) return Collections.emptyMap();
		List<com.weeklyroster.entity.EmployeePreference> all = preferenceRepository.findAllByOrderByCreatedAtDesc();
		Map<Long, String> map = new HashMap<>();
		for (com.weeklyroster.entity.EmployeePreference p : all) {
			if (p.getEmployee() != null && p.getStatus() == com.weeklyroster.entity.PreferenceStatus.APPROVED) {
				if (!map.containsKey(p.getEmployee().getId())) {
					String s = p.getPreferredShiftTypes();
					if (s != null && !s.isBlank()) {
						map.put(p.getEmployee().getId(), s);
					}
				}
			}
		}
		return map;
	}

	private String loadShiftPreference(Long employeeId) {
		if (preferenceRepository == null || employeeId == null) return null;
		return preferenceRepository.findTopByEmployeeIdAndStatusOrderByCreatedAtDesc(employeeId, com.weeklyroster.entity.PreferenceStatus.APPROVED)
				.map(com.weeklyroster.entity.EmployeePreference::getPreferredShiftTypes)
				.filter(s -> s != null && !s.isBlank())
				.orElse(null);
	}

	@Transactional(readOnly = true)
	public List<EmployeeResponse> all() {
		Map<Long, String> prefMap = loadShiftPreferenceMap();
		return employeeRepository.findAllByOrderByIdAsc().stream()
				.map(e -> toResponse(e, prefMap.get(e.getId())))
				.toList();
	}

	@Transactional(readOnly = true)
	public List<EmployeeResponse> active() {
		Map<Long, String> prefMap = loadShiftPreferenceMap();
		return employeeRepository.findByActiveTrueOrderByIdAsc().stream()
				.map(e -> toResponse(e, prefMap.get(e.getId())))
				.toList();
	}

	@Transactional(readOnly = true)
	public synchronized String generateNextEmployeeCode() {
		List<String> codes = employeeRepository.findAllEmployeeCodes();
		int maxNum = 0;
		int digitCount = 3;
		String prefix = "EMP";

		java.util.regex.Pattern empPattern = java.util.regex.Pattern.compile("^EMP(\\d+)$", java.util.regex.Pattern.CASE_INSENSITIVE);
		boolean foundEmp = false;

		for (String code : codes) {
			if (code == null) continue;
			java.util.regex.Matcher m = empPattern.matcher(code.trim());
			if (m.matches()) {
				String numStr = m.group(1);
				try {
					int num = Integer.parseInt(numStr);
					if (num > maxNum) {
						maxNum = num;
						digitCount = Math.max(digitCount, numStr.length());
					}
					foundEmp = true;
				} catch (NumberFormatException ignored) {}
			}
		}

		if (!foundEmp) {
			java.util.regex.Pattern generalPattern = java.util.regex.Pattern.compile("^([A-Za-z]+)(\\d+)$");
			for (String code : codes) {
				if (code == null) continue;
				java.util.regex.Matcher m = generalPattern.matcher(code.trim().toUpperCase());
				if (m.matches()) {
					String p = m.group(1);
					String numStr = m.group(2);
					try {
						int num = Integer.parseInt(numStr);
						if (num > maxNum) {
							maxNum = num;
							prefix = p;
							digitCount = Math.max(digitCount, numStr.length());
						}
					} catch (NumberFormatException ignored) {}
				}
			}
		}

		int nextNum = maxNum + 1;
		int targetDigits = Math.max(digitCount, String.valueOf(nextNum).length());
		String nextCode = String.format("%s%0" + targetDigits + "d", prefix, nextNum);

		while (employeeRepository.existsByEmployeeCode(nextCode)) {
			nextNum++;
			targetDigits = Math.max(digitCount, String.valueOf(nextNum).length());
			nextCode = String.format("%s%0" + targetDigits + "d", prefix, nextNum);
		}
		return nextCode;
	}

	@Transactional
	public synchronized EmployeeResponse create(EmployeeRequest request) {
		String code = request.employeeCode();
		if (code == null || code.isBlank()) {
			code = generateNextEmployeeCode();
		} else {
			code = code.trim().toUpperCase();
		}

		if (employeeRepository.existsByEmployeeCode(code)) {
			// Handle race condition: if frontend-cached ID was just claimed, generate authoritative next ID
			if (code.matches("^EMP\\d+$")) {
				code = generateNextEmployeeCode();
			}
			if (employeeRepository.existsByEmployeeCode(code)) {
				throw new BusinessException("Employee code already exists: " + code);
			}
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
		employee.setEmployeeCode(code);
		employee.setUser(user);

		Employee saved;
		try {
			saved = employeeRepository.save(employee);
		} catch (org.springframework.dao.DataIntegrityViolationException dive) {
			if (dive.getMessage() != null && dive.getMessage().toLowerCase().contains("employee_code")) {
				throw new BusinessException("Duplicate Employee ID detected. Please retry with a freshly generated ID.");
			}
			throw dive;
		}

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
		return toResponse(employee, loadShiftPreference(employee.getId()));
	}

	public EmployeeResponse toResponse(Employee employee, String shiftPreference) {
		return new EmployeeResponse(employee.getId(), employee.getEmployeeCode(), employee.getFirstName(),
				employee.getLastName(), employee.getEmail(), employee.getGender(), employee.isActive(),
				employee.getUser() == null ? null : employee.getUser().getUsername(),
				employee.getContactNumber(), shiftPreference);
	}
}
