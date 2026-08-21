package com.weeklyroster.service;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.weeklyroster.dto.request.ChangePasswordRequest;
import com.weeklyroster.dto.request.LoginRequest;
import com.weeklyroster.dto.response.AuthResponse;
import com.weeklyroster.dto.response.UserProfileResponse;
import com.weeklyroster.entity.ActivityCategory;
import com.weeklyroster.entity.ActivityStatus;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.User;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.UserRepository;
import com.weeklyroster.security.JwtService;
import com.weeklyroster.security.UserPrincipal;

import java.util.Map;

@Service
public class AuthService {
	private final AuthenticationManager authenticationManager;
	private final JwtService jwtService;
	private final UserRepository userRepository;
	private final EmployeeRepository employeeRepository;
	private final PasswordEncoder passwordEncoder;
	private final EmployeeActivityLogService activityLogService;
	private final DevCredentialMirrorService devCredentialMirrorService;

	public AuthService(AuthenticationManager authenticationManager,
					   JwtService jwtService,
					   UserRepository userRepository,
					   EmployeeRepository employeeRepository,
					   PasswordEncoder passwordEncoder,
					   EmployeeActivityLogService activityLogService) {
		this(authenticationManager, jwtService, userRepository, employeeRepository, passwordEncoder, activityLogService, null);
	}

	@org.springframework.beans.factory.annotation.Autowired
	public AuthService(AuthenticationManager authenticationManager,
					   JwtService jwtService,
					   UserRepository userRepository,
					   EmployeeRepository employeeRepository,
					   PasswordEncoder passwordEncoder,
					   EmployeeActivityLogService activityLogService,
					   @org.springframework.beans.factory.annotation.Autowired(required = false) DevCredentialMirrorService devCredentialMirrorService) {
		this.authenticationManager = authenticationManager;
		this.jwtService = jwtService;
		this.userRepository = userRepository;
		this.employeeRepository = employeeRepository;
		this.passwordEncoder = passwordEncoder;
		this.activityLogService = activityLogService;
		this.devCredentialMirrorService = devCredentialMirrorService;
	}

	public AuthResponse login(LoginRequest request) {
		try {
			var authentication = authenticationManager
					.authenticate(new UsernamePasswordAuthenticationToken(request.username(), request.password()));
			UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();

			if (activityLogService != null) {
				activityLogService.logUserActivity(
						principal.getUsername(),
						ActivityCategory.ACCOUNT,
						"LOGIN",
						ActivityStatus.SUCCESS,
						"User signed in successfully"
				);
			}

			return new AuthResponse(jwtService.generateToken(principal), "Bearer", profile(principal.user()));
		} catch (AuthenticationException ex) {
			if (activityLogService != null && request.username() != null) {
				activityLogService.logUserActivity(
						request.username(),
						ActivityCategory.SECURITY,
						"FAILED_LOGIN",
						ActivityStatus.FAILED,
						"Login attempt failed: invalid credentials"
				);
			}
			throw ex;
		}
	}

	@Transactional
	public Map<String, Object> changePassword(ChangePasswordRequest request) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()) {
			throw new org.springframework.security.access.AccessDeniedException("Authentication required");
		}

		String username = authentication.getName();
		User user = userRepository.findByUsername(username)
				.orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));

		if (!request.newPassword().equals(request.confirmPassword())) {
			if (activityLogService != null) {
				activityLogService.logUserActivity(
						username,
						ActivityCategory.SECURITY,
						"PASSWORD_CHANGED",
						ActivityStatus.FAILED,
						"Password change failed: new password and confirmation do not match"
				);
			}
			throw new BusinessException("New password and confirmation do not match");
		}

		if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
			if (activityLogService != null) {
				activityLogService.logUserActivity(
						username,
						ActivityCategory.SECURITY,
						"PASSWORD_CHANGED",
						ActivityStatus.FAILED,
						"Password change failed: current password incorrect"
				);
			}
			throw new BusinessException("Current password is incorrect");
		}

		if (passwordEncoder.matches(request.newPassword(), user.getPassword())) {
			if (activityLogService != null) {
				activityLogService.logUserActivity(
						username,
						ActivityCategory.SECURITY,
						"PASSWORD_CHANGED",
						ActivityStatus.FAILED,
						"Password change failed: new password cannot be the same as current password"
				);
			}
			throw new BusinessException("New password cannot be the same as current password");
		}

		user.setPassword(passwordEncoder.encode(request.newPassword()));
		userRepository.save(user);

		if (devCredentialMirrorService != null) {
			devCredentialMirrorService.updatePassword(username, request.newPassword());
		}

		if (activityLogService != null) {
			activityLogService.logUserActivity(
					username,
					ActivityCategory.SECURITY,
					"PASSWORD_CHANGED",
					ActivityStatus.SUCCESS,
					"Your account password was changed successfully."
			);
		}

		return Map.of("success", true, "message", "Password changed successfully");
	}

	@Transactional(readOnly = true)
	public UserProfileResponse currentProfile() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null) {
			return null;
		}
		String username = authentication.getName();
		User user = userRepository.findByUsername(username).orElse(null);
		if (user == null) {
			return null;
		}
		return profile(user);
	}

	private UserProfileResponse profile(User user) {
		Employee employee = employeeRepository.findByUserUsername(user.getUsername()).orElse(null);
		String employeeName = employee == null ? null : employee.getFirstName() + " " + employee.getLastName();
		return new UserProfileResponse(user.getId(), user.getUsername(), user.getRole(),
				employee == null ? null : employee.getId(), employeeName);
	}
}
