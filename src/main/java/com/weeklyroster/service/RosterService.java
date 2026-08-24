package com.weeklyroster.service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.weeklyroster.dto.request.RosterOverrideRequest;
import com.weeklyroster.dto.request.ShiftChangeRequest;
import com.weeklyroster.dto.request.UnlockRosterRequest;
import com.weeklyroster.dto.response.CoverageReportResponse;
import com.weeklyroster.dto.response.DailyCoverageReport;
import com.weeklyroster.dto.response.DutySummaryDto;
import com.weeklyroster.dto.response.RosterAssignmentResponse;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.dto.response.RosterHealthReport;
import com.weeklyroster.dto.response.ShiftCoverageSummary;
import com.weeklyroster.dto.response.TodayDutyResponse;
import com.weeklyroster.entity.AuditAction;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.Gender;
import com.weeklyroster.entity.LeaveStatus;
import com.weeklyroster.entity.NotificationType;
import com.weeklyroster.entity.RosterAssignment;
import com.weeklyroster.entity.RosterCycle;
import com.weeklyroster.entity.RosterOverride;
import com.weeklyroster.entity.RosterStatus;
import com.weeklyroster.entity.Shift;
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.LeaveRequestRepository;
import com.weeklyroster.repository.RosterAssignmentRepository;
import com.weeklyroster.repository.RosterCycleRepository;
import com.weeklyroster.repository.RosterOverrideRepository;
import com.weeklyroster.repository.ShiftRepository;

@Service
public class RosterService {
	private static final Logger log = LoggerFactory.getLogger(RosterService.class);

	public static final int MIN_REST_HOURS = 12;
	public static final int MAX_NIGHTS_PER_CYCLE = 2;

	private static final List<ShiftType> MALE_ROTATION = List.of(ShiftType.MORNING, ShiftType.GENERAL,
			ShiftType.EVENING, ShiftType.NIGHT);
	private static final List<ShiftType> FEMALE_ROTATION = List.of(ShiftType.MORNING, ShiftType.GENERAL);
	private static final List<ShiftType> ASSIGNMENT_ORDER = List.of(ShiftType.NIGHT, ShiftType.EVENING,
			ShiftType.MORNING, ShiftType.GENERAL);

	private final EmployeeRepository employeeRepository;
	private final ShiftRepository shiftRepository;
	private final RosterCycleRepository cycleRepository;
	private final RosterAssignmentRepository assignmentRepository;
	private final RosterOverrideRepository overrideRepository;
	private final LeaveRequestRepository leaveRepository;
	private final com.weeklyroster.repository.EmailDeliveryLogRepository emailDeliveryLogRepository;
	private final AuditService auditService;
	private final NotificationService notificationService;
	private final RosterHealthService rosterHealthService;

	@org.springframework.beans.factory.annotation.Autowired(required = false)
	private RosterVersionService rosterVersionService;

	@org.springframework.beans.factory.annotation.Autowired
	public RosterService(EmployeeRepository employeeRepository, ShiftRepository shiftRepository,
			RosterCycleRepository cycleRepository, RosterAssignmentRepository assignmentRepository,
			RosterOverrideRepository overrideRepository, LeaveRequestRepository leaveRepository,
			com.weeklyroster.repository.EmailDeliveryLogRepository emailDeliveryLogRepository,
			AuditService auditService, NotificationService notificationService,
			RosterHealthService rosterHealthService) {
		this.employeeRepository = employeeRepository;
		this.shiftRepository = shiftRepository;
		this.cycleRepository = cycleRepository;
		this.assignmentRepository = assignmentRepository;
		this.overrideRepository = overrideRepository;
		this.leaveRepository = leaveRepository;
		this.emailDeliveryLogRepository = emailDeliveryLogRepository;
		this.auditService = auditService;
		this.notificationService = notificationService;
		this.rosterHealthService = rosterHealthService;
	}

	public RosterService(EmployeeRepository employeeRepository, ShiftRepository shiftRepository,
			RosterCycleRepository cycleRepository, RosterAssignmentRepository assignmentRepository,
			RosterOverrideRepository overrideRepository, LeaveRequestRepository leaveRepository,
			com.weeklyroster.repository.EmailDeliveryLogRepository emailDeliveryLogRepository) {
		this(employeeRepository, shiftRepository, cycleRepository, assignmentRepository, overrideRepository,
				leaveRepository, emailDeliveryLogRepository, null, null, null);
	}

	@Transactional
	public RosterCycleResponse generateWeeklyRoster() {
		return generateWeeklyRoster(LocalDate.now().plusDays(1), com.weeklyroster.entity.GenerationMode.MANUAL);
	}

	@Transactional
	public RosterCycleResponse generateWeeklyRoster(LocalDate startDate) {
		return generateWeeklyRoster(startDate, com.weeklyroster.entity.GenerationMode.MANUAL);
	}

	@Transactional
	public RosterCycleResponse generateWeeklyRoster(LocalDate startDate, com.weeklyroster.entity.GenerationMode mode) {
		if (startDate == null) {
			startDate = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
					.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY));
		}
		if (startDate.getDayOfWeek() != java.time.DayOfWeek.MONDAY) {
			throw new BusinessException("Roster cycle start date must be a Monday (received: " + startDate.getDayOfWeek() + " " + startDate + ")");
		}
		LocalDate endDate = startDate.plusDays(6);
		List<Employee> employees = employeeRepository.findByActiveTrueOrderByIdAsc();
		if (employees.isEmpty()) {
			throw new BusinessException("No active employees available to generate roster");
		}

		Map<ShiftType, Shift> shifts = activeShiftMap();

		int minActiveWorkingShifts = (int) shifts.entrySet().stream()
				.filter(entry -> entry.getKey() != ShiftType.OFF && entry.getValue().isActive())
				.count();
		if (employees.size() < minActiveWorkingShifts) {
			throw new BusinessException("At least " + minActiveWorkingShifts
					+ " active employees are required to cover active working shifts");
		}

		long maleCount = employees.stream().filter(employee -> employee.getGender() == Gender.MALE).count();
		int minMaleRequired = 0;
		if (shifts.get(ShiftType.EVENING).isActive()) minMaleRequired++;
		if (shifts.get(ShiftType.NIGHT).isActive()) minMaleRequired++;
		if (maleCount < minMaleRequired) {
			throw new BusinessException("At least " + minMaleRequired
					+ " active male employees are required for evening and night coverage");
		}

		int maxNightsAllowed = Math.max(MAX_NIGHTS_PER_CYCLE, (int) Math.ceil(7.0 / Math.max(1, maleCount)));

		// Clean up any existing overlapping cycles, overrides, assignments, and email logs for [startDate, endDate]
		List<RosterCycle> overlapping = new ArrayList<>(cycleRepository.findOverlappingCycles(startDate, endDate));
		if (overlapping.isEmpty()) {
			cycleRepository.findByStartDateAndEndDate(startDate, endDate).ifPresent(overlapping::add);
		}
		for (RosterCycle existing : overlapping) {
			if (existing.getStatus() == RosterStatus.LOCKED) {
				throw new BusinessException("Roster cycle for " + existing.getStartDate() + " to "
						+ existing.getEndDate() + " is locked and cannot be regenerated. Changes require an authorized unlock action.");
			}
		}

		for (RosterCycle existing : overlapping) {
			overrideRepository.deleteByCycleIdNative(existing.getId());
		}
		overrideRepository.deleteByDateRangeNative(startDate, endDate);

		for (RosterCycle existing : overlapping) {
			assignmentRepository.deleteByCycleIdNative(existing.getId());
		}
		assignmentRepository.deleteByDateRangeNative(startDate, endDate);

		if (emailDeliveryLogRepository != null) {
			for (RosterCycle existing : overlapping) {
				emailDeliveryLogRepository.deleteByCycleIdNative(existing.getId());
			}
			emailDeliveryLogRepository.deleteByDateRangeNative(startDate, endDate);
		}

		if (rosterVersionService != null) {
			for (RosterCycle existing : overlapping) {
				rosterVersionService.deleteByCycleId(existing.getId());
			}
		}

		for (RosterCycle existing : overlapping) {
			cycleRepository.deleteCycleByIdNative(existing.getId());
		}

		RosterCycle cycle = new RosterCycle();
		cycle.setStartDate(startDate);
		cycle.setEndDate(endDate);
		cycle.setGeneratedAt(LocalDateTime.now());
		cycle.setGenerationMode(mode != null ? mode : com.weeklyroster.entity.GenerationMode.MANUAL);
		cycle.setStatus(RosterStatus.GENERATED);
		cycle = cycleRepository.save(cycle);

		if (auditService != null) {
			auditService.log(
					mode == com.weeklyroster.entity.GenerationMode.AUTOMATIC ? AuditAction.AUTOMATIC_GENERATION : AuditAction.ROSTER_GENERATED,
					"ROSTER_CYCLE", cycle.getId(), cycle.getId(), null, null,
					null, "GENERATED", "Generated weekly roster for " + startDate + " to " + endDate,
					mode == com.weeklyroster.entity.GenerationMode.AUTOMATIC ? "AUTOMATIC" : "MANUAL"
			);
		}

		// Plan single non-working day (OFF or LEAVE) per employee
		Map<Long, LocalDate> weeklyOffs = planWeeklyOffs(employees, startDate, endDate);
		Set<Long> offTaken = new HashSet<>();
		
		// In-flight state tracking for continuity, rest rules, and night limits
		Map<Long, Shift> lastShiftMap = new HashMap<>();
		Map<Long, LocalDate> lastShiftDateMap = new HashMap<>();
		Map<Long, Integer> cycleNightCounts = new HashMap<>();
		Map<Long, Map<ShiftType, Integer>> shiftCountsMap = new HashMap<>();

		for (Employee emp : employees) {
			cycleNightCounts.put(emp.getId(), 0);
			
			// Load boundary state from previous cycles
			List<RosterAssignment> prevWorked = assignmentRepository.findWorkedAssignmentsBefore(emp.getId(), startDate);
			if (!prevWorked.isEmpty()) {
				RosterAssignment lastA = prevWorked.get(0);
				lastShiftMap.put(emp.getId(), lastA.getShift());
				lastShiftDateMap.put(emp.getId(), lastA.getRosterDate());
			}

			Map<ShiftType, Integer> counts = new EnumMap<>(ShiftType.class);
			for (ShiftType type : ShiftType.values()) {
				counts.put(type, (int) assignmentRepository.countShiftForEmployee(emp.getId(), type));
			}
			shiftCountsMap.put(emp.getId(), counts);
		}

		List<RosterAssignment> generated = new ArrayList<>();
		List<DailyCoverageReport> dailyReports = new ArrayList<>();
		int totalConfiguredDemand = 0;
		int totalWorkforceCapacity = 0;
		int totalFeasibleCapacity = 0;
		int totalAssigned = 0;
		int totalOperationalShortage = 0;
		List<String> warnings = new ArrayList<>();

		for (int offset = 0; offset < 7; offset++) {
			LocalDate date = startDate.plusDays(offset);
			DayGenerationResult dayResult = generateDay(cycle, employees, weeklyOffs, offTaken, shifts, date,
					lastShiftMap, lastShiftDateMap, cycleNightCounts, shiftCountsMap, maxNightsAllowed);
			
			generated.addAll(assignmentRepository.saveAll(dayResult.assignments()));
			dailyReports.add(dayResult.coverageReport());

			totalConfiguredDemand += dayResult.coverageReport().dailyConfiguredDemand();
			totalWorkforceCapacity += dayResult.coverageReport().plannedWorking();
			totalFeasibleCapacity += dayResult.coverageReport().dailyFeasibleCapacity();
			totalAssigned += dayResult.coverageReport().dailyAssigned();
			totalOperationalShortage += dayResult.coverageReport().dailyOperationalShortage();

			for (ShiftCoverageSummary summary : dayResult.coverageReport().shiftSummaries()) {
				if (summary.operationalShortage() > 0) {
					warnings.add("Operational shortage on " + date + " for " + summary.shiftType() + ": feasible "
							+ summary.feasibleCapacity() + ", assigned " + summary.assignedCount() + " (" + summary.reason() + ")");
				}
			}
		}

		int totalConfiguredShortage = Math.max(0, totalConfiguredDemand - totalAssigned);
		if (totalConfiguredShortage > 0 && totalOperationalShortage == 0) {
			warnings.add("Configured demand (" + totalConfiguredDemand + ") exceeds active workforce capacity ("
					+ totalWorkforceCapacity + "). All required shift types are fully covered.");
		}

		// Enforce and repair exact weekly off rule (exactly 1 weekly OFF per active employee)
		enforceAndRepairExactWeeklyOff(cycle, generated, employees, shifts, maxNightsAllowed, weeklyOffs);
		assignmentRepository.saveAll(generated);

		// Comprehensive Post-Generation Validation
		validateGeneratedRoster(cycle, generated, shifts, maxNightsAllowed);

		if (rosterVersionService != null) {
			try {
				rosterVersionService.recordVersionSnapshot(cycle, mode == com.weeklyroster.entity.GenerationMode.AUTOMATIC ? "AUTOMATIC_GENERATION" : "GENERATED", "Roster cycle generated", "system");
			} catch (Exception ignored) {}
		}

		CoverageReportResponse coverageReport = new CoverageReportResponse(totalConfiguredDemand,
				totalWorkforceCapacity, totalFeasibleCapacity, totalAssigned, totalOperationalShortage,
				totalConfiguredShortage, dailyReports, warnings);

		return toCycleResponse(cycle, generated, coverageReport);
	}

	@Transactional(readOnly = true)
	public List<RosterCycleResponse> allCycles() {
		return cycleRepository.findAllByOrderByStartDateDesc().stream().map(cycle -> {
			List<RosterAssignment> assignments = assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(cycle);
			CoverageReportResponse coverageReport = calculateCoverageReport(cycle, assignments);
			return toCycleResponse(cycle, assignments, coverageReport);
		}).toList();
	}

	@Transactional(readOnly = true)
	public RosterCycleResponse cycle(Long id) {
		RosterCycle cycle = cycleRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Roster cycle not found with id: " + id));
		List<RosterAssignment> assignments = assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(cycle);
		CoverageReportResponse coverageReport = calculateCoverageReport(cycle, assignments);
		return toCycleResponse(cycle, assignments, coverageReport);
	}

	@Transactional
	public void deleteCycle(Long id) {
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || authentication.getAuthorities().stream()
				.noneMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"))) {
			throw new AccessDeniedException("Only administrators can delete roster cycles");
		}

		RosterCycle cycle = cycleRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Roster cycle not found with id: " + id));

		if (cycle.getStatus() == RosterStatus.LOCKED) {
			throw new BusinessException("Roster cycle #" + id + " is locked and cannot be deleted. Unlock it first if deletion is required.");
		}

		log.info("Admin {} is deleting roster cycle ID {} ({} to {})",
				new Object[] { authentication.getName(), cycle.getId(), cycle.getStartDate(), cycle.getEndDate() });

		if (auditService != null) {
			auditService.log(AuditAction.ROSTER_DELETED, "ROSTER_CYCLE", cycle.getId(), cycle.getId(),
					null, null, cycle.getStatus() != null ? cycle.getStatus().name() : "UNKNOWN", "DELETED", "Admin deleted roster cycle", "MANUAL");
		}

		// Strict child-first deletion order:
		// 1. Delete overrides on assignments belonging to this cycle
		overrideRepository.deleteByCycleIdNative(cycle.getId());

		// 2. Delete assignments belonging to this cycle
		assignmentRepository.deleteByCycleIdNative(cycle.getId());

		// 3. Delete email delivery logs belonging to this cycle
		if (emailDeliveryLogRepository != null) {
			emailDeliveryLogRepository.deleteByCycleIdNative(cycle.getId());
		}

		// 4. Delete version snapshots belonging to this cycle
		if (rosterVersionService != null) {
			rosterVersionService.deleteByCycleId(cycle.getId());
		}

		// 5. Delete the parent roster cycle entity
		cycleRepository.deleteCycleByIdNative(cycle.getId());
	}

	@Transactional
	public RosterCycleResponse publishRoster(Long cycleId) {
		var auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
			throw new AccessDeniedException("Only administrators can publish rosters");
		}
		RosterCycle cycle = cycleRepository.findById(cycleId)
				.orElseThrow(() -> new ResourceNotFoundException("Roster cycle not found with id: " + cycleId));
		List<RosterAssignment> assignments = assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(cycle);
		
		if (rosterHealthService != null) {
			RosterHealthReport health = rosterHealthService.evaluateHealth(cycle, assignments);
			if (!health.readyToPublish()) {
				throw new BusinessException("Roster cannot be published until critical conflicts are resolved. Found " 
						+ health.criticalConflictsCount() + " critical conflict(s).");
			}
		}

		String actor = auth.getName();
		cycle.setStatus(RosterStatus.PUBLISHED);
		cycle.setPublishedAt(LocalDateTime.now());
		cycle.setPublishedBy(actor);
		cycle = cycleRepository.save(cycle);

		if (auditService != null) {
			auditService.log(AuditAction.ROSTER_PUBLISHED, "ROSTER_CYCLE", cycle.getId(), cycle.getId(),
					null, null, "GENERATED", "PUBLISHED", "Admin published roster cycle", "MANUAL");
		}

		if (notificationService != null) {
			notificationService.notifyAllActiveEmployees(
					"Weekly Roster Published",
					"Your weekly roster for " + cycle.getStartDate() + " to " + cycle.getEndDate() + " is now published.",
					NotificationType.ROSTER_PUBLISHED, "roster", cycle.getId());
		}

		if (rosterVersionService != null) {
			try {
				rosterVersionService.recordVersionSnapshot(cycle, "PUBLISHED", "Admin published roster cycle", actor);
			} catch (Exception ignored) {}
		}

		CoverageReportResponse coverageReport = calculateCoverageReport(cycle, assignments);
		return toCycleResponse(cycle, assignments, coverageReport);
	}

	@Transactional
	public RosterCycleResponse lockRoster(Long cycleId) {
		var auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
			throw new AccessDeniedException("Only administrators can lock rosters");
		}
		RosterCycle cycle = cycleRepository.findById(cycleId)
				.orElseThrow(() -> new ResourceNotFoundException("Roster cycle not found with id: " + cycleId));
		List<RosterAssignment> assignments = assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(cycle);

		String actor = auth.getName();
		cycle.setStatus(RosterStatus.LOCKED);
		cycle.setLockedAt(LocalDateTime.now());
		cycle.setLockedBy(actor);
		cycle = cycleRepository.save(cycle);

		if (auditService != null) {
			auditService.log(AuditAction.ROSTER_LOCKED, "ROSTER_CYCLE", cycle.getId(), cycle.getId(),
					null, null, "PUBLISHED", "LOCKED", "Admin locked roster cycle", "MANUAL");
		}

		if (notificationService != null) {
			notificationService.notifyAllActiveEmployees(
					"Weekly Roster Locked",
					"The roster for " + cycle.getStartDate() + " to " + cycle.getEndDate() + " has been locked.",
					NotificationType.ROSTER_LOCKED, "roster", cycle.getId());
		}

		if (rosterVersionService != null) {
			try {
				rosterVersionService.recordVersionSnapshot(cycle, "LOCKED", "Admin locked roster cycle", actor);
			} catch (Exception ignored) {}
		}

		CoverageReportResponse coverageReport = calculateCoverageReport(cycle, assignments);
		return toCycleResponse(cycle, assignments, coverageReport);
	}

	@Transactional
	public RosterCycleResponse unlockRoster(Long cycleId, UnlockRosterRequest request) {
		var auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
			throw new AccessDeniedException("Only administrators can unlock rosters");
		}
		if (request == null || request.reason() == null || request.reason().trim().isEmpty()) {
			throw new BusinessException("Unlock reason is mandatory");
		}
		RosterCycle cycle = cycleRepository.findById(cycleId)
				.orElseThrow(() -> new ResourceNotFoundException("Roster cycle not found with id: " + cycleId));
		List<RosterAssignment> assignments = assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(cycle);

		String actor = auth.getName();
		cycle.setStatus(RosterStatus.PUBLISHED);
		cycle.setUnlockedAt(LocalDateTime.now());
		cycle.setUnlockedBy(actor);
		cycle.setUnlockReason(request.reason().trim());
		cycle = cycleRepository.save(cycle);

		if (auditService != null) {
			auditService.log(AuditAction.ROSTER_UNLOCKED, "ROSTER_CYCLE", cycle.getId(), cycle.getId(),
					null, null, "LOCKED", "PUBLISHED", request.reason().trim(), "MANUAL");
		}

		if (rosterVersionService != null) {
			try {
				rosterVersionService.recordVersionSnapshot(cycle, "UNLOCKED", "Admin unlocked roster cycle: " + request.reason().trim(), actor);
			} catch (Exception ignored) {}
		}

		if (notificationService != null) {
			notificationService.notifyAdmins(
					"Roster Unlocked",
					"Admin " + actor + " unlocked roster #" + cycle.getId() + " (" + cycle.getStartDate() + " to " + cycle.getEndDate() + "). Reason: " + request.reason(),
					NotificationType.ROSTER_UNLOCKED, "health", cycle.getId());
		}

		CoverageReportResponse coverageReport = calculateCoverageReport(cycle, assignments);
		return toCycleResponse(cycle, assignments, coverageReport);
	}

	@Transactional(readOnly = true)
	public RosterHealthReport getRosterHealth(Long cycleId) {
		if (rosterHealthService != null) {
			return rosterHealthService.getCycleHealth(cycleId);
		}
		RosterCycle cycle = cycleRepository.findById(cycleId)
				.orElseThrow(() -> new ResourceNotFoundException("Roster cycle not found with id: " + cycleId));
		List<RosterAssignment> assignments = assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(cycle);
		return new RosterHealthService(cycleRepository, assignmentRepository, leaveRepository).evaluateHealth(cycle, assignments);
	}

	@Transactional(readOnly = true)
	public List<RosterAssignmentResponse> employeeRoster(Long employeeId) {
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		boolean admin = authentication != null && authentication.getAuthorities().stream()
				.anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
		if (!admin) {
			if (authentication == null) {
				throw new AccessDeniedException("Authentication required");
			}
			Employee employee = employeeRepository.findByUserUsername(authentication.getName())
					.orElseThrow(() -> new ResourceNotFoundException("Employee profile not found"));
			if (!employee.getId().equals(employeeId)) {
				throw new AccessDeniedException("Access denied: You can only view your own roster");
			}
		}
		return assignmentRepository.findByEmployeeIdOrderByRosterDateAsc(employeeId).stream()
				.map(this::toAssignmentResponse).toList();
	}

	@Transactional
	public RosterAssignmentResponse changeShift(Long id, ShiftChangeRequest request) {
		return applyOverride(id, request.shiftType(), false, request.reason());
	}

	@Transactional
	public RosterAssignmentResponse markWeeklyOff(Long id, ShiftChangeRequest request) {
		return applyOverride(id, ShiftType.OFF, true, request.reason());
	}

	@Transactional
	public RosterAssignmentResponse override(RosterOverrideRequest request) {
		return applyOverride(request.assignmentId(), request.shiftType(), Boolean.TRUE.equals(request.weeklyOff()),
				request.reason());
	}

	@Transactional
	public List<RosterAssignmentResponse> swapShifts(Long assignmentId1, Long assignmentId2, String reason) {
		if (assignmentId1 == null || assignmentId2 == null) {
			throw new BusinessException("Both assignment IDs are required for shift swap");
		}
		if (assignmentId1.equals(assignmentId2)) {
			throw new BusinessException("Cannot swap an assignment with itself");
		}

		RosterAssignment a1 = assignmentRepository.findById(assignmentId1)
				.orElseThrow(() -> new ResourceNotFoundException("Roster assignment " + assignmentId1 + " not found"));
		RosterAssignment a2 = assignmentRepository.findById(assignmentId2)
				.orElseThrow(() -> new ResourceNotFoundException("Roster assignment " + assignmentId2 + " not found"));

		if ((a1.getCycle() != null && a1.getCycle().getStatus() == RosterStatus.LOCKED) ||
		    (a2.getCycle() != null && a2.getCycle().getStatus() == RosterStatus.LOCKED)) {
			throw new BusinessException("Cannot swap shifts on a locked roster. Changes require an authorized unlock action.");
		}

		if (!a1.getRosterDate().equals(a2.getRosterDate())) {
			throw new BusinessException("Shift swap is only allowed between assignments on the same date");
		}

		Shift shift1 = a1.getShift();
		Shift shift2 = a2.getShift();
		boolean off1 = a1.isWeeklyOff();
		boolean off2 = a2.isWeeklyOff();
		boolean leave1 = a1.isOnLeave();
		boolean leave2 = a2.isOnLeave();

		if (leave1 || leave2) {
			throw new BusinessException("Shift swap rejected: Cannot swap an approved leave assignment");
		}

		// 1. Gender / Eligibility Checks
		if (!off2 && !isEligible(a1.getEmployee(), shift2.getShiftType())) {
			throw new BusinessException("Shift swap rejected: " + a1.getEmployee().getFirstName() + " (" + a1.getEmployee().getGender() + ") is not eligible for " + shift2.getShiftType() + " shift");
		}
		if (!off1 && !isEligible(a2.getEmployee(), shift1.getShiftType())) {
			throw new BusinessException("Shift swap rejected: " + a2.getEmployee().getFirstName() + " (" + a2.getEmployee().getGender() + ") is not eligible for " + shift1.getShiftType() + " shift");
		}

		// 2. Night Shift Count Rule (Max 2 nights per cycle)
		if (shift2.getShiftType() == ShiftType.NIGHT && shift1.getShiftType() != ShiftType.NIGHT) {
			long existingNights = assignmentRepository.findByEmployeeIdOrderByRosterDateAsc(a1.getEmployee().getId()).stream()
					.filter(a -> a.getCycle() != null && a.getCycle().getId().equals(a1.getCycle().getId()))
					.filter(a -> !a.getId().equals(a1.getId()))
					.filter(a -> a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT)
					.count();
			if (existingNights >= MAX_NIGHTS_PER_CYCLE) {
				throw new BusinessException("Shift swap rejected: " + a1.getEmployee().getFirstName() + " would exceed the maximum allowed 2 night shifts in this cycle");
			}
		}
		if (shift1.getShiftType() == ShiftType.NIGHT && shift2.getShiftType() != ShiftType.NIGHT) {
			long existingNights = assignmentRepository.findByEmployeeIdOrderByRosterDateAsc(a2.getEmployee().getId()).stream()
					.filter(a -> a.getCycle() != null && a.getCycle().getId().equals(a2.getCycle().getId()))
					.filter(a -> !a.getId().equals(a2.getId()))
					.filter(a -> a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT)
					.count();
			if (existingNights >= MAX_NIGHTS_PER_CYCLE) {
				throw new BusinessException("Shift swap rejected: " + a2.getEmployee().getFirstName() + " would exceed the maximum allowed 2 night shifts in this cycle");
			}
		}

		// 3. Minimum 12-Hour Rest Check for Employee 1
		if (!off2 && shift2.getShiftType() != ShiftType.OFF) {
			LocalDate date = a1.getRosterDate();
			List<RosterAssignment> prev1 = assignmentRepository.findByEmployeeIdAndRosterDate(a1.getEmployee().getId(), date.minusDays(1));
			if (!prev1.isEmpty()) {
				RosterAssignment prevA = prev1.get(0);
				if (!prevA.isWeeklyOff() && !prevA.isOnLeave() && prevA.getShift() != null && prevA.getShift().getShiftType() != ShiftType.OFF) {
					if (!hasMinimumRest(date.minusDays(1), prevA.getShift(), date, shift2)) {
						throw new BusinessException("Shift swap rejected: " + a1.getEmployee().getFirstName() + " would violate the 12-hour minimum rest rule with previous day's shift");
					}
				}
			}
			List<RosterAssignment> next1 = assignmentRepository.findByEmployeeIdAndRosterDate(a1.getEmployee().getId(), date.plusDays(1));
			if (!next1.isEmpty()) {
				RosterAssignment nextA = next1.get(0);
				if (!nextA.isWeeklyOff() && !nextA.isOnLeave() && nextA.getShift() != null && nextA.getShift().getShiftType() != ShiftType.OFF) {
					if (!hasMinimumRest(date, shift2, date.plusDays(1), nextA.getShift())) {
						throw new BusinessException("Shift swap rejected: " + a1.getEmployee().getFirstName() + " would violate the 12-hour minimum rest rule with next day's shift");
					}
				}
			}
		}

		// 4. Minimum 12-Hour Rest Check for Employee 2
		if (!off1 && shift1.getShiftType() != ShiftType.OFF) {
			LocalDate date = a2.getRosterDate();
			List<RosterAssignment> prev2 = assignmentRepository.findByEmployeeIdAndRosterDate(a2.getEmployee().getId(), date.minusDays(1));
			if (!prev2.isEmpty()) {
				RosterAssignment prevA = prev2.get(0);
				if (!prevA.isWeeklyOff() && !prevA.isOnLeave() && prevA.getShift() != null && prevA.getShift().getShiftType() != ShiftType.OFF) {
					if (!hasMinimumRest(date.minusDays(1), prevA.getShift(), date, shift1)) {
						throw new BusinessException("Shift swap rejected: " + a2.getEmployee().getFirstName() + " would violate the 12-hour minimum rest rule with previous day's shift");
					}
				}
			}
			List<RosterAssignment> next2 = assignmentRepository.findByEmployeeIdAndRosterDate(a2.getEmployee().getId(), date.plusDays(1));
			if (!next2.isEmpty()) {
				RosterAssignment nextA = next2.get(0);
				if (!nextA.isWeeklyOff() && !nextA.isOnLeave() && nextA.getShift() != null && nextA.getShift().getShiftType() != ShiftType.OFF) {
					if (!hasMinimumRest(date, shift1, date.plusDays(1), nextA.getShift())) {
						throw new BusinessException("Shift swap rejected: " + a2.getEmployee().getFirstName() + " would violate the 12-hour minimum rest rule with next day's shift");
					}
				}
			}
		}

		// 5. Weekly OFF Rule Validation
		if (off1 != off2 && a1.getCycle() != null) {
			long offCount1 = assignmentRepository.findByEmployeeIdOrderByRosterDateAsc(a1.getEmployee().getId()).stream()
					.filter(a -> a.getCycle() != null && a.getCycle().getId().equals(a1.getCycle().getId()))
					.filter(a -> !a.getId().equals(a1.getId()))
					.filter(RosterAssignment::isWeeklyOff)
					.count() + (off2 ? 1 : 0);

			long offCount2 = assignmentRepository.findByEmployeeIdOrderByRosterDateAsc(a2.getEmployee().getId()).stream()
					.filter(a -> a.getCycle() != null && a.getCycle().getId().equals(a2.getCycle().getId()))
					.filter(a -> !a.getId().equals(a2.getId()))
					.filter(RosterAssignment::isWeeklyOff)
					.count() + (off1 ? 1 : 0);

			if (offCount1 != 1 || offCount2 != 1) {
				throw new BusinessException("Shift swap rejected: Every active employee must have exactly one Weekly OFF in a Monday-Sunday roster cycle");
			}
		}

		a1.setShift(shift2);
		a1.setWeeklyOff(off2);
		a1.setOnLeave(leave2);
		a1.setOverridden(true);

		a2.setShift(shift1);
		a2.setWeeklyOff(off1);
		a2.setOnLeave(leave1);
		a2.setOverridden(true);

		LocalDateTime now = LocalDateTime.now();

		RosterOverride o1 = new RosterOverride();
		o1.setAssignment(a1);
		o1.setPreviousShiftType(shift1.getShiftType());
		o1.setNewShiftType(shift2.getShiftType());
		o1.setWeeklyOff(off2);
		o1.setReason(reason == null ? "Shift swap with " + a2.getEmployee().getEmployeeCode() : reason);
		o1.setCreatedAt(now);

		RosterOverride o2 = new RosterOverride();
		o2.setAssignment(a2);
		o2.setPreviousShiftType(shift2.getShiftType());
		o2.setNewShiftType(shift1.getShiftType());
		o2.setWeeklyOff(off1);
		o2.setReason(reason == null ? "Shift swap with " + a1.getEmployee().getEmployeeCode() : reason);
		o2.setCreatedAt(now);

		overrideRepository.saveAll(List.of(o1, o2));
		List<RosterAssignment> saved = assignmentRepository.saveAll(List.of(a1, a2));

		if (auditService != null) {
			auditService.log(AuditAction.SHIFT_SWAPPED, "ROSTER_ASSIGNMENT", a1.getId(),
					a1.getCycle() != null ? a1.getCycle().getId() : null,
					a1.getEmployee().getId(), a1.getEmployee().getFirstName() + " " + a1.getEmployee().getLastName(),
					shift1.getShiftType().name(), shift2.getShiftType().name(),
					"Swapped shift with " + a2.getEmployee().getEmployeeCode() + (reason != null ? ": " + reason : ""), "MANUAL");
		}

		if (notificationService != null) {
			notificationService.notifyEmployee(a1.getEmployee(), "Shift Swapped",
					"Your shift on " + a1.getRosterDate() + " was swapped to " + shift2.getShiftType() + " with " + a2.getEmployee().getFirstName() + " " + a2.getEmployee().getLastName(),
					NotificationType.SWAP_EXECUTED, "employeeWorkspace", a1.getCycle() != null ? a1.getCycle().getId() : null);
			notificationService.notifyEmployee(a2.getEmployee(), "Shift Swapped",
					"Your shift on " + a2.getRosterDate() + " was swapped to " + shift1.getShiftType() + " with " + a1.getEmployee().getFirstName() + " " + a1.getEmployee().getLastName(),
					NotificationType.SWAP_EXECUTED, "employeeWorkspace", a2.getCycle() != null ? a2.getCycle().getId() : null);
		}

		if (rosterVersionService != null && a1.getCycle() != null) {
			String actor = SecurityContextHolder.getContext().getAuthentication() != null
					? SecurityContextHolder.getContext().getAuthentication().getName()
					: "admin";
			rosterVersionService.recordVersionSnapshot(
					a1.getCycle(),
					"SHIFT_SWAPPED",
					"Shift swapped on " + a1.getRosterDate() + " between " + a1.getEmployee().getEmployeeCode() + " and " + a2.getEmployee().getEmployeeCode() + (reason != null ? ": " + reason : ""),
					actor);
		}

		return saved.stream().map(this::toAssignmentResponse).toList();
	}

	private record DayGenerationResult(List<RosterAssignment> assignments, DailyCoverageReport coverageReport) {}

	private DayGenerationResult generateDay(RosterCycle cycle, List<Employee> employees,
			Map<Long, LocalDate> weeklyOffs, Set<Long> offTaken, Map<ShiftType, Shift> shifts, LocalDate date,
			Map<Long, Shift> lastShiftMap, Map<Long, LocalDate> lastShiftDateMap,
			Map<Long, Integer> cycleNightCounts, Map<Long, Map<ShiftType, Integer>> shiftCountsMap,
			int maxNightsAllowed) {
		List<RosterAssignment> dayAssignments = new ArrayList<>();
		List<Employee> available = new ArrayList<>();

		int plannedOffOrLeave = 0;

		for (Employee employee : employees) {
			boolean onLeave = isApprovedLeave(employee.getId(), date);
			LocalDate scheduledOff = weeklyOffs.get(employee.getId());
			boolean weeklyOff = !onLeave && scheduledOff != null && date.equals(scheduledOff) && !offTaken.contains(employee.getId());

			if (onLeave) {
				dayAssignments.add(newAssignment(cycle, employee, shifts.get(ShiftType.OFF), date, false, true));
				plannedOffOrLeave++;
			} else if (weeklyOff) {
				offTaken.add(employee.getId());
				dayAssignments.add(newAssignment(cycle, employee, shifts.get(ShiftType.OFF), date, true, false));
				plannedOffOrLeave++;
			} else {
				available.add(employee);
			}
		}

		int plannedWorking = available.size();
		int activeEmployees = employees.size();

		// Calculate Configured Demand vs Feasible Demand for this date
		Map<ShiftType, Integer> configuredDemands = new EnumMap<>(ShiftType.class);
		int dailyConfiguredTotal = 0;
		for (ShiftType type : ASSIGNMENT_ORDER) {
			int cap = Math.max(1, shifts.get(type).getCapacity());
			if (type == ShiftType.NIGHT) {
				cap = 1;
			}
			configuredDemands.put(type, cap);
			dailyConfiguredTotal += cap;
		}

		// Calculate realistic feasible daily demand tailored for available staff
		Map<ShiftType, Integer> feasibleDemands = calculateDailyFeasibleDemands(available, configuredDemands);
		int dailyFeasibleTotal = feasibleDemands.values().stream().mapToInt(Integer::intValue).sum();

		// Build prioritized required slots list
		List<ShiftType> feasibleSlots = new ArrayList<>();
		for (ShiftType type : ASSIGNMENT_ORDER) {
			int count = feasibleDemands.getOrDefault(type, 0);
			for (int i = 0; i < count; i++) {
				feasibleSlots.add(type);
			}
		}

		Map<ShiftType, List<Employee>> dailyMatching = new EnumMap<>(ShiftType.class);
		Set<Long> assigned = new HashSet<>();

		// Solve feasible matching
		int dayOffset = cycle.getStartDate() != null ? (int) java.time.temporal.ChronoUnit.DAYS.between(cycle.getStartDate(), date) : 0;
		boolean solvedFeasible = solveDay(0, feasibleSlots, available, assigned, dailyMatching, date,
				shifts, lastShiftMap, lastShiftDateMap, cycleNightCounts, shiftCountsMap, maxNightsAllowed, weeklyOffs, cycle.getStartDate(), cycle.getEndDate());

		if (!solvedFeasible) {
			// Backtracking fallback
			assigned.clear();
			dailyMatching.clear();

			for (ShiftType type : ASSIGNMENT_ORDER) {
				int target = feasibleDemands.getOrDefault(type, 1);
				for (int i = 0; i < target; i++) {
					Shift candidateShift = shifts.get(type);
					List<Employee> candidates = available.stream()
							.filter(emp -> !assigned.contains(emp.getId()))
							.filter(emp -> isEligible(emp, type))
							.filter(emp -> {
								if (type != ShiftType.NIGHT) return true;
								if (cycleNightCounts.getOrDefault(emp.getId(), 0) >= maxNightsAllowed) return false;
								boolean wasNightYesterday = false;
								if (cycleNightCounts.getOrDefault(emp.getId(), 0) >= 1) {
									Shift prevS = lastShiftMap.get(emp.getId());
									LocalDate prevD = lastShiftDateMap.get(emp.getId());
									wasNightYesterday = prevS != null && prevS.getShiftType() == ShiftType.NIGHT && prevD != null && prevD.equals(date.minusDays(1));
									if (!wasNightYesterday) return false;
								}
								LocalDate nextDate = date.plusDays(1);
								if (cycle.getEndDate() != null && nextDate.isAfter(cycle.getEndDate())) return true;
								boolean isCompletingSecondNight = (cycleNightCounts.getOrDefault(emp.getId(), 0) == 1) && wasNightYesterday;
								boolean isOffTomorrow = nextDate.equals(weeklyOffs != null ? weeklyOffs.get(emp.getId()) : null);
								boolean isLeaveTomorrow = isApprovedLeave(emp.getId(), nextDate);
								boolean canDoSecondNight = (cycleNightCounts.getOrDefault(emp.getId(), 0) == 0) && (dayOffset == 0 || dayOffset == 2 || dayOffset == 4);
								return isCompletingSecondNight || isOffTomorrow || isLeaveTomorrow || canDoSecondNight;
							})
							.filter(emp -> hasMinimumRest(lastShiftDateMap.get(emp.getId()), lastShiftMap.get(emp.getId()), date, candidateShift))
							.sorted(Comparator.comparingInt((Employee emp) -> score(emp, type, date, lastShiftMap, cycleNightCounts, shiftCountsMap, weeklyOffs, cycle.getStartDate()))
									.thenComparing(Employee::getId))
							.toList();

					if (!candidates.isEmpty()) {
						Employee chosen = candidates.get(0);
						assigned.add(chosen.getId());
						dailyMatching.computeIfAbsent(type, k -> new ArrayList<>()).add(chosen);
					} else {
						break;
					}
				}
			}
		}

		// Rebalancing / Repair: Ensure mandatory coverage (Morning >= 1, General >= 1, Evening >= 1, Night = 1)
		repairDailyCoverage(dailyMatching, date, shifts, lastShiftMap, lastShiftDateMap, cycleNightCounts, maxNightsAllowed);

		// Record assigned shifts
		Map<ShiftType, Integer> actualAssignedCounts = new EnumMap<>(ShiftType.class);
		for (ShiftType type : ASSIGNMENT_ORDER) {
			actualAssignedCounts.put(type, 0);
		}

		for (Map.Entry<ShiftType, List<Employee>> entry : dailyMatching.entrySet()) {
			ShiftType type = entry.getKey();
			for (Employee emp : entry.getValue()) {
				dayAssignments.add(newAssignment(cycle, emp, shifts.get(type), date, false, false));
				actualAssignedCounts.put(type, actualAssignedCounts.get(type) + 1);

				if (type == ShiftType.NIGHT) {
					cycleNightCounts.put(emp.getId(), cycleNightCounts.getOrDefault(emp.getId(), 0) + 1);
				}

				Map<ShiftType, Integer> empCounts = shiftCountsMap.get(emp.getId());
				if (empCounts != null) {
					empCounts.compute(type, (k, v) -> v == null ? 1 : v + 1);
				}
			}
		}

		// Handle any remaining available employee: assign to Day/Evening shift if valid (NEVER NIGHT)
		List<Employee> unassigned = available.stream().filter(e -> !assigned.contains(e.getId())).toList();
		for (Employee emp : unassigned) {
			ShiftType bestExtra = null;
			Shift prevShift = lastShiftMap.get(emp.getId());
			ShiftType prevType = prevShift != null ? prevShift.getShiftType() : null;

			// Prefer same shift as yesterday for continuity, otherwise try day shifts
			List<ShiftType> tryTypes = new ArrayList<>();
			if (prevType != null && prevType != ShiftType.NIGHT && prevType != ShiftType.OFF) {
				tryTypes.add(prevType);
			}
			for (ShiftType candidateType : List.of(ShiftType.MORNING, ShiftType.GENERAL, ShiftType.EVENING)) {
				if (!tryTypes.contains(candidateType)) {
					tryTypes.add(candidateType);
				}
			}

			for (ShiftType candidateType : tryTypes) {
				Shift candidateShift = shifts.get(candidateType);
				if (isEligible(emp, candidateType) &&
						hasMinimumRest(lastShiftDateMap.get(emp.getId()), lastShiftMap.get(emp.getId()), date, candidateShift)) {
					bestExtra = candidateType;
					break;
				}
			}

			if (bestExtra != null) {
				dayAssignments.add(newAssignment(cycle, emp, shifts.get(bestExtra), date, false, false));
				actualAssignedCounts.put(bestExtra, actualAssignedCounts.get(bestExtra) + 1);
				Map<ShiftType, Integer> empCounts = shiftCountsMap.get(emp.getId());
				if (empCounts != null) {
					empCounts.compute(bestExtra, (k, v) -> v == null ? 1 : v + 1);
				}
			} else {
				// Fallback: assign to eligible working shift (GENERAL/MORNING/EVENING) if rest allows
				ShiftType fallback = null;
				for (ShiftType candidateType : List.of(ShiftType.GENERAL, ShiftType.MORNING, ShiftType.EVENING)) {
					if (isEligible(emp, candidateType) &&
							hasMinimumRest(lastShiftDateMap.get(emp.getId()), lastShiftMap.get(emp.getId()), date, shifts.get(candidateType))) {
						fallback = candidateType;
						break;
					}
				}
				if (fallback != null) {
					dayAssignments.add(newAssignment(cycle, emp, shifts.get(fallback), date, false, false));
					actualAssignedCounts.put(fallback, actualAssignedCounts.get(fallback) + 1);
					Map<ShiftType, Integer> empCounts = shiftCountsMap.get(emp.getId());
					if (empCounts != null) {
						empCounts.compute(fallback, (k, v) -> v == null ? 1 : v + 1);
					}
				} else {
					// Rest prevents working any day shift (e.g. post-night recovery)
					dayAssignments.add(newAssignment(cycle, emp, shifts.get(ShiftType.OFF), date, true, false));
					offTaken.add(emp.getId());
				}
			}
		}

		// Update in-flight shift and date states
		for (RosterAssignment assignment : dayAssignments) {
			Long empId = assignment.getEmployee().getId();
			if (!assignment.isWeeklyOff() && !assignment.isOnLeave() && assignment.getShift().getShiftType() != ShiftType.OFF) {
				lastShiftMap.put(empId, assignment.getShift());
				lastShiftDateMap.put(empId, assignment.getRosterDate());
			}
		}

		// Compile Daily Coverage Report with 3-tier metrics
		List<ShiftCoverageSummary> summaries = new ArrayList<>();
		int dailyAssignedTotal = 0;
		int dailyOperationalShortage = 0;

		for (ShiftType type : ASSIGNMENT_ORDER) {
			int configured = configuredDemands.getOrDefault(type, 1);
			int feasible = feasibleDemands.getOrDefault(type, 1);
			int actual = actualAssignedCounts.getOrDefault(type, 0);
			int opShortage = Math.max(0, feasible - actual);

			dailyAssignedTotal += actual;
			dailyOperationalShortage += opShortage;

			String status;
			String reason = null;

			if (opShortage > 0) {
				status = "SHORTAGE";
				reason = determineShortageReason(type, available, assigned, date, shifts.get(type),
						lastShiftMap, lastShiftDateMap, cycleNightCounts, maxNightsAllowed);
			} else if (actual < configured) {
				status = (type == ShiftType.NIGHT || type == ShiftType.EVENING)
						? "Workforce/eligibility-limited"
						: "Workforce-limited";
				reason = "Configured demand (" + configured + ") dynamically adapted to workforce capacity (" + feasible + ")";
			} else {
				status = "FULL";
				reason = "✓ Fully staffed within safety rules";
			}

			summaries.add(new ShiftCoverageSummary(type, configured, feasible, actual, opShortage, status, reason));
		}

		DailyCoverageReport dailyReport = new DailyCoverageReport(date, activeEmployees, plannedWorking,
				plannedOffOrLeave, dailyConfiguredTotal, dailyFeasibleTotal, dailyAssignedTotal,
				dailyOperationalShortage, summaries);

		return new DayGenerationResult(dayAssignments, dailyReport);
	}

	private void repairDailyCoverage(Map<ShiftType, List<Employee>> dailyMatching, LocalDate date,
			Map<ShiftType, Shift> shifts, Map<Long, Shift> lastShiftMap, Map<Long, LocalDate> lastShiftDateMap,
			Map<Long, Integer> cycleNightCounts, int maxNightsAllowed) {
		for (ShiftType missingType : List.of(ShiftType.NIGHT, ShiftType.EVENING, ShiftType.GENERAL, ShiftType.MORNING)) {
			List<Employee> assigned = dailyMatching.getOrDefault(missingType, Collections.emptyList());
			if (!assigned.isEmpty()) {
				continue;
			}

			Shift targetShift = shifts.get(missingType);
			for (ShiftType donorType : List.of(ShiftType.MORNING, ShiftType.GENERAL, ShiftType.EVENING)) {
				if (donorType == missingType) continue;
				List<Employee> donorList = dailyMatching.getOrDefault(donorType, Collections.emptyList());
				if (donorList.size() <= 1) continue; // Keep at least 1 in donor shift

				Employee candidateToMove = null;
				for (Employee candidate : donorList) {
					if (isEligible(candidate, missingType)
							&& !(missingType == ShiftType.NIGHT && cycleNightCounts.getOrDefault(candidate.getId(), 0) >= maxNightsAllowed)
							&& hasMinimumRest(lastShiftDateMap.get(candidate.getId()), lastShiftMap.get(candidate.getId()), date, targetShift)) {
						candidateToMove = candidate;
						break;
					}
				}

				if (candidateToMove != null) {
					donorList.remove(candidateToMove);
					dailyMatching.computeIfAbsent(missingType, k -> new ArrayList<>()).add(candidateToMove);
					break;
				}
			}
		}
	}

	private Map<ShiftType, Integer> calculateDailyFeasibleDemands(List<Employee> available, Map<ShiftType, Integer> configuredDemands) {
		Map<ShiftType, Integer> feasible = new EnumMap<>(ShiftType.class);
		int availableCount = available.size();

		if (availableCount == 0) {
			for (ShiftType type : ASSIGNMENT_ORDER) feasible.put(type, 0);
			return feasible;
		}

		// Baseline: Ensure all 4 shift types are covered (Night is strictly exactly 1)
		feasible.put(ShiftType.NIGHT, availableCount >= 1 ? 1 : 0);
		feasible.put(ShiftType.EVENING, availableCount >= 2 ? 1 : 0);
		feasible.put(ShiftType.MORNING, availableCount >= 3 ? 1 : 0);
		feasible.put(ShiftType.GENERAL, availableCount >= 4 ? 1 : 0);

		int baselineAssigned = feasible.values().stream().mapToInt(Integer::intValue).sum();
		int remainingStaff = Math.max(0, availableCount - baselineAssigned);

		// Distribute remaining available staff to Morning and General (never to Night!)
		while (remainingStaff > 0) {
			int morningCur = feasible.get(ShiftType.MORNING);
			int generalCur = feasible.get(ShiftType.GENERAL);
			int morningTarget = configuredDemands.getOrDefault(ShiftType.MORNING, 2);
			int generalTarget = configuredDemands.getOrDefault(ShiftType.GENERAL, 2);

			if (morningCur < morningTarget) {
				feasible.put(ShiftType.MORNING, morningCur + 1);
				remainingStaff--;
			} else if (generalCur < generalTarget) {
				feasible.put(ShiftType.GENERAL, generalCur + 1);
				remainingStaff--;
			} else {
				feasible.put(ShiftType.GENERAL, generalCur + 1);
				remainingStaff--;
			}
		}

		return feasible;
	}

	private String determineShortageReason(ShiftType type, List<Employee> available, Set<Long> assigned,
			LocalDate date, Shift shift, Map<Long, Shift> lastShiftMap, Map<Long, LocalDate> lastShiftDateMap,
			Map<Long, Integer> cycleNightCounts, int maxNightsAllowed) {
		if (available.isEmpty()) {
			return "No active employees available on " + date + " (all on leave or weekly off)";
		}

		long eligibleCount = available.stream().filter(e -> isEligible(e, type)).count();
		if (eligibleCount == 0) {
			return "No available employees eligible for " + type + " (female restriction / skills)";
		}

		if (type == ShiftType.NIGHT) {
			long nightLimitReached = available.stream()
					.filter(e -> isEligible(e, type))
					.filter(e -> cycleNightCounts.getOrDefault(e.getId(), 0) >= maxNightsAllowed)
					.count();
			if (nightLimitReached == eligibleCount) {
				return "All eligible employees have reached maximum night limit (" + maxNightsAllowed + "/cycle)";
			}
		}

		long restBlocked = available.stream()
				.filter(e -> isEligible(e, type))
				.filter(e -> !hasMinimumRest(lastShiftDateMap.get(e.getId()), lastShiftMap.get(e.getId()), date, shift))
				.count();
		if (restBlocked == eligibleCount) {
			return "All eligible employees blocked by 12h rest rule from previous shift";
		}

		return "Workforce capacity prioritized for other mandatory shifts";
	}

	private boolean solveDay(int slotIndex, List<ShiftType> slots, List<Employee> available,
			Set<Long> assigned, Map<ShiftType, List<Employee>> dailyAssignments, LocalDate date,
			Map<ShiftType, Shift> shifts, Map<Long, Shift> lastShiftMap, Map<Long, LocalDate> lastShiftDateMap,
			Map<Long, Integer> cycleNightCounts, Map<Long, Map<ShiftType, Integer>> shiftCountsMap,
			int maxNightsAllowed, Map<Long, LocalDate> weeklyOffs, LocalDate cycleStartDate, LocalDate cycleEndDate) {
		if (slotIndex >= slots.size()) {
			return true;
		}

		ShiftType shiftType = slots.get(slotIndex);
		Shift shift = shifts.get(shiftType);
		int dayOffset = cycleStartDate != null ? (int) java.time.temporal.ChronoUnit.DAYS.between(cycleStartDate, date) : 0;

		List<Employee> candidates = available.stream()
				.filter(e -> !assigned.contains(e.getId()))
				.filter(e -> isEligible(e, shiftType))
				.filter(e -> {
					if (shiftType != ShiftType.NIGHT) return true;
					if (cycleNightCounts.getOrDefault(e.getId(), 0) >= maxNightsAllowed) return false;
					boolean wasNightYesterday = false;
					if (cycleNightCounts.getOrDefault(e.getId(), 0) >= 1) {
						Shift prevS = lastShiftMap.get(e.getId());
						LocalDate prevD = lastShiftDateMap.get(e.getId());
						wasNightYesterday = prevS != null && prevS.getShiftType() == ShiftType.NIGHT && prevD != null && prevD.equals(date.minusDays(1));
						if (!wasNightYesterday) return false;
					}
					LocalDate nextDate = date.plusDays(1);
					if (cycleEndDate != null && nextDate.isAfter(cycleEndDate)) return true;
					boolean isCompletingSecondNight = (cycleNightCounts.getOrDefault(e.getId(), 0) == 1) && wasNightYesterday;
					boolean isOffTomorrow = nextDate.equals(weeklyOffs != null ? weeklyOffs.get(e.getId()) : null);
					boolean isLeaveTomorrow = isApprovedLeave(e.getId(), nextDate);
					boolean canDoSecondNight = (cycleNightCounts.getOrDefault(e.getId(), 0) == 0) && (dayOffset == 0 || dayOffset == 2 || dayOffset == 4);
					return isCompletingSecondNight || isOffTomorrow || isLeaveTomorrow || canDoSecondNight;
				})
				.filter(e -> hasMinimumRest(lastShiftDateMap.get(e.getId()), lastShiftMap.get(e.getId()), date, shift))
				.sorted(Comparator.comparingInt((Employee e) -> score(e, shiftType, date, lastShiftMap, cycleNightCounts, shiftCountsMap, weeklyOffs, cycleStartDate))
						.thenComparing(Employee::getId))
				.toList();

		for (Employee candidate : candidates) {
			assigned.add(candidate.getId());
			dailyAssignments.computeIfAbsent(shiftType, k -> new ArrayList<>()).add(candidate);

			if (solveDay(slotIndex + 1, slots, available, assigned, dailyAssignments, date,
					shifts, lastShiftMap, lastShiftDateMap, cycleNightCounts, shiftCountsMap, maxNightsAllowed, weeklyOffs, cycleStartDate, cycleEndDate)) {
				return true;
			}

			assigned.remove(candidate.getId());
			dailyAssignments.get(shiftType).remove(candidate);
		}

		return false;
	}

	private Map<Long, LocalDate> planWeeklyOffs(List<Employee> employees, LocalDate startDate, LocalDate endDate) {
		Map<Long, LocalDate> result = new HashMap<>();
		Map<LocalDate, Integer> offCounts = new LinkedHashMap<>();
		for (int i = 0; i < 7; i++) {
			offCounts.put(startDate.plusDays(i), 0);
		}

		// Maximum normal OFF capacity per day = total active employees - 4 mandatory shifts (Morning, General, Evening, Night)
		int maxOffPerDay = Math.max(1, Math.min(3, employees.size() - 4));

		List<Employee> males = employees.stream()
				.filter(e -> e.getGender() == Gender.MALE)
				.toList();
		List<Employee> females = employees.stream()
				.filter(e -> e.getGender() == Gender.FEMALE)
				.toList();

		// Sort by weekend fairness: fewest past weekend OFFs first
		List<Employee> sortedMales = new ArrayList<>(males);
		sortedMales.sort(Comparator.comparingInt(this::weekendOffCount)
				.thenComparing(Comparator.comparingInt(this::weekendWorkCount).reversed())
				.thenComparing(Employee::getId));

		List<Employee> sortedFemales = new ArrayList<>(females);
		sortedFemales.sort(Comparator.comparingInt(this::weekendOffCount)
				.thenComparing(Comparator.comparingInt(this::weekendWorkCount).reversed())
				.thenComparing(Employee::getId));

		LocalDate d0 = startDate.plusDays(0);
		LocalDate d1 = startDate.plusDays(1);
		LocalDate d2 = startDate.plusDays(2);
		LocalDate d3 = startDate.plusDays(3);
		LocalDate d4 = startDate.plusDays(4);
		LocalDate d5 = startDate.plusDays(5);
		LocalDate d6 = startDate.plusDays(6);

		// Distribute males:
		// Male 1 (Day 0, 1 Night) -> Day 2 (Wed)
		// Male 2 (Day 2, 3 Night) -> Day 4 (Fri)
		// Male 3 (Day 4, 5 Night) -> Day 6 (Sun)
		// Male 4 (Day 6 Night) -> Day 5 (Sat)
		// Male 5 -> Day 6 (Sun)
		List<LocalDate> maleOrderedTargets;
		if (sortedMales.size() >= 5) {
			maleOrderedTargets = List.of(d2, d4, d6, d5, d6);
		} else if (sortedMales.size() == 4) {
			maleOrderedTargets = List.of(d2, d4, d6, d5);
		} else if (sortedMales.size() == 3) {
			maleOrderedTargets = List.of(d2, d4, d6);
		} else {
			maleOrderedTargets = List.of(d6, d5, d4, d2, d3, d1, d0);
		}

		Map<LocalDate, Integer> maleOffCounts = new LinkedHashMap<>();
		for (int i = 0; i < 7; i++) {
			maleOffCounts.put(startDate.plusDays(i), 0);
		}
		int maxMaleOffPerDay = Math.min(2, Math.max(1, males.size() - 3));

		for (int i = 0; i < sortedMales.size(); i++) {
			Employee m = sortedMales.get(i);
			if (result.containsKey(m.getId())) continue;

			// If employee is on leave on all 7 days of the cycle, skip
			boolean allLeave = true;
			for (int d = 0; d < 7; d++) {
				if (!isApprovedLeave(m.getId(), startDate.plusDays(d))) {
					allLeave = false;
					break;
				}
			}
			if (allLeave) {
				result.put(m.getId(), null);
				continue;
			}

			LocalDate target = i < maleOrderedTargets.size() ? maleOrderedTargets.get(i) : d5;
			if (isApprovedLeave(m.getId(), target)
					|| offCounts.getOrDefault(target, 0) >= maxOffPerDay
					|| maleOffCounts.getOrDefault(target, 0) >= maxMaleOffPerDay) {
				// Pick next available non-leave day
				target = List.of(d5, d6, d4, d3, d2, d1, d0).stream()
						.filter(d -> !isApprovedLeave(m.getId(), d))
						.filter(d -> maleOffCounts.getOrDefault(d, 0) < maxMaleOffPerDay)
						.filter(d -> offCounts.getOrDefault(d, 0) < maxOffPerDay)
						.findFirst()
						.orElseGet(() -> List.of(d5, d6, d4, d3, d2, d1, d0).stream()
								.filter(d -> !isApprovedLeave(m.getId(), d))
								.findFirst()
								.orElse(d5));
			}
			result.put(m.getId(), target);
			offCounts.compute(target, (d, count) -> count == null ? 1 : count + 1);
			maleOffCounts.compute(target, (d, count) -> count == null ? 1 : count + 1);
		}

		// Distribute females:
		List<LocalDate> femaleOrderedTargets;
		if (maxOffPerDay >= 2) {
			femaleOrderedTargets = List.of(d6, d5, d4, d3, d2, d1, d0);
		} else {
			femaleOrderedTargets = List.of(d5, d3, d1, d0, d4, d2, d6);
		}

		for (int i = 0; i < sortedFemales.size(); i++) {
			Employee f = sortedFemales.get(i);
			if (result.containsKey(f.getId())) continue;

			// If employee is on leave on all 7 days of the cycle, skip
			boolean allLeave = true;
			for (int d = 0; d < 7; d++) {
				if (!isApprovedLeave(f.getId(), startDate.plusDays(d))) {
					allLeave = false;
					break;
				}
			}
			if (allLeave) {
				result.put(f.getId(), null);
				continue;
			}

			LocalDate chosen = null;
			for (LocalDate target : femaleOrderedTargets) {
				if (!isApprovedLeave(f.getId(), target) && offCounts.getOrDefault(target, 0) < maxOffPerDay) {
					chosen = target;
					break;
				}
			}
			if (chosen == null) {
				chosen = List.of(d6, d5, d4, d3, d2, d1, d0).stream()
						.filter(d -> !isApprovedLeave(f.getId(), d))
						.findFirst()
						.orElse(d5);
			}
			result.put(f.getId(), chosen);
			offCounts.compute(chosen, (d, count) -> count == null ? 1 : count + 1);
		}

		return result;
	}

	private void enforceAndRepairExactWeeklyOff(RosterCycle cycle, List<RosterAssignment> assignments,
			List<Employee> employees, Map<ShiftType, Shift> shifts, int maxNightsAllowed, Map<Long, LocalDate> weeklyOffs) {
		Map<Long, List<RosterAssignment>> empAssignments = assignments.stream()
				.collect(Collectors.groupingBy(a -> a.getEmployee().getId()));

		for (Employee emp : employees) {
			List<RosterAssignment> list = empAssignments.getOrDefault(emp.getId(), Collections.emptyList());
			if (list.isEmpty()) continue;
			list.sort(Comparator.comparing(RosterAssignment::getRosterDate));

			long leaveCount = list.stream().filter(RosterAssignment::isOnLeave).count();
			if (leaveCount >= 7) {
				continue;
			}

			// 1. If employee worked NIGHT, the day immediately following ANY night shift (if not another night shift) MUST be OFF
			Set<LocalDate> postNightOffDates = new HashSet<>();
			for (int i = 0; i < list.size(); i++) {
				RosterAssignment cur = list.get(i);
				if (!cur.isWeeklyOff() && !cur.isOnLeave() && cur.getShift() != null && cur.getShift().getShiftType() == ShiftType.NIGHT) {
					if (i + 1 < list.size()) {
						RosterAssignment next = list.get(i + 1);
						if (!next.isOnLeave() && (next.getShift() == null || next.getShift().getShiftType() != ShiftType.NIGHT)) {
							postNightOffDates.add(next.getRosterDate());
							next.setWeeklyOff(true);
							next.setShift(shifts.get(ShiftType.OFF));
						}
					}
				}
			}

			// 2. Query offList after ensuring post-night OFF
			List<RosterAssignment> offList = new ArrayList<>(list.stream().filter(RosterAssignment::isWeeklyOff).toList());

			// Case 1: More than 1 Weekly OFF -> Convert extra OFFs to valid working shifts with 12h rest
			if (offList.size() > 1) {
				RosterAssignment primaryOff = null;
				// Post-night OFF takes absolute priority as the employee's weekly OFF
				if (!postNightOffDates.isEmpty()) {
					LocalDate postNightDate = postNightOffDates.iterator().next();
					primaryOff = offList.stream().filter(a -> a.getRosterDate().equals(postNightDate)).findFirst().orElse(null);
				}
				if (primaryOff == null && weeklyOffs != null) {
					LocalDate scheduled = weeklyOffs.get(emp.getId());
					if (scheduled != null) {
						primaryOff = offList.stream().filter(a -> a.getRosterDate().equals(scheduled)).findFirst().orElse(null);
					}
				}
				if (primaryOff == null) {
					primaryOff = offList.get(0);
				}

				for (RosterAssignment extraOff : offList) {
					if (extraOff.equals(primaryOff)) continue;

					extraOff.setWeeklyOff(false);
					extraOff.setOnLeave(false);

					int idx = list.indexOf(extraOff);
					RosterAssignment prev = idx > 0 ? list.get(idx - 1) : null;
					RosterAssignment next = idx < list.size() - 1 ? list.get(idx + 1) : null;

					ShiftType chosen = null;
					for (ShiftType candidate : List.of(ShiftType.GENERAL, ShiftType.MORNING, ShiftType.EVENING)) {
						if (!isEligible(emp, candidate)) continue;
						Shift candidateShift = shifts.get(candidate);
						boolean restPrev = (prev == null || prev.isWeeklyOff() || prev.isOnLeave() ||
								hasMinimumRest(prev.getRosterDate(), prev.getShift(), extraOff.getRosterDate(), candidateShift));
						boolean restNext = (next == null || next.isWeeklyOff() || next.isOnLeave() ||
								hasMinimumRest(extraOff.getRosterDate(), candidateShift, next.getRosterDate(), next.getShift()));
						if (restPrev && restNext) {
							chosen = candidate;
							break;
						}
					}
					if (chosen == null) {
						if (isEligible(emp, ShiftType.NIGHT)) {
							Shift nightShift = shifts.get(ShiftType.NIGHT);
							boolean restPrev = (prev == null || prev.isWeeklyOff() || prev.isOnLeave() ||
									hasMinimumRest(prev.getRosterDate(), prev.getShift(), extraOff.getRosterDate(), nightShift));
							boolean restNext = (next == null || next.isWeeklyOff() || next.isOnLeave() ||
									hasMinimumRest(extraOff.getRosterDate(), nightShift, next.getRosterDate(), next.getShift()));
							if (restPrev && restNext) {
								chosen = ShiftType.NIGHT;
							}
						}
					}
					if (chosen != null) {
						extraOff.setShift(shifts.get(chosen));
					} else {
						extraOff.setShift(shifts.get(ShiftType.GENERAL));
					}
				}
			}

			// Case 2: 0 Weekly OFF (and leave < 7) -> Assign exactly 1 Weekly OFF
			if (offList.isEmpty()) {
				LocalDate scheduled = weeklyOffs != null ? weeklyOffs.get(emp.getId()) : null;
				RosterAssignment targetAssignment = null;
				if (scheduled != null) {
					targetAssignment = list.stream().filter(a -> a.getRosterDate().equals(scheduled) && !a.isOnLeave()).findFirst().orElse(null);
				}
				if (targetAssignment == null) {
					targetAssignment = list.stream().filter(a -> !a.isOnLeave()).reduce((first, second) -> second).orElse(null);
				}

				if (targetAssignment != null) {
					targetAssignment.setWeeklyOff(true);
					targetAssignment.setOnLeave(false);
					targetAssignment.setShift(shifts.get(ShiftType.OFF));
				}
			}
		}
	}

	private int score(Employee employee, ShiftType shiftType, LocalDate date, Map<Long, Shift> lastShiftMap,
			Map<Long, Integer> cycleNightCounts, Map<Long, Map<ShiftType, Integer>> shiftCountsMap,
			Map<Long, LocalDate> weeklyOffs, LocalDate cycleStartDate) {
		int shiftCount = shiftCountsMap != null && shiftCountsMap.containsKey(employee.getId())
				? shiftCountsMap.get(employee.getId()).getOrDefault(shiftType, 0)
				: (int) assignmentRepository.countShiftForEmployee(employee.getId(), shiftType);
		
		int score = shiftCount * 10;

		// Prioritize female employees for Morning and General so males stay available for Evening and Night
		if (shiftType == ShiftType.MORNING || shiftType == ShiftType.GENERAL) {
			if (employee.getGender() == Gender.FEMALE) {
				score -= 150;
			}
		}
		
		if (shiftType == ShiftType.NIGHT) {
			// Prioritize employees with fewer nights in current cycle
			score += cycleNightCounts.getOrDefault(employee.getId(), 0) * 150;
			Shift lastShiftObj = lastShiftMap.get(employee.getId());
			if (lastShiftObj != null && lastShiftObj.getShiftType() == ShiftType.NIGHT) {
				// Strongly prioritize completing the consecutive 2-night block (15h rest)
				score -= 3500;
			}

			// Reward matching night shifts to recovery days by cycle offset
			LocalDate scheduledOff = weeklyOffs != null ? weeklyOffs.get(employee.getId()) : null;
			int dayOffset = cycleStartDate != null ? (int) java.time.temporal.ChronoUnit.DAYS.between(cycleStartDate, date) : 0;
			if (scheduledOff != null && cycleStartDate != null) {
				int offOffset = (int) java.time.temporal.ChronoUnit.DAYS.between(cycleStartDate, scheduledOff);
				if ((dayOffset == 0 || dayOffset == 1) && offOffset == 2) {
					score -= 600;
				} else if ((dayOffset == 2 || dayOffset == 3) && offOffset == 4) {
					score -= 600;
				} else if ((dayOffset == 4 || dayOffset == 5) && offOffset == 6) {
					score -= 600;
				} else if (dayOffset == 6 && offOffset == 5) {
					score -= 600;
				}
			}
		}

		Shift lastShiftObj = lastShiftMap.get(employee.getId());
		ShiftType lastShift = lastShiftObj != null ? lastShiftObj.getShiftType() : null;
		if (lastShift != null) {
			if (lastShift == shiftType) {
				// Strong bonus for maintaining shift continuity (same shift block)
				score -= 280;
			} else {
				// Penalty for unnecessary shift change
				score += 180;
				// Larger penalty for rapid/disruptive switching between dissimilar shifts
				if ((lastShift == ShiftType.MORNING && shiftType == ShiftType.EVENING) ||
				    (lastShift == ShiftType.EVENING && shiftType == ShiftType.MORNING) ||
				    (lastShift == ShiftType.GENERAL && shiftType == ShiftType.NIGHT)) {
					score += 120;
				}
			}
		}

		if (isWeekend(date)) {
			score += weekendWorkCount(employee) * 5;
		}
		if (shiftType == ShiftType.NIGHT || shiftType == ShiftType.EVENING || shiftType == ShiftType.MORNING) {
			score += shiftCount * 3;
		}
		return score;
	}

	private int score(Employee employee, ShiftType shiftType, LocalDate date, Map<Long, Shift> lastShiftMap,
			Map<Long, Integer> cycleNightCounts, Map<Long, Map<ShiftType, Integer>> shiftCountsMap) {
		return score(employee, shiftType, date, lastShiftMap, cycleNightCounts, shiftCountsMap, null, null);
	}

	public int calculateRosterQualityScore(List<RosterAssignment> assignments) {
		if (assignments == null || assignments.isEmpty()) return 0;
		int score = 1000;
		
		Map<Long, List<RosterAssignment>> byEmployee = assignments.stream()
				.collect(Collectors.groupingBy(a -> a.getEmployee().getId()));
		
		for (List<RosterAssignment> empAssignments : byEmployee.values()) {
			empAssignments.sort(Comparator.comparing(RosterAssignment::getRosterDate));
			ShiftType last = null;
			int consecutive = 0;
			for (RosterAssignment a : empAssignments) {
				if (a.isWeeklyOff() || a.isOnLeave() || a.getShift().getShiftType() == ShiftType.OFF) {
					last = null;
					consecutive = 0;
					continue;
				}
				ShiftType cur = a.getShift().getShiftType();
				if (last != null) {
					if (last == cur) {
						consecutive++;
						score += 20 * consecutive; // Reward multi-day shift blocks
					} else {
						score -= 30; // Penalize shift change
						consecutive = 0;
					}
				}
				last = cur;
			}
		}
		return score;
	}

	// -------------------------------------------------------------------------
	// CENTRALIZED REST TIME & TIMING CALCULATION ENGINE
	// -------------------------------------------------------------------------

	public LocalDateTime calculateShiftStartDateTime(LocalDate date, Shift shift) {
		if (date == null || shift == null || shift.getShiftType() == ShiftType.OFF) return null;
		LocalTime start = shift.getStartTime() != null ? shift.getStartTime() : defaultStartTime(shift.getShiftType());
		if (start == null) return null;
		return LocalDateTime.of(date, start);
	}

	public LocalDateTime calculateShiftEndDateTime(LocalDate date, Shift shift) {
		if (date == null || shift == null || shift.getShiftType() == ShiftType.OFF) return null;
		LocalTime start = shift.getStartTime() != null ? shift.getStartTime() : defaultStartTime(shift.getShiftType());
		LocalTime end = shift.getEndTime() != null ? shift.getEndTime() : defaultEndTime(shift.getShiftType());
		if (start == null || end == null) return null;
		boolean overnight = shift.isOvernight() || end.isBefore(start);
		return overnight ? LocalDateTime.of(date.plusDays(1), end) : LocalDateTime.of(date, end);
	}

	public Duration calculateRestDuration(LocalDate prevDate, Shift prevShift, LocalDate nextDate, Shift nextShift) {
		if (prevDate == null || prevShift == null || prevShift.getShiftType() == ShiftType.OFF ||
				nextDate == null || nextShift == null || nextShift.getShiftType() == ShiftType.OFF) {
			return Duration.ofHours(48); // Full rest
		}
		LocalDateTime prevEnd = calculateShiftEndDateTime(prevDate, prevShift);
		LocalDateTime nextStart = calculateShiftStartDateTime(nextDate, nextShift);
		if (prevEnd == null || nextStart == null) return Duration.ofHours(48);
		return Duration.between(prevEnd, nextStart);
	}

	public boolean hasMinimumRest(LocalDate prevDate, Shift prevShift, LocalDate nextDate, Shift nextShift) {
		if (prevDate == null || prevShift == null || prevShift.getShiftType() == ShiftType.OFF ||
				nextDate == null || nextShift == null || nextShift.getShiftType() == ShiftType.OFF) {
			return true;
		}
		Duration rest = calculateRestDuration(prevDate, prevShift, nextDate, nextShift);
		return !rest.isNegative() && rest.toMinutes() >= (MIN_REST_HOURS * 60);
	}

	private LocalTime defaultStartTime(ShiftType type) {
		return switch (type) {
			case MORNING -> LocalTime.of(7, 0);
			case GENERAL -> LocalTime.of(9, 30);
			case EVENING -> LocalTime.of(14, 0);
			case NIGHT -> LocalTime.of(22, 0);
			case OFF -> null;
		};
	}

	private LocalTime defaultEndTime(ShiftType type) {
		return switch (type) {
			case MORNING -> LocalTime.of(15, 0);
			case GENERAL -> LocalTime.of(18, 0);
			case EVENING -> LocalTime.of(22, 0);
			case NIGHT -> LocalTime.of(7, 0);
			case OFF -> null;
		};
	}

	// -------------------------------------------------------------------------
	// VALIDATION PASS (INVARIANT ENFORCEMENT)
	// -------------------------------------------------------------------------

	public void validateGeneratedRoster(RosterCycle cycle, List<RosterAssignment> assignments, Map<ShiftType, Shift> shifts, int maxNightsAllowed) {
		Map<Long, List<RosterAssignment>> empAssignments = new HashMap<>();
		for (RosterAssignment a : assignments) {
			empAssignments.computeIfAbsent(a.getEmployee().getId(), k -> new ArrayList<>()).add(a);
		}

		for (Map.Entry<Long, List<RosterAssignment>> entry : empAssignments.entrySet()) {
			List<RosterAssignment> list = entry.getValue();
			list.sort(Comparator.comparing(RosterAssignment::getRosterDate));

			int nightCount = 0;
			int offCount = 0;
			int leaveCount = 0;
			RosterAssignment prevWorking = null;

			// Check rest from previous cycle
			if (!list.isEmpty()) {
				LocalDate firstDate = list.get(0).getRosterDate();
				List<RosterAssignment> before = assignmentRepository.findWorkedAssignmentsBefore(entry.getKey(), firstDate);
				if (!before.isEmpty()) {
					prevWorking = before.get(0);
				}
			}

			for (RosterAssignment curr : list) {
				Employee emp = curr.getEmployee();

				if (curr.isWeeklyOff()) offCount++;
				if (curr.isOnLeave()) leaveCount++;

				// Gender Constraint
				if (emp.getGender() == Gender.FEMALE) {
					if (curr.getShift().getShiftType() == ShiftType.EVENING || curr.getShift().getShiftType() == ShiftType.NIGHT) {
						throw new BusinessException("Validation failure: Female employee " + emp.getEmployeeCode()
								+ " assigned to " + curr.getShift().getShiftType() + " on " + curr.getRosterDate());
					}
				}

				if (!curr.isWeeklyOff() && !curr.isOnLeave() && curr.getShift().getShiftType() != ShiftType.OFF) {
					if (curr.getShift().getShiftType() == ShiftType.NIGHT) {
						nightCount++;
					}

					// Rest Constraint
					if (prevWorking != null) {
						if (!hasMinimumRest(prevWorking.getRosterDate(), prevWorking.getShift(), curr.getRosterDate(), curr.getShift())) {
							Duration rest = calculateRestDuration(prevWorking.getRosterDate(), prevWorking.getShift(), curr.getRosterDate(), curr.getShift());
							throw new BusinessException("Validation failure: Insufficient rest (" + rest.toHours() + "h " + (rest.toMinutes() % 60)
									+ "m < " + MIN_REST_HOURS + "h) for employee " + emp.getEmployeeCode()
									+ " between " + prevWorking.getRosterDate() + " " + prevWorking.getShift().getShiftType()
									+ " and " + curr.getRosterDate() + " " + curr.getShift().getShiftType());
						}
					}
					prevWorking = curr;
				}
			}

			// Max Night Count Constraint
			if (nightCount > maxNightsAllowed) {
				throw new BusinessException("Validation failure: Employee " + entry.getKey()
						+ " assigned " + nightCount + " night shifts in cycle (max allowed is " + maxNightsAllowed + ")");
			}

			// Exactly 1 Weekly OFF Constraint (unless on leave all 7 days)
			if (offCount != 1 && leaveCount < 7) {
				Employee emp = list.get(0).getEmployee();
				throw new BusinessException("Validation failure: Employee " + emp.getEmployeeCode()
						+ " has " + offCount + " weekly OFF assignments in cycle (expected exactly 1)");
			}
		}
	}

	private CoverageReportResponse calculateCoverageReport(RosterCycle cycle, List<RosterAssignment> assignments) {
		Map<ShiftType, Shift> shifts = activeShiftMap();
		Map<LocalDate, Map<ShiftType, Integer>> countsByDate = new HashMap<>();

		for (RosterAssignment a : assignments) {
			if (!a.isWeeklyOff() && !a.isOnLeave() && a.getShift().getShiftType() != ShiftType.OFF) {
				countsByDate.computeIfAbsent(a.getRosterDate(), d -> new EnumMap<>(ShiftType.class))
						.compute(a.getShift().getShiftType(), (k, v) -> v == null ? 1 : v + 1);
			}
		}

		List<DailyCoverageReport> dailyReports = new ArrayList<>();
		int totalConfiguredDemand = 0;
		int totalWorkforceCapacity = 0;
		int totalFeasibleCapacity = 0;
		int totalAssigned = 0;
		int totalOperationalShortage = 0;
		List<String> warnings = new ArrayList<>();

		for (int offset = 0; offset < 7; offset++) {
			LocalDate date = cycle.getStartDate().plusDays(offset);
			Map<ShiftType, Integer> dayCounts = countsByDate.getOrDefault(date, Map.of());
			int dayAssigned = dayCounts.values().stream().mapToInt(Integer::intValue).sum();

			// Calculate planned working staff for this date
			long offOrLeaveOnDate = assignments.stream()
					.filter(a -> a.getRosterDate().equals(date) && (a.isWeeklyOff() || a.isOnLeave() || a.getShift().getShiftType() == ShiftType.OFF))
					.count();
			int plannedWorkingStaff = (int) (assignments.stream().filter(a -> a.getRosterDate().equals(date)).count() - offOrLeaveOnDate);

			Map<ShiftType, Integer> dayConfiguredDemands = new EnumMap<>(ShiftType.class);
			for (ShiftType type : ASSIGNMENT_ORDER) {
				int cap = Math.max(1, shifts.get(type).getCapacity());
				if (type == ShiftType.NIGHT) cap = 1;
				dayConfiguredDemands.put(type, cap);
			}

			// Calculate realistic feasible daily demand tailored for available staff
			Map<ShiftType, Integer> dayFeasibleDemands = new EnumMap<>(ShiftType.class);
			int avail = Math.max(0, plannedWorkingStaff);
			dayFeasibleDemands.put(ShiftType.NIGHT, avail >= 1 ? 1 : 0);
			dayFeasibleDemands.put(ShiftType.EVENING, avail >= 2 ? 1 : 0);
			dayFeasibleDemands.put(ShiftType.MORNING, avail >= 3 ? 1 : 0);
			dayFeasibleDemands.put(ShiftType.GENERAL, avail >= 4 ? 1 : 0);
			int rem = Math.max(0, avail - dayFeasibleDemands.values().stream().mapToInt(Integer::intValue).sum());
			while (rem > 0) {
				int mCur = dayFeasibleDemands.get(ShiftType.MORNING);
				int gCur = dayFeasibleDemands.get(ShiftType.GENERAL);
				int mTarget = dayConfiguredDemands.getOrDefault(ShiftType.MORNING, 2);
				int gTarget = dayConfiguredDemands.getOrDefault(ShiftType.GENERAL, 2);
				if (mCur < mTarget) {
					dayFeasibleDemands.put(ShiftType.MORNING, mCur + 1);
					rem--;
				} else {
					dayFeasibleDemands.put(ShiftType.GENERAL, gCur + 1);
					rem--;
				}
			}

			List<ShiftCoverageSummary> summaries = new ArrayList<>();
			int dayConfigured = 0;
			int dayFeasible = 0;
			int dayOpShortage = 0;

			for (ShiftType type : ASSIGNMENT_ORDER) {
				int configured = dayConfiguredDemands.getOrDefault(type, 1);
				int feasible = dayFeasibleDemands.getOrDefault(type, 0);
				int actual = dayCounts.getOrDefault(type, 0);
				int opShortage = Math.max(0, feasible - actual);

				dayConfigured += configured;
				dayFeasible += feasible;
				dayOpShortage += opShortage;

				String status;
				String reason = null;
				if (opShortage > 0) {
					status = "SHORTAGE";
					reason = "Feasible capacity target not met due to rest/availability rules";
				} else if (actual < configured) {
					status = (type == ShiftType.NIGHT || type == ShiftType.EVENING)
							? "Workforce/eligibility-limited"
							: "Workforce-limited";
					reason = "Configured demand (" + configured + ") dynamically adapted to workforce capacity (" + feasible + ")";
				} else {
					status = "FULL";
					reason = "✓ Fully staffed within safety rules";
				}

				summaries.add(new ShiftCoverageSummary(type, configured, feasible, actual, opShortage, status, reason));
			}

			dailyReports.add(new DailyCoverageReport(date, 7, plannedWorkingStaff, (int) offOrLeaveOnDate, dayConfigured, dayFeasible, dayAssigned, dayOpShortage, summaries));
			totalConfiguredDemand += dayConfigured;
			totalWorkforceCapacity += plannedWorkingStaff;
			totalFeasibleCapacity += dayFeasible;
			totalAssigned += dayAssigned;
			totalOperationalShortage += dayOpShortage;
		}

		int totalConfiguredShortage = Math.max(0, totalConfiguredDemand - totalAssigned);
		if (totalConfiguredShortage > 0 && totalOperationalShortage == 0) {
			warnings.add("Configured demand (" + totalConfiguredDemand + ") exceeds active workforce capacity ("
					+ totalWorkforceCapacity + "). All required shift types are fully covered.");
		}

		return new CoverageReportResponse(totalConfiguredDemand, totalWorkforceCapacity, totalFeasibleCapacity,
				totalAssigned, totalOperationalShortage, totalConfiguredShortage, dailyReports, warnings);
	}

	private boolean isEligible(Employee employee, ShiftType shiftType) {
		return employee.getGender() == Gender.MALE || shiftType == ShiftType.MORNING || shiftType == ShiftType.GENERAL;
	}

	private ShiftType nextShift(Employee employee, ShiftType previous) {
		List<ShiftType> rotation = employee.getGender() == Gender.FEMALE ? FEMALE_ROTATION : MALE_ROTATION;
		int index = rotation.indexOf(previous);
		return index < 0 ? rotation.get(0) : rotation.get((index + 1) % rotation.size());
	}

	private int weekendOffCount(Employee employee) {
		return (int) assignmentRepository.findTop30ByEmployeeIdOrderByRosterDateDesc(employee.getId()).stream()
				.filter(RosterAssignment::isWeeklyOff).filter(assignment -> isWeekend(assignment.getRosterDate()))
				.count();
	}

	private int weekendWorkCount(Employee employee) {
		return (int) assignmentRepository.findTop30ByEmployeeIdOrderByRosterDateDesc(employee.getId()).stream()
				.filter(assignment -> isWeekend(assignment.getRosterDate()))
				.filter(assignment -> !assignment.isWeeklyOff() && !assignment.isOnLeave()).count();
	}

	private boolean isApprovedLeave(Long employeeId, LocalDate date) {
		return leaveRepository.existsByEmployeeIdAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
				employeeId, LeaveStatus.APPROVED, date, date);
	}

	private boolean isWeekend(LocalDate date) {
		return date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
	}

	private RosterAssignment newAssignment(RosterCycle cycle, Employee employee, Shift shift, LocalDate date,
			boolean weeklyOff, boolean onLeave) {
		RosterAssignment assignment = new RosterAssignment();
		assignment.setCycle(cycle);
		assignment.setEmployee(employee);
		assignment.setShift(shift);
		assignment.setRosterDate(date);
		assignment.setWeeklyOff(weeklyOff);
		assignment.setOnLeave(onLeave);
		return assignment;
	}

	private RosterAssignmentResponse applyOverride(Long assignmentId, ShiftType shiftType, boolean weeklyOff,
			String reason) {
		RosterAssignment assignment = assignmentRepository.findById(assignmentId)
				.orElseThrow(() -> new ResourceNotFoundException("Roster assignment not found"));

		if (assignment.getCycle() != null && assignment.getCycle().getStatus() == RosterStatus.LOCKED) {
			throw new BusinessException("Roster " + assignment.getCycle().getStartDate() + " to "
					+ assignment.getCycle().getEndDate() + " is locked and cannot be modified. Changes require an authorized unlock action.");
		}

		if (!weeklyOff && !isEligible(assignment.getEmployee(), shiftType)) {
			throw new BusinessException("Selected employee is not eligible for " + shiftType);
		}
		Shift shift = shiftRepository.findByShiftType(shiftType)
				.orElseThrow(() -> new ResourceNotFoundException("Shift not found"));

		ShiftType previous = assignment.getShift().getShiftType();
		if ((weeklyOff || shiftType != previous) && previous != ShiftType.OFF && !assignment.isWeeklyOff()
				&& !assignment.isOnLeave()) {
			long remaining = assignmentRepository.countByRosterDateAndShiftShiftTypeAndWeeklyOffFalseAndOnLeaveFalse(
					assignment.getRosterDate(), previous) - 1;
			if (remaining < 1) {
				throw new BusinessException(
						"Override would leave " + previous + " shift empty on " + assignment.getRosterDate());
			}
		}
		assignment.setShift(shift);
		assignment.setWeeklyOff(weeklyOff);
		assignment.setOnLeave(false);
		assignment.setOverridden(true);

		RosterOverride override = new RosterOverride();
		override.setAssignment(assignment);
		override.setPreviousShiftType(previous);
		override.setNewShiftType(shiftType);
		override.setWeeklyOff(weeklyOff);
		override.setReason(reason);
		override.setCreatedAt(LocalDateTime.now());
		overrideRepository.save(override);

		if (auditService != null) {
			String empName = assignment.getEmployee() != null ? assignment.getEmployee().getFirstName() + " " + assignment.getEmployee().getLastName() : "Unknown";
			auditService.log(AuditAction.SHIFT_OVERRIDDEN, "ROSTER_ASSIGNMENT", assignment.getId(),
					assignment.getCycle() != null ? assignment.getCycle().getId() : null,
					assignment.getEmployee() != null ? assignment.getEmployee().getId() : null,
					empName,
					previous != null ? previous.name() : "NONE",
					weeklyOff ? "OFF" : shiftType.name(),
					reason != null ? reason : "Admin override",
					"MANUAL");
		}

		if (notificationService != null && assignment.getEmployee() != null) {
			notificationService.notifyEmployee(
					assignment.getEmployee(),
					"Shift Changed",
					"Your shift on " + assignment.getRosterDate() + " was changed from " + previous + " to " + (weeklyOff ? "OFF" : shiftType) + (reason != null ? " (" + reason + ")" : ""),
					NotificationType.SHIFT_CHANGED,
					"employeeWorkspace",
					assignment.getCycle() != null ? assignment.getCycle().getId() : null);
		}

		if (rosterVersionService != null && assignment.getCycle() != null) {
			String actor = SecurityContextHolder.getContext().getAuthentication() != null
					? SecurityContextHolder.getContext().getAuthentication().getName()
					: "admin";
			rosterVersionService.recordVersionSnapshot(
					assignment.getCycle(),
					"OVERRIDE_APPLIED",
					"Override on " + assignment.getRosterDate() + " for " + (assignment.getEmployee() != null ? assignment.getEmployee().getEmployeeCode() : "employee") + ": " + (weeklyOff ? "OFF" : shiftType.name()) + (reason != null ? " (" + reason + ")" : ""),
					actor);
		}

		return toAssignmentResponse(assignment);
	}

	private Map<ShiftType, Shift> activeShiftMap() {
		Map<ShiftType, Shift> shifts = new EnumMap<>(ShiftType.class);
		shiftRepository.findByActiveTrueOrderByIdAsc().forEach(shift -> shifts.put(shift.getShiftType(), shift));
		for (ShiftType type : ShiftType.values()) {
			if (!shifts.containsKey(type)) {
				throw new BusinessException("Missing active shift configuration for " + type);
			}
		}
		return shifts;
	}

	public byte[] exportExcel(Long cycleId) {
		RosterCycleResponse cycle = cycle(cycleId);
		List<Shift> shifts = shiftRepository.findByActiveTrueOrderByIdAsc();
		try {
			return com.weeklyroster.export.RosterExcelExporter.exportToExcel(cycle, shifts);
		} catch (Exception e) {
			throw new BusinessException("Failed to generate Excel export: " + e.getMessage());
		}
	}

	public byte[] exportImage(Long cycleId) {
		RosterCycleResponse cycle = cycle(cycleId);
		List<Shift> shifts = shiftRepository.findByActiveTrueOrderByIdAsc();
		try {
			return com.weeklyroster.export.RosterImageExporter.exportToImage(cycle, shifts);
		} catch (Exception e) {
			throw new BusinessException("Failed to generate Image export: " + e.getMessage());
		}
	}

	public Map<String, Object> checkExistingCycle(LocalDate startDate) {
		if (startDate == null) {
			startDate = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
					.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY));
		} else if (startDate.getDayOfWeek() != java.time.DayOfWeek.MONDAY) {
			startDate = startDate.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY));
		}
		LocalDate endDate = startDate.plusDays(6);
		List<RosterCycle> overlapping = cycleRepository.findOverlappingCycles(startDate, endDate);
		Map<String, Object> res = new LinkedHashMap<>();
		if (!overlapping.isEmpty()) {
			RosterCycle existing = overlapping.get(0);
			res.put("exists", true);
			res.put("cycleId", existing.getId());
			res.put("startDate", existing.getStartDate().toString());
			res.put("endDate", existing.getEndDate().toString());
			res.put("mode", existing.getGenerationMode() != null ? existing.getGenerationMode().name() : "MANUAL");
			res.put("message", "A roster already exists for " + existing.getStartDate() + " to " + existing.getEndDate());
		} else {
			res.put("exists", false);
			res.put("startDate", startDate.toString());
			res.put("endDate", endDate.toString());
		}
		return res;
	}

	private RosterCycleResponse toCycleResponse(RosterCycle cycle, List<RosterAssignment> assignments, CoverageReportResponse coverageReport) {
		return new RosterCycleResponse(
				cycle.getId(),
				cycle.getStartDate(),
				cycle.getEndDate(),
				cycle.getGeneratedAt(),
				cycle.getGenerationMode() != null ? cycle.getGenerationMode() : com.weeklyroster.entity.GenerationMode.MANUAL,
				cycle.getStatus() != null ? cycle.getStatus() : RosterStatus.GENERATED,
				cycle.getPublishedAt(),
				cycle.getPublishedBy(),
				cycle.getLockedAt(),
				cycle.getLockedBy(),
				cycle.getUnlockedAt(),
				cycle.getUnlockedBy(),
				cycle.getUnlockReason(),
				"SENT",
				assignments.stream().sorted(Comparator.comparing(RosterAssignment::getRosterDate)
						.thenComparing(a -> a.getEmployee().getId())).map(this::toAssignmentResponse).toList(),
				coverageReport);
	}

	private RosterAssignmentResponse toAssignmentResponse(RosterAssignment assignment) {
		Employee employee = assignment.getEmployee();
		Long cycleId = assignment.getCycle() == null ? null : assignment.getCycle().getId();
		return new RosterAssignmentResponse(assignment.getId(), cycleId,
				assignment.getRosterDate(), employee.getId(), employee.getEmployeeCode(),
				employee.getFirstName() + " " + employee.getLastName(), employee.getGender(),
				assignment.getShift().getShiftType(), assignment.isWeeklyOff(), assignment.isOnLeave(),
				assignment.isOverridden());
	}

	// -------------------------------------------------------------------------
	// AUTHORITATIVE EFFECTIVE DUTY RESOLUTION ENGINE
	// -------------------------------------------------------------------------

	@Transactional(readOnly = true)
	public TodayDutyResponse getMyTodayDuty() {
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()) {
			throw new AccessDeniedException("Authentication required to access duty schedule");
		}
		String username = authentication.getName();
		Employee employee = employeeRepository.findByUserUsername(username)
				.orElseThrow(() -> new ResourceNotFoundException("Employee profile not found for user: " + username));

		LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
		LocalDateTime now = LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata"));
		return getTodayEffectiveDuty(employee.getId(), today, now);
	}

	@Transactional(readOnly = true)
	public TodayDutyResponse getTodayEffectiveDuty(Long employeeId, LocalDate queryDate, LocalDateTime currentDateTime) {
		if (queryDate == null) {
			queryDate = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
		}
		if (currentDateTime == null) {
			currentDateTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata"));
		}

		Employee employee = employeeRepository.findById(employeeId)
				.orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + employeeId));

		// Check security authorization
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null) {
			boolean isAdmin = authentication.getAuthorities().stream()
					.anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
			if (!isAdmin) {
				Employee currentEmployee = employeeRepository.findByUserUsername(authentication.getName()).orElse(null);
				if (currentEmployee == null || !currentEmployee.getId().equals(employeeId)) {
					throw new AccessDeniedException("Access denied: You can only view your own duty schedule");
				}
			}
		}

		Map<ShiftType, Shift> shifts = activeShiftMap();
		int activeWorkforceCount = (int) employeeRepository.countByActiveTrue();

		// Check Night shift special case (crossing midnight from yesterday):
		// If yesterday had an active Night shift, and current time is still within that shift:
		LocalDate yesterday = queryDate.minusDays(1);
		DutyResolution yesterdayResolution = resolveDutyForDate(employee, yesterday, shifts);
		if (yesterdayResolution.shiftType == ShiftType.NIGHT && "WORKING".equals(yesterdayResolution.status)) {
			Shift nightShift = shifts.get(ShiftType.NIGHT);
			LocalDateTime yStart = calculateShiftStartDateTime(yesterday, nightShift);
			LocalDateTime yEnd = calculateShiftEndDateTime(yesterday, nightShift);
			if (yStart != null && yEnd != null && !currentDateTime.isBefore(yStart) && currentDateTime.isBefore(yEnd)) {
				// The employee is currently working yesterday's Night shift!
				String dynamicStatus = "Currently on duty (Ends at " + (nightShift.getEndTime() != null ? nightShift.getEndTime() : "07:00") + ")";
				DutySummaryDto prevSummary = toDutySummaryDto(yesterday.minusDays(1), resolveDutyForDate(employee, yesterday.minusDays(1), shifts));
				DutySummaryDto nextSummary = toDutySummaryDto(queryDate, resolveDutyForDate(employee, queryDate, shifts));

				return new TodayDutyResponse(
						queryDate,
						employee.getId(),
						employee.getEmployeeCode(),
						employee.getFirstName() + " " + employee.getLastName(),
						"WORKING",
						ShiftType.NIGHT,
						"Night Shift",
						nightShift.getStartTime() != null ? nightShift.getStartTime() : LocalTime.of(22, 0),
						nightShift.getEndTime() != null ? nightShift.getEndTime() : LocalTime.of(7, 0),
						true,
						yStart.toString(),
						yEnd.toString(),
						null,
						null,
						yesterdayResolution.source,
						"12h Min Rest Protected",
						dynamicStatus,
						prevSummary,
						nextSummary,
						activeWorkforceCount
				);
			}
		}

		// Resolve today's effective duty
		DutyResolution todayResolution = resolveDutyForDate(employee, queryDate, shifts);

		// Calculate dynamic status text for today
		String dynamicStatus = calculateDynamicStatusText(todayResolution, queryDate, currentDateTime);

		// Compute Previous Duty (yesterday)
		DutyResolution prevRes = resolveDutyForDate(employee, queryDate.minusDays(1), shifts);
		DutySummaryDto prevSummary = toDutySummaryDto(queryDate.minusDays(1), prevRes);

		// Compute Next Duty (tomorrow)
		DutyResolution nextRes = resolveDutyForDate(employee, queryDate.plusDays(1), shifts);
		DutySummaryDto nextSummary = toDutySummaryDto(queryDate.plusDays(1), nextRes);

		return new TodayDutyResponse(
				queryDate,
				employee.getId(),
				employee.getEmployeeCode(),
				employee.getFirstName() + " " + employee.getLastName(),
				todayResolution.status,
				todayResolution.shiftType,
				todayResolution.shiftName,
				todayResolution.startTime,
				todayResolution.endTime,
				todayResolution.overnight,
				todayResolution.startDateTime != null ? todayResolution.startDateTime.toString() : null,
				todayResolution.endDateTime != null ? todayResolution.endDateTime.toString() : null,
				todayResolution.leaveType,
				todayResolution.leaveReason,
				todayResolution.source,
				"12h Min Rest Protected",
				dynamicStatus,
				prevSummary,
				nextSummary,
				activeWorkforceCount
		);
	}

	private static class DutyResolution {
		String status = "NO_ASSIGNMENT"; // "WORKING", "OFF", "LEAVE", "NO_ASSIGNMENT"
		ShiftType shiftType;
		String shiftName = "Standby / Not Assigned";
		LocalTime startTime;
		LocalTime endTime;
		boolean overnight;
		LocalDateTime startDateTime;
		LocalDateTime endDateTime;
		String leaveType;
		String leaveReason;
		String source = "NONE"; // "LEAVE", "OVERRIDE", "ROSTER_ASSIGNMENT", "NONE"
	}

	private DutyResolution resolveDutyForDate(Employee employee, LocalDate date, Map<ShiftType, Shift> shifts) {
		DutyResolution res = new DutyResolution();

		// 1. Authoritative check: Approved Leave takes highest precedence
		List<com.weeklyroster.entity.LeaveRequest> approvedLeaves = leaveRepository
				.findByEmployeeIdAndStatusOrderByIdDesc(employee.getId(), LeaveStatus.APPROVED).stream()
				.filter(l -> !date.isBefore(l.getStartDate()) && !date.isAfter(l.getEndDate()))
				.toList();

		if (!approvedLeaves.isEmpty()) {
			com.weeklyroster.entity.LeaveRequest leave = approvedLeaves.get(0);
			res.status = "LEAVE";
			res.source = "LEAVE";
			res.leaveType = "Approved Leave";
			res.leaveReason = leave.getReason() != null ? leave.getReason() : "Annual / Approved Leave";
			res.shiftType = null;
			res.shiftName = "On Leave";
			return res;
		}

		// 2. Check Roster Assignment & Override for the date
		List<RosterAssignment> assignments = assignmentRepository.findByEmployeeIdAndRosterDate(employee.getId(), date);
		if (assignments.isEmpty()) {
			res.status = "NO_ASSIGNMENT";
			res.source = "NONE";
			res.shiftName = "Standby / Not Assigned";
			return res;
		}

		RosterAssignment assignment = assignments.get(0);

		// Check if an active override exists for this assignment
		List<RosterOverride> overrides = overrideRepository.findByAssignmentIdOrderByCreatedAtDesc(assignment.getId());
		if (!overrides.isEmpty()) {
			RosterOverride override = overrides.get(0);
			if (override.isWeeklyOff()) {
				res.status = "OFF";
				res.source = "OVERRIDE";
				res.shiftType = ShiftType.OFF;
				res.shiftName = "Weekly Rest Day (Overridden)";
				return res;
			}
			ShiftType newType = override.getNewShiftType();
			if (newType != null && newType != ShiftType.OFF) {
				Shift shift = shifts.get(newType);
				res.status = "WORKING";
				res.source = "OVERRIDE";
				res.shiftType = newType;
				res.shiftName = getShiftDisplayName(newType);
				res.startTime = shift.getStartTime() != null ? shift.getStartTime() : defaultStartTime(newType);
				res.endTime = shift.getEndTime() != null ? shift.getEndTime() : defaultEndTime(newType);
				res.overnight = shift.isOvernight() || (res.endTime != null && res.startTime != null && res.endTime.isBefore(res.startTime));
				res.startDateTime = calculateShiftStartDateTime(date, shift);
				res.endDateTime = calculateShiftEndDateTime(date, shift);
				return res;
			}
		}

		// 3. Evaluate Roster Assignment
		if (assignment.isOnLeave()) {
			res.status = "LEAVE";
			res.source = "LEAVE";
			res.leaveType = "Approved Leave";
			res.leaveReason = "Scheduled Absence";
			res.shiftName = "On Leave";
			return res;
		}

		if (assignment.isWeeklyOff() || assignment.getShift() == null || assignment.getShift().getShiftType() == ShiftType.OFF) {
			res.status = "OFF";
			res.source = "ROSTER_ASSIGNMENT";
			res.shiftType = ShiftType.OFF;
			res.shiftName = "Weekly Rest Day";
			return res;
		}

		Shift shift = assignment.getShift();
		ShiftType type = shift.getShiftType();
		res.status = "WORKING";
		res.source = "ROSTER_ASSIGNMENT";
		res.shiftType = type;
		res.shiftName = getShiftDisplayName(type);
		res.startTime = shift.getStartTime() != null ? shift.getStartTime() : defaultStartTime(type);
		res.endTime = shift.getEndTime() != null ? shift.getEndTime() : defaultEndTime(type);
		res.overnight = shift.isOvernight() || (res.endTime != null && res.startTime != null && res.endTime.isBefore(res.startTime));
		res.startDateTime = calculateShiftStartDateTime(date, shift);
		res.endDateTime = calculateShiftEndDateTime(date, shift);
		return res;
	}

	private String calculateDynamicStatusText(DutyResolution res, LocalDate date, LocalDateTime currentDateTime) {
		if (res == null) return "Standby / Not Assigned";
		if ("LEAVE".equals(res.status)) return "On approved leave.";
		if ("OFF".equals(res.status)) return "Today is your weekly OFF.";
		if ("NO_ASSIGNMENT".equals(res.status)) return "Standby / Not Assigned";

		if ("WORKING".equals(res.status)) {
			if (res.startDateTime == null || res.endDateTime == null) {
				return "Currently scheduled on duty";
			}
			if (currentDateTime.isBefore(res.startDateTime)) {
				Duration duration = Duration.between(currentDateTime, res.startDateTime);
				long hours = duration.toHours();
				long minutes = duration.toMinutesPart();
				if (hours > 0) {
					return "Starts in " + hours + "h " + minutes + "m";
				} else {
					return "Starts in " + Math.max(1, minutes) + "m";
				}
			} else if (currentDateTime.isAfter(res.endDateTime)) {
				return "Shift completed";
			} else {
				return "Currently on duty";
			}
		}
		return "Standby / Not Assigned";
	}

	private DutySummaryDto toDutySummaryDto(LocalDate date, DutyResolution res) {
		String dayOfWeek = date.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH);
		return new DutySummaryDto(
				date,
				dayOfWeek,
				res.status,
				res.shiftType,
				res.shiftName,
				res.startTime,
				res.endTime,
				res.overnight,
				res.startDateTime != null ? res.startDateTime.toString() : null,
				res.endDateTime != null ? res.endDateTime.toString() : null,
				res.source
		);
	}

	private String getShiftDisplayName(ShiftType type) {
		if (type == null) return "Off";
		return switch (type) {
			case MORNING -> "Morning Shift";
			case GENERAL -> "General Shift";
			case EVENING -> "Evening Shift";
			case NIGHT -> "Night Shift";
			case OFF -> "Weekly Off";
		};
	}
}
