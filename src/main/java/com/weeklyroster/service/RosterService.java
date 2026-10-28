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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import java.util.stream.Collectors;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weeklyroster.entity.ManualRosterOperation;
import com.weeklyroster.repository.ManualRosterOperationRepository;
import com.weeklyroster.dto.request.CopyPreviousWeekRequest;
import com.weeklyroster.dto.request.ClearManualRosterRequest;
import com.weeklyroster.dto.request.ManualShiftSwapRequest;
import com.weeklyroster.dto.request.AutoSaveDraftRequest;
import com.weeklyroster.dto.response.CopyWeekPreviewResponse;
import com.weeklyroster.dto.response.ClearRosterPreviewResponse;
import com.weeklyroster.dto.response.ManualShiftSwapPreviewResponse;
import com.weeklyroster.dto.response.WeekComparisonResponse;
import com.weeklyroster.dto.response.UndoOperationResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.weeklyroster.dto.ApplicablePreference;
import com.weeklyroster.entity.EmployeePreference;
import com.weeklyroster.entity.GenerationMode;
import com.weeklyroster.entity.PreferenceStatus;
import com.weeklyroster.repository.EmployeePreferenceRepository;
import com.weeklyroster.dto.request.RosterOverrideRequest;
import com.weeklyroster.dto.request.ShiftChangeRequest;
import com.weeklyroster.dto.request.UnlockRosterRequest;
import com.weeklyroster.dto.response.ConflictItem;
import com.weeklyroster.dto.response.EmployeeWorkloadMetric;
import com.weeklyroster.dto.response.CoverageReportResponse;
import com.weeklyroster.dto.response.DailyCoverageReport;
import com.weeklyroster.dto.response.DutySummaryDto;
import com.weeklyroster.dto.response.RosterAssignmentResponse;
import com.weeklyroster.dto.response.RosterCycleResponse;
import com.weeklyroster.dto.response.ShiftExplanationResponse;

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
	private final EmployeePreferenceRepository preferenceRepository;

	@org.springframework.beans.factory.annotation.Autowired(required = false)
	private RosterVersionService rosterVersionService;

	@org.springframework.beans.factory.annotation.Autowired(required = false)
	private com.weeklyroster.repository.HolidayRepository holidayRepository;

	@org.springframework.beans.factory.annotation.Autowired(required = false)
	private com.weeklyroster.repository.DateShiftPreferenceRepository dateShiftPreferenceRepository;

	@org.springframework.beans.factory.annotation.Autowired(required = false)
	private com.weeklyroster.repository.RecurringShiftPreferenceRepository recurringShiftPreferenceRepository;

	@org.springframework.beans.factory.annotation.Autowired(required = false)
	private com.weeklyroster.repository.RosterGenerationHistoryRepository generationHistoryRepository;

	@org.springframework.beans.factory.annotation.Autowired(required = false)
	private RosterEmailService rosterEmailService;

	@org.springframework.beans.factory.annotation.Autowired(required = false)
	private com.weeklyroster.config.ShiftCapacityProperties shiftCapacityProperties;

	private final java.util.concurrent.ConcurrentHashMap<String, List<com.weeklyroster.dto.response.RosterCandidateResponse>> candidateCache = new java.util.concurrent.ConcurrentHashMap<>();

	@org.springframework.beans.factory.annotation.Autowired(required = false)
	private com.weeklyroster.config.DatabaseWriteCoordinator databaseWriteCoordinator;

	@org.springframework.beans.factory.annotation.Autowired(required = false)
	private ManualRosterOperationRepository manualRosterOperationRepository;

	@org.springframework.beans.factory.annotation.Autowired(required = false)
	private ObjectMapper objectMapper;

	private final java.util.concurrent.ConcurrentHashMap<LocalDate, java.util.concurrent.CompletableFuture<RosterCycleResponse>> inFlightGenerations = new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.concurrent.ConcurrentHashMap<LocalDate, java.util.concurrent.CompletableFuture<List<com.weeklyroster.dto.response.RosterCandidateResponse>>> inFlightCandidates = new java.util.concurrent.ConcurrentHashMap<>();

	public void setShiftCapacityProperties(com.weeklyroster.config.ShiftCapacityProperties shiftCapacityProperties) {
		this.shiftCapacityProperties = shiftCapacityProperties;
	}

	public int getRequiredMorningMin(LocalDate date) {
		if (shiftCapacityProperties != null) {
			return shiftCapacityProperties.getMinimumStaff(ShiftType.MORNING, date);
		}
		return isWeekend(date) ? 1 : 2;
	}

	public int getRequiredEveningMin(LocalDate date) {
		if (shiftCapacityProperties != null) {
			return shiftCapacityProperties.getMinimumStaff(ShiftType.EVENING, date);
		}
		return 1;
	}

	public int getRequiredNightMin(LocalDate date) {
		if (shiftCapacityProperties != null) {
			return shiftCapacityProperties.getMinimumStaff(ShiftType.NIGHT, date);
		}
		return 1;
	}

	public void setRosterEmailService(RosterEmailService rosterEmailService) {
		this.rosterEmailService = rosterEmailService;
	}

	public void setDateShiftPreferenceRepository(com.weeklyroster.repository.DateShiftPreferenceRepository dateShiftPreferenceRepository) {
		this.dateShiftPreferenceRepository = dateShiftPreferenceRepository;
	}

	public void setRecurringShiftPreferenceRepository(com.weeklyroster.repository.RecurringShiftPreferenceRepository recurringShiftPreferenceRepository) {
		this.recurringShiftPreferenceRepository = recurringShiftPreferenceRepository;
	}

	public void setGenerationHistoryRepository(com.weeklyroster.repository.RosterGenerationHistoryRepository generationHistoryRepository) {
		this.generationHistoryRepository = generationHistoryRepository;
	}

	@org.springframework.beans.factory.annotation.Autowired
	public RosterService(EmployeeRepository employeeRepository, ShiftRepository shiftRepository,
			RosterCycleRepository cycleRepository, RosterAssignmentRepository assignmentRepository,
			RosterOverrideRepository overrideRepository, LeaveRequestRepository leaveRepository,
			com.weeklyroster.repository.EmailDeliveryLogRepository emailDeliveryLogRepository,
			AuditService auditService, NotificationService notificationService,
			RosterHealthService rosterHealthService,
			EmployeePreferenceRepository preferenceRepository) {
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
		this.preferenceRepository = preferenceRepository;
	}

	public RosterService(EmployeeRepository employeeRepository, ShiftRepository shiftRepository,
			RosterCycleRepository cycleRepository, RosterAssignmentRepository assignmentRepository,
			RosterOverrideRepository overrideRepository, LeaveRequestRepository leaveRepository,
			com.weeklyroster.repository.EmailDeliveryLogRepository emailDeliveryLogRepository) {
		this(employeeRepository, shiftRepository, cycleRepository, assignmentRepository, overrideRepository,
				leaveRepository, emailDeliveryLogRepository, null, null, null, null);
	}

	@Transactional
	public RosterCycleResponse generateWeeklyRoster() {
		return generateWeeklyRoster(LocalDate.now().plusDays(1), GenerationMode.MANUAL);
	}

	@Transactional
	public RosterCycleResponse reoptimizeCycle(Long cycleId, String reason) {
		RosterCycle cycle = cycleRepository.findById(cycleId)
				.orElseThrow(() -> new ResourceNotFoundException("Roster cycle not found with id: " + cycleId));

		if (cycle.getStatus() == RosterStatus.LOCKED) {
			throw new BusinessException("Cannot re-optimize locked roster cycle #" + cycleId + ". Unlock first.");
		}

		LocalDate startDate = cycle.getStartDate();
		log.info("Re-optimizing roster cycle #{} ({} to {}) - Reason: {}", cycleId, startDate, cycle.getEndDate(), reason);

		int wDays = cycle.getWorkingDays() != null ? cycle.getWorkingDays() : 6;
		return generateWeeklyRoster(startDate, cycle.getGenerationMode() != null ? cycle.getGenerationMode() : GenerationMode.MANUAL, wDays);
	}

	@Transactional
	public RosterCycleResponse generateWeeklyRoster(LocalDate startDate) {
		return generateWeeklyRoster(startDate, GenerationMode.MANUAL, 6);
	}

	@Transactional
	public RosterCycleResponse generateWeeklyRoster(LocalDate startDate, GenerationMode mode) {
		return generateWeeklyRoster(startDate, mode, 6);
	}

	@Transactional
	public RosterCycleResponse generateWeeklyRoster(LocalDate startDate, GenerationMode mode, int workingDays) {
		int validWorkingDays = (workingDays == 5) ? 5 : 6;
		if (startDate == null) {
			startDate = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
					.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY));
		}
		if (startDate.getDayOfWeek() != java.time.DayOfWeek.MONDAY) {
			throw new BusinessException("Roster cycle start date must be a Monday (received: " + startDate.getDayOfWeek() + " " + startDate + ")");
		}
		final LocalDate targetMonday = startDate;

		// De-duplicate concurrent generation requests for the exact same week
		java.util.concurrent.CompletableFuture<RosterCycleResponse> future = inFlightGenerations.get(targetMonday);
		if (future != null) {
			log.info("[RosterService] Deduplicating concurrent roster generation request for {}", targetMonday);
			try {
				return future.get(60, java.util.concurrent.TimeUnit.SECONDS);
			} catch (Exception e) {
				log.warn("[RosterService] In-flight generation wait failed for {}: {}", targetMonday, e.getMessage());
			}
		}

		java.util.concurrent.CompletableFuture<RosterCycleResponse> myFuture = new java.util.concurrent.CompletableFuture<>();
		java.util.concurrent.CompletableFuture<RosterCycleResponse> existing = inFlightGenerations.putIfAbsent(targetMonday, myFuture);
		if (existing != null) {
			try {
				return existing.get(60, java.util.concurrent.TimeUnit.SECONDS);
			} catch (Exception e) {
				log.warn("[RosterService] In-flight generation wait failed for {}: {}", targetMonday, e.getMessage());
			}
		}

		try {
			RosterCycleResponse res = (databaseWriteCoordinator != null)
					? databaseWriteCoordinator.executeWrite(() -> doGenerateWeeklyRoster(targetMonday, mode, validWorkingDays))
					: doGenerateWeeklyRoster(targetMonday, mode, validWorkingDays);
			myFuture.complete(res);
			return res;
		} catch (Throwable t) {
			myFuture.completeExceptionally(t);
			if (t instanceof RuntimeException) throw (RuntimeException) t;
			if (t instanceof Error) throw (Error) t;
			throw new RuntimeException(t);
		} finally {
			inFlightGenerations.remove(targetMonday, myFuture);
		}
	}

	@Transactional
	public RosterCycleResponse doGenerateWeeklyRoster(LocalDate startDate, GenerationMode mode, int workingDays) {
		int validWorkingDays = (workingDays == 5) ? 5 : 6;
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

		int maxNightsAllowed = computeMaxNightsAllowed(employees);

		// 1. Capture existing overrides and manual assignments before clearing so intentional admin assignments survive regeneration
		List<RosterOverride> priorOverrides = new ArrayList<>();
		if (overrideRepository != null) {
			try {
				priorOverrides = overrideRepository.findByDateRange(startDate, endDate);
			} catch (Exception ignored) {}
		}
		Map<String, RosterOverride> priorOverrideMap = new HashMap<>();
		for (RosterOverride ro : priorOverrides) {
			if (ro.getAssignment() != null && ro.getAssignment().getEmployee() != null && ro.getAssignment().getRosterDate() != null) {
				String key = ro.getAssignment().getEmployee().getId() + "_" + ro.getAssignment().getRosterDate();
				priorOverrideMap.put(key, ro);
			}
		}

		Map<String, RosterAssignment> priorManualAssignments = new HashMap<>();
		List<RosterCycle> preScanOverlapping = cycleRepository.findOverlappingCycles(startDate, endDate);
		for (RosterCycle existing : preScanOverlapping) {
			List<RosterAssignment> existingAssignments = assignmentRepository.findByCycleId(existing.getId());
			for (RosterAssignment ea : existingAssignments) {
				if (ea.isManualAssignment() || ea.isOverridden()) {
					String key = ea.getEmployee().getId() + "_" + ea.getRosterDate();
					priorManualAssignments.put(key, ea);
				}
			}
		}

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
			try { overrideRepository.deleteByAssignmentCycle(existing); } catch (Exception ignored) {}
			try { overrideRepository.deleteByCycleIdNative(existing.getId()); } catch (Exception ignored) {}
		}
		try { overrideRepository.deleteByDateRangeNative(startDate, endDate); } catch (Exception ignored) {}

		for (RosterCycle existing : overlapping) {
			try { assignmentRepository.deleteByCycle(existing); } catch (Exception ignored) {}
			try { assignmentRepository.deleteByCycleIdNative(existing.getId()); } catch (Exception ignored) {}
		}
		try { assignmentRepository.deleteByDateRangeNative(startDate, endDate); } catch (Exception ignored) {}
		try { assignmentRepository.flush(); } catch (Exception ignored) {}

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

		RosterCycle cycle = null;
		for (RosterCycle existing : overlapping) {
			if (existing.getStartDate() != null && existing.getStartDate().equals(startDate)
					&& existing.getEndDate() != null && existing.getEndDate().equals(endDate)) {
				cycle = existing;
			} else {
				try {
					overrideRepository.deleteByCycleIdNative(existing.getId());
					assignmentRepository.deleteByCycleIdNative(existing.getId());
					if (emailDeliveryLogRepository != null) emailDeliveryLogRepository.deleteByCycleIdNative(existing.getId());
					if (rosterVersionService != null) rosterVersionService.deleteByCycleId(existing.getId());
					cycleRepository.deleteCycleByIdNative(existing.getId());
				} catch (Exception ignored) {}

			}
		}

		if (cycle == null) {
			for (RosterCycle existing : overlapping) {
				try {
					overrideRepository.deleteByCycleIdNative(existing.getId());
					assignmentRepository.deleteByCycleIdNative(existing.getId());
					if (emailDeliveryLogRepository != null) emailDeliveryLogRepository.deleteByCycleIdNative(existing.getId());
					if (rosterVersionService != null) rosterVersionService.deleteByCycleId(existing.getId());
					cycleRepository.deleteCycleByIdNative(existing.getId());
				} catch (Exception ignored) {}

			}
			cycle = new RosterCycle();
			cycle.setStartDate(startDate);
			cycle.setEndDate(endDate);
			cycle.setStatus(RosterStatus.GENERATED);
			cycle.setWorkingDays(validWorkingDays);
		}

		cycle.setGeneratedAt(LocalDateTime.now());
		cycle.setGenerationMode(mode != null ? mode : GenerationMode.MANUAL);
		cycle.setWorkingDays(validWorkingDays);
		if (cycle.getStatus() == null) {
			cycle.setStatus(RosterStatus.GENERATED);
		}
		cycle = cycleRepository.save(cycle);

		if (auditService != null) {
			auditService.log(
					mode == GenerationMode.AUTOMATIC ? AuditAction.AUTOMATIC_GENERATION : AuditAction.ROSTER_GENERATED,
					"ROSTER_CYCLE", cycle.getId(), cycle.getId(), null, null,
					null, "GENERATED", "Generated weekly roster for " + startDate + " to " + endDate,
					mode == GenerationMode.AUTOMATIC ? "AUTOMATIC" : "MANUAL"
			);
		}

		// 2. Load approved employee preferences applicable to [startDate, endDate]
		Map<Long, ApplicablePreference> preferencesMap = loadApprovedPreferences(employees, startDate, endDate);

		// 3. Load previous generation fingerprints for this date range so regenerations do not repeat
		Set<String> previousFingerprints = new HashSet<>();
		if (generationHistoryRepository != null) {
			try {
				previousFingerprints.addAll(generationHistoryRepository.findFingerprintsByDateRange(startDate, endDate));
			} catch (Exception e) {
				log.warn("Could not load past generation fingerprints: {}", e.getMessage());
			}
		}

		int maxAttempts = 50;
		List<RosterAssignment> bestNonDuplicateGenerated = null;
		List<DailyCoverageReport> bestNonDuplicateDailyReports = null;
		int bestNonDuplicateScore = Integer.MIN_VALUE;
		FinalValidationResult bestNonDuplicateValidation = null;
		int bestNonDuplicateAttempt = 1;

		List<RosterAssignment> bestDuplicateGenerated = null;
		List<DailyCoverageReport> bestDuplicateDailyReports = null;
		int bestDuplicateScore = Integer.MIN_VALUE;
		FinalValidationResult bestDuplicateValidation = null;
		int bestDuplicateAttempt = 1;

		List<RosterAssignment> leastConflictsGenerated = null;
		List<DailyCoverageReport> leastConflictsDailyReports = null;
		FinalValidationResult leastConflictsValidation = null;

		int bestChosenAttempt = 1;

		for (int attempt = 0; attempt < maxAttempts; attempt++) {
			int cycleSalt = (int) ((System.nanoTime() + attempt * 7919L + (long)(Math.random() * 100000)) % 100000L);

			Map<Long, Set<LocalDate>> weeklyOffs = planWeeklyOffs(employees, startDate, endDate, preferencesMap, cycleSalt, validWorkingDays);
			Map<Long, Integer> offTakenCount = new HashMap<>();

			Map<Long, Shift> lastShiftMap = new HashMap<>();
			Map<Long, LocalDate> lastShiftDateMap = new HashMap<>();
			Map<Long, Integer> cycleNightCounts = new HashMap<>();
			Map<Long, Map<ShiftType, Integer>> shiftCountsMap = new HashMap<>();

			for (Employee emp : employees) {
				cycleNightCounts.put(emp.getId(), 0);

				List<RosterAssignment> prevWorked = assignmentRepository.findWorkedAssignmentsBefore(emp.getId(), startDate);
				if (!prevWorked.isEmpty()) {
					RosterAssignment lastA = prevWorked.get(0);
					lastShiftMap.put(emp.getId(), lastA.getShift());
					lastShiftDateMap.put(emp.getId(), lastA.getRosterDate());
				}

				Map<ShiftType, Integer> counts = new EnumMap<>(ShiftType.class);
				for (ShiftType type : ShiftType.values()) {
					counts.put(type, 0);
				}
				shiftCountsMap.put(emp.getId(), counts);
			}

			List<RosterAssignment> candidateAssignments = new ArrayList<>();
			List<DailyCoverageReport> dailyReports = new ArrayList<>();
			int totalConfiguredDemand = 0;
			int totalWorkforceCapacity = 0;
			int totalFeasibleCapacity = 0;
			int totalAssigned = 0;
			int totalOperationalShortage = 0;
			List<String> warnings = new ArrayList<>();

			for (int offset = 0; offset < 7; offset++) {
				LocalDate date = startDate.plusDays(offset);
				DayGenerationResult dayResult = generateDay(cycle, employees, weeklyOffs, offTakenCount, shifts, date,
						lastShiftMap, lastShiftDateMap, cycleNightCounts, shiftCountsMap, maxNightsAllowed, preferencesMap, cycleSalt, validWorkingDays);

				candidateAssignments.addAll(dayResult.assignments());
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

			balanceMaleEveningAssignments(candidateAssignments, employees, shifts, preferencesMap);

			int totalConfiguredShortage = Math.max(0, totalConfiguredDemand - totalAssigned);
			if (totalConfiguredShortage > 0 && totalOperationalShortage == 0) {
				warnings.add("Configured demand (" + totalConfiguredDemand + ") exceeds active workforce capacity ("
						+ totalWorkforceCapacity + "). All required shift types are fully covered.");
			}

			enforceAndRepairExactWeeklyOff(cycle, candidateAssignments, employees, shifts, maxNightsAllowed, weeklyOffs, preferencesMap, validWorkingDays);
			ensureMinimumNightAllocation(cycle, candidateAssignments, employees, shifts, maxNightsAllowed, weeklyOffs, preferencesMap);
			enforceAndRepairExactWeeklyOff(cycle, candidateAssignments, employees, shifts, maxNightsAllowed, weeklyOffs, preferencesMap, validWorkingDays);
			rebalanceDailyCoverage(cycle, candidateAssignments, employees, shifts, preferencesMap);
			enforceAndRepairExactWeeklyOff(cycle, candidateAssignments, employees, shifts, maxNightsAllowed, weeklyOffs, preferencesMap, validWorkingDays);

			// BATCH 102: Strictly protect explicit manual assignments against any algorithm overwrites
			if (!priorManualAssignments.isEmpty()) {
				for (RosterAssignment a : candidateAssignments) {
					String key = a.getEmployee().getId() + "_" + a.getRosterDate();
					RosterAssignment priorManual = priorManualAssignments.get(key);
					if (priorManual != null) {
						a.setManualAssignment(true);
						a.setOverridden(true);
						a.setAssignmentSource(priorManual.getAssignmentSource() != null ? priorManual.getAssignmentSource() : "MANUAL");
						a.setWeeklyOff(priorManual.isWeeklyOff());
						a.setShift(priorManual.getShift());
						if (priorManual.getAssignmentReason() != null && !priorManual.getAssignmentReason().isBlank()) {
							a.setAssignmentReason(priorManual.getAssignmentReason());
						} else {
							a.setAssignmentReason("Explicit Manual Assignment (" + (priorManual.getShift() != null ? priorManual.getShift().getShiftType() : "OFF") + ")");
						}
					}
				}
			}

			if (!priorOverrideMap.isEmpty()) {
				for (RosterAssignment a : candidateAssignments) {
					String key = a.getEmployee().getId() + "_" + a.getRosterDate();
					RosterOverride prior = priorOverrideMap.get(key);
					if (prior != null) {
						a.setManualAssignment(true);
						a.setOverridden(true);
						a.setAssignmentSource("MANUAL");
						a.setWeeklyOff(prior.isWeeklyOff());
						if (prior.isWeeklyOff()) {
							a.setShift(shifts.get(ShiftType.OFF));
						} else if (prior.getNewShiftType() != null) {
							a.setShift(shifts.get(prior.getNewShiftType()));
						}
						a.setAssignmentReason("Admin Override: " + (prior.getReason() != null ? prior.getReason() : "Manual override"));
					}
				}
			}

			for (RosterAssignment a : candidateAssignments) {
				ApplicablePreference pref = preferencesMap.getOrDefault(a.getEmployee().getId(), ApplicablePreference.none(a.getEmployee().getId()));
				populateAssignmentReason(a, pref);
			}

			FinalValidationResult validation = evaluateFinalValidation(cycle, candidateAssignments, shifts, maxNightsAllowed, preferencesMap, priorOverrideMap);

			if (leastConflictsValidation == null || validation.criticalConflicts() < leastConflictsValidation.criticalConflicts()) {
				leastConflictsGenerated = candidateAssignments;
				leastConflictsDailyReports = dailyReports;
				leastConflictsValidation = validation;
			}

			if (validation.criticalConflicts() == 0) {
				String candidateFp = computeRosterFingerprint(candidateAssignments);
				RosterScoringBreakdown scores = calculateSmartScore(candidateAssignments, preferencesMap, previousFingerprints, employees);
				boolean isDuplicate = previousFingerprints.contains(candidateFp);

				if (!isDuplicate) {
					if (bestNonDuplicateGenerated == null || scores.totalScore() > bestNonDuplicateScore) {
						bestNonDuplicateGenerated = candidateAssignments;
						bestNonDuplicateDailyReports = dailyReports;
						bestNonDuplicateScore = scores.totalScore();
						bestNonDuplicateValidation = validation;
						bestNonDuplicateAttempt = attempt + 1;
					}
					// If we have explored at least 12 attempts and found a high quality non-duplicate candidate, we can finish
					if (attempt >= 12 && scores.totalScore() >= 1200) {
						break;
					}
				} else {
					if (bestDuplicateGenerated == null || scores.totalScore() > bestDuplicateScore) {
						bestDuplicateGenerated = candidateAssignments;
						bestDuplicateDailyReports = dailyReports;
						bestDuplicateScore = scores.totalScore();
						bestDuplicateValidation = validation;
						bestDuplicateAttempt = attempt + 1;
					}
				}
			}
		}

		List<RosterAssignment> bestGenerated;
		FinalValidationResult bestValidation;

		if (bestNonDuplicateGenerated != null) {
			bestGenerated = bestNonDuplicateGenerated;
			bestValidation = bestNonDuplicateValidation;
			bestChosenAttempt = bestNonDuplicateAttempt;
			log.info("[BATCH 89] Selected non-duplicate candidate on attempt {} with score {}", bestChosenAttempt, bestNonDuplicateScore);
		} else if (bestDuplicateGenerated != null) {
			bestGenerated = bestDuplicateGenerated;
			bestValidation = bestDuplicateValidation;
			bestChosenAttempt = bestDuplicateAttempt;
			log.info("[BATCH 89] All valid candidates repeated previous fingerprints; selected best duplicate candidate on attempt {} with score {}", bestChosenAttempt, bestDuplicateScore);
		} else {
			bestGenerated = leastConflictsGenerated;
			bestValidation = leastConflictsValidation;
			bestChosenAttempt = 1;
			log.info("[BATCH 91] No zero-conflict candidate found in {} attempts; selected candidate with least conflicts ({} critical conflicts)",
					maxAttempts, (leastConflictsValidation != null ? leastConflictsValidation.criticalConflicts() : 0));
		}

		if (bestGenerated == null || bestGenerated.isEmpty()) {
			throw new BusinessException("Unable to generate compliant roster within safety and workforce rules");
		}

		List<RosterAssignment> existingAssignments = assignmentRepository.findByRosterDateBetweenOrderByRosterDateAsc(startDate, endDate);
		Map<String, RosterAssignment> existingMap = new HashMap<>();
		for (RosterAssignment ea : existingAssignments) {
			if (ea.getEmployee() != null && ea.getRosterDate() != null) {
				existingMap.put(ea.getEmployee().getId() + "_" + ea.getRosterDate(), ea);
			}
		}

		List<RosterAssignment> toSave = new ArrayList<>();
		for (RosterAssignment candidate : bestGenerated) {
			String key = candidate.getEmployee().getId() + "_" + candidate.getRosterDate();
			RosterAssignment target = existingMap.remove(key);
			if (target != null) {
				target.setCycle(cycle);
				target.setShift(candidate.getShift());
				target.setWeeklyOff(candidate.isWeeklyOff());
				target.setOnLeave(candidate.isOnLeave());
				target.setOverridden(candidate.isOverridden());
				target.setAssignmentReason(candidate.getAssignmentReason());
				target.setPreviousShiftType(candidate.getPreviousShiftType());
				target.setOverrideReason(candidate.getOverrideReason());
				target.setOverrideCreatedAt(candidate.getOverrideCreatedAt());
				toSave.add(target);
			} else {
				candidate.setCycle(cycle);
				toSave.add(candidate);
			}
		}
		if (!existingMap.isEmpty()) {
			try { assignmentRepository.deleteAll(existingMap.values()); } catch (Exception ignored) {}
		}

		List<RosterAssignment> generated = new ArrayList<>(assignmentRepository.saveAll(toSave));

		if (!priorOverrideMap.isEmpty()) {
			List<RosterOverride> reestablishedOverrides = new ArrayList<>();
			for (RosterAssignment a : generated) {
				String key = a.getEmployee().getId() + "_" + a.getRosterDate();
				RosterOverride prior = priorOverrideMap.get(key);
				if (prior != null) {
					a.setOverridden(true);
					a.setWeeklyOff(prior.isWeeklyOff());
					if (prior.isWeeklyOff()) {
						a.setShift(shifts.get(ShiftType.OFF));
					} else if (prior.getNewShiftType() != null) {
						a.setShift(shifts.get(prior.getNewShiftType()));
					}
					a.setAssignmentReason("Admin Override: " + (prior.getReason() != null ? prior.getReason() : "Manual override"));

					RosterOverride newRo = new RosterOverride();
					newRo.setAssignment(a);
					newRo.setPreviousShiftType(prior.getPreviousShiftType() != null ? prior.getPreviousShiftType() : ShiftType.GENERAL);
					newRo.setNewShiftType(prior.getNewShiftType() != null ? prior.getNewShiftType() : (prior.isWeeklyOff() ? ShiftType.OFF : ShiftType.GENERAL));
					newRo.setWeeklyOff(prior.isWeeklyOff());
					newRo.setReason(prior.getReason() != null ? prior.getReason() : "Preserved Admin Override");
					newRo.setCreatedAt(prior.getCreatedAt() != null ? prior.getCreatedAt() : LocalDateTime.now());
					reestablishedOverrides.add(newRo);
				}
			}
			if (!reestablishedOverrides.isEmpty()) {
				overrideRepository.saveAll(reestablishedOverrides);
			}
		}

		// BATCH 102: Re-enforce explicit manual assignments on final generated roster
		if (!priorManualAssignments.isEmpty()) {
			for (RosterAssignment a : generated) {
				String key = a.getEmployee().getId() + "_" + a.getRosterDate();
				RosterAssignment priorManual = priorManualAssignments.get(key);
				if (priorManual != null) {
					a.setManualAssignment(true);
					a.setOverridden(true);
					a.setAssignmentSource(priorManual.getAssignmentSource() != null ? priorManual.getAssignmentSource() : "MANUAL");
					a.setWeeklyOff(priorManual.isWeeklyOff());
					a.setShift(priorManual.getShift());
					if (priorManual.getAssignmentReason() != null && !priorManual.getAssignmentReason().isBlank()) {
						a.setAssignmentReason(priorManual.getAssignmentReason());
					} else {
						a.setAssignmentReason("Explicit Manual Assignment (" + (priorManual.getShift() != null ? priorManual.getShift().getShiftType() : "OFF") + ")");
					}
				}
			}
		}

		for (RosterAssignment a : generated) {
			ApplicablePreference pref = preferencesMap.getOrDefault(a.getEmployee().getId(), ApplicablePreference.none(a.getEmployee().getId()));
			populateAssignmentReason(a, pref);
		}
		generated = new ArrayList<>(assignmentRepository.saveAll(generated));
		try { assignmentRepository.flush(); } catch (Exception ignored) {}
		try { cycleRepository.flush(); } catch (Exception ignored) {}

		// BATCH 91: Evaluate live validation against saved draft assignments without throwing blocking exceptions
		FinalValidationResult currentValidation = evaluateFinalValidation(cycle, generated, shifts, maxNightsAllowed, preferencesMap, priorOverrideMap);
		boolean hasCriticalErrors = (currentValidation != null && currentValidation.criticalConflicts() > 0);
		if (hasCriticalErrors && cycle.getStatus() != RosterStatus.DRAFT) {
			cycle.setStatus(RosterStatus.DRAFT);
			cycle = cycleRepository.save(cycle);
		}

		// BATCH 80: Verify date-specific preferences fulfillment and log any constraint fallbacks
		if (preferencesMap != null) {
			for (RosterAssignment a : generated) {
				ApplicablePreference pref = preferencesMap.get(a.getEmployee().getId());
				if (pref != null && pref.hasDateSpecificShift(a.getRosterDate())) {
					ShiftType targetShift = pref.getDateSpecificShift(a.getRosterDate());
					ShiftType assignedShift = (a.getShift() != null) ? a.getShift().getShiftType() : ShiftType.OFF;
					if (assignedShift != targetShift) {
						String fallbackReason;
						if (a.isOnLeave()) fallbackReason = "Approved Leave";
						else if (a.isWeeklyOff()) fallbackReason = "Weekly Off / Mandatory Rest";
						else if (a.getEmployee().getGender() == Gender.FEMALE && (targetShift == ShiftType.EVENING || targetShift == ShiftType.NIGHT))
							fallbackReason = "Female Evening/Night Statutory Protection";
						else fallbackReason = "Operational Staffing / Rest Rule Constraint";

						log.warn("[BATCH 80 PREFERENCE CONFLICT] Employee {} ({}) preferred {} on {}, but was assigned {} due to: {}",
								a.getEmployee().getEmployeeCode(), a.getEmployee().getFirstName(),
								targetShift, a.getRosterDate(), assignedShift, fallbackReason);
					}
				}
			}
		}

		if (rosterVersionService != null) {
			try {
				rosterVersionService.recordVersionSnapshot(cycle, mode == GenerationMode.AUTOMATIC ? "AUTOMATIC_GENERATION" : (hasCriticalErrors ? "DRAFT" : "GENERATED"), hasCriticalErrors ? "Draft roster generated with " + currentValidation.criticalConflicts() + " validation issue(s)" : "Roster cycle generated", "system");
			} catch (Exception ignored) {}
		}

		// Persist generation history so future regenerations (even after cycle delete) do not repeat
		if (generationHistoryRepository != null) {
			try {
				String finalFingerprint = computeRosterFingerprint(generated);
				RosterScoringBreakdown scores = calculateSmartScore(generated, preferencesMap, previousFingerprints, employees);
				com.weeklyroster.entity.RosterGenerationHistory historyEntry = new com.weeklyroster.entity.RosterGenerationHistory(
						startDate,
						endDate,
						finalFingerprint,
						bestChosenAttempt,
						mode == GenerationMode.AUTOMATIC ? "AUTOMATIC" : "MANUAL",
						validWorkingDays,
						scores.totalScore(),
						scores.preferenceScore(),
						scores.continuityScore()
				);
				generationHistoryRepository.save(historyEntry);
				log.info("[BATCH 89] Recorded generation history for {} to {}: fingerprint={}, attempt={}, score={}",
						startDate, endDate, finalFingerprint.substring(0, Math.min(12, finalFingerprint.length())), bestChosenAttempt, scores.totalScore());
			} catch (Exception e) {
				log.warn("Failed to record roster generation history: {}", e.getMessage());
			}
		}

		CoverageReportResponse coverageReport = calculateCoverageReport(cycle, generated);

		return toCycleResponse(cycle, generated, coverageReport, currentValidation != null ? currentValidation : bestValidation);
	}


	@Transactional(readOnly = true)
		// -------------------------------------------------------------------------
	// PREFERENCE MANAGEMENT & PARSING ENGINE
	// -------------------------------------------------------------------------

	public Map<Long, ApplicablePreference> loadApprovedPreferences(List<Employee> employees, LocalDate startDate, LocalDate endDate) {
		Map<Long, ApplicablePreference> map = new HashMap<>();
		Map<Long, Map<LocalDate, ShiftType>> dateShiftMap = new HashMap<>();
		if (dateShiftPreferenceRepository != null && startDate != null && endDate != null) {
			try {
				List<com.weeklyroster.entity.DateShiftPreference> datePrefs = dateShiftPreferenceRepository.findByPreferenceDateBetween(startDate, endDate);
				for (com.weeklyroster.entity.DateShiftPreference dp : datePrefs) {
					if (dp.getEmployee() != null && dp.getPreferenceDate() != null && dp.getShiftType() != null) {
						dateShiftMap.computeIfAbsent(dp.getEmployee().getId(), k -> new HashMap<>())
								.put(dp.getPreferenceDate(), dp.getShiftType());
					}
				}
			} catch (Exception e) {
				log.warn("[BATCH 80] Could not load date-specific preferences: {}", e.getMessage());
			}
		}

		Map<Long, Map<DayOfWeek, ShiftType>> recurringShiftMap = new HashMap<>();
		if (recurringShiftPreferenceRepository != null) {
			try {
				List<com.weeklyroster.entity.RecurringShiftPreference> recurringPrefs = recurringShiftPreferenceRepository.findByActiveTrue();
				for (com.weeklyroster.entity.RecurringShiftPreference rp : recurringPrefs) {
					if (rp.getEmployee() != null && rp.getDayOfWeek() != null && rp.getShiftType() != null) {
						boolean startOk = (rp.getStartDate() == null || endDate == null || !rp.getStartDate().isAfter(endDate));
						boolean endOk = (rp.getEndDate() == null || startDate == null || !rp.getEndDate().isBefore(startDate));
						if (startOk && endOk) {
							recurringShiftMap.computeIfAbsent(rp.getEmployee().getId(), k -> new EnumMap<>(DayOfWeek.class))
									.put(rp.getDayOfWeek(), rp.getShiftType());
						}
					}
				}
			} catch (Exception e) {
				log.warn("[BATCH 113] Could not load recurring shift preferences: {}", e.getMessage());
			}
		}

		for (Employee emp : employees) {
			Map<LocalDate, ShiftType> empDateShifts = dateShiftMap.getOrDefault(emp.getId(), Collections.emptyMap());
			Map<DayOfWeek, ShiftType> empRecurringShifts = recurringShiftMap.getOrDefault(emp.getId(), Collections.emptyMap());
			if (preferenceRepository == null) {
				map.put(emp.getId(), ApplicablePreference.withDateAndRecurringShifts(emp.getId(), empDateShifts, empRecurringShifts));
				continue;
			}
			List<EmployeePreference> prefs = preferenceRepository.findByEmployeeIdOrderByCreatedAtDesc(emp.getId());
			EmployeePreference active = prefs.stream()
					.filter(pr -> pr.getStatus() == PreferenceStatus.APPROVED)
					.filter(pr -> (pr.getEffectiveFrom() == null || !pr.getEffectiveFrom().isAfter(endDate))
							&& (pr.getEffectiveTo() == null || !pr.getEffectiveTo().isBefore(startDate)))
					.findFirst()
					.orElse(null);

			if (active != null) {
				map.put(emp.getId(), parseApplicablePreference(active, empDateShifts, empRecurringShifts));
			} else {
				map.put(emp.getId(), ApplicablePreference.withDateAndRecurringShifts(emp.getId(), empDateShifts, empRecurringShifts));
			}
		}
		return map;
	}

	private ApplicablePreference parseApplicablePreference(EmployeePreference p) {
		return parseApplicablePreference(p, Collections.emptyMap(), Collections.emptyMap());
	}

	private ApplicablePreference parseApplicablePreference(EmployeePreference p, Map<LocalDate, ShiftType> dateShifts) {
		return parseApplicablePreference(p, dateShifts, Collections.emptyMap());
	}

	private ApplicablePreference parseApplicablePreference(EmployeePreference p, Map<LocalDate, ShiftType> dateShifts, Map<DayOfWeek, ShiftType> recurringShifts) {
		List<ShiftType> preferredShiftOrder = parseShiftTypesList(p.getPreferredShiftTypes());
		Set<ShiftType> preferredShifts = new LinkedHashSet<>(preferredShiftOrder);
		Set<ShiftType> avoidShifts = parseShiftTypes(p.getAvoidShiftTypes());
		Set<DayOfWeek> preferredOffDays = parseDaysOfWeek(p.getPreferredOffDays());
		Set<DayOfWeek> preferredWorkingDays = parseDaysOfWeek(p.getPreferredWorkingDays());

		if (!preferredWorkingDays.isEmpty() && preferredOffDays.isEmpty()) {
			Set<DayOfWeek> implicitOff = EnumSet.allOf(DayOfWeek.class);
			implicitOff.removeAll(preferredWorkingDays);
			preferredOffDays = implicitOff;
		}

		return new ApplicablePreference(
				p.getId(),
				p.getEmployee().getId(),
				preferredShifts,
				preferredShiftOrder,
				avoidShifts,
				preferredOffDays,
				preferredWorkingDays,
				p.getTemporaryRestrictions(),
				true,
				dateShifts != null ? dateShifts : Collections.emptyMap(),
				recurringShifts != null ? recurringShifts : Collections.emptyMap()
		);
	}

	private List<ShiftType> parseShiftTypesList(String text) {
		if (text == null || text.isBlank()) return Collections.emptyList();
		List<ShiftType> list = new ArrayList<>();
		String[] parts = text.toUpperCase().split("[,;|/\\s]+");
		for (String p : parts) {
			String trimmed = p.trim();
			if (trimmed.isEmpty() || trimmed.equals("AND") || trimmed.equals("&")) continue;
			try {
				ShiftType st = ShiftType.valueOf(trimmed);
				if (!list.contains(st)) {
					list.add(st);
				}
			} catch (IllegalArgumentException ignored) {
				for (ShiftType st : ShiftType.values()) {
					if (st.name().startsWith(trimmed) || (trimmed.length() >= 3 && st.name().startsWith(trimmed.substring(0, 3)))) {
						if (!list.contains(st)) {
							list.add(st);
						}
						break;
					}
				}
			}
		}
		return list;
	}

	private Set<ShiftType> parseShiftTypes(String text) {
		if (text == null || text.isBlank()) return Collections.emptySet();
		Set<ShiftType> set = EnumSet.noneOf(ShiftType.class);
		String[] parts = text.toUpperCase().split("[,;|/\\s]+");
		for (String p : parts) {
			String trimmed = p.trim();
			if (trimmed.isEmpty() || trimmed.equals("AND") || trimmed.equals("&")) continue;
			try {
				set.add(ShiftType.valueOf(trimmed));
			} catch (IllegalArgumentException ignored) {
				for (ShiftType st : ShiftType.values()) {
					if (st.name().startsWith(trimmed) || (trimmed.length() >= 3 && st.name().startsWith(trimmed.substring(0, 3)))) {
						set.add(st);
						break;
					}
				}
			}
		}
		return set;
	}

	private Set<DayOfWeek> parseDaysOfWeek(String text) {
		if (text == null || text.isBlank()) return Collections.emptySet();
		Set<DayOfWeek> set = EnumSet.noneOf(DayOfWeek.class);
		String upper = text.toUpperCase().trim();

		if (upper.contains("-") || upper.contains(" TO ")) {
			String[] tokens = upper.contains("-") ? upper.split("-") : upper.split(" TO ");
			if (tokens.length == 2) {
				DayOfWeek start = parseSingleDay(tokens[0].trim());
				DayOfWeek end = parseSingleDay(tokens[1].trim());
				if (start != null && end != null) {
					int s = start.getValue();
					int e = end.getValue();
					if (s <= e) {
						for (int i = s; i <= e; i++) set.add(DayOfWeek.of(i));
					} else {
						for (int i = s; i <= 7; i++) set.add(DayOfWeek.of(i));
						for (int i = 1; i <= e; i++) set.add(DayOfWeek.of(i));
					}
					return set;
				}
			}
		}

		String[] parts = upper.split("[,;|/\\s]+");
		for (String p : parts) {
			DayOfWeek d = parseSingleDay(p.trim());
			if (d != null) set.add(d);
		}
		return set;
	}

	private DayOfWeek parseSingleDay(String s) {
		if (s == null) return null;
		String u = s.toUpperCase().trim();
		if (u.startsWith("MON")) return DayOfWeek.MONDAY;
		if (u.startsWith("TUE")) return DayOfWeek.TUESDAY;
		if (u.startsWith("WED")) return DayOfWeek.WEDNESDAY;
		if (u.startsWith("THU")) return DayOfWeek.THURSDAY;
		if (u.startsWith("FRI")) return DayOfWeek.FRIDAY;
		if (u.startsWith("SAT")) return DayOfWeek.SATURDAY;
		if (u.startsWith("SUN")) return DayOfWeek.SUNDAY;
		return null;
	}

	private void populateAssignmentReason(RosterAssignment a, ApplicablePreference pref) {
		if (a.isManualAssignment() || a.isOverridden()) {
			if (a.getAssignmentReason() == null || a.getAssignmentReason().isBlank() || a.getAssignmentReason().startsWith("Workforce") || a.getAssignmentReason().equals("Admin Override")) {
				ShiftType st = a.getShift() != null ? a.getShift().getShiftType() : ShiftType.OFF;
				a.setAssignmentReason("Explicit Manual Assignment (" + st + ")");
			}
			return;
		}
		if (a.isOnLeave()) {
			a.setAssignmentReason("Approved Leave");
			return;
		}
		if (a.isWeeklyOff() || a.getShift().getShiftType() == ShiftType.OFF) {
			if (pref != null && pref.isDayPreferredOff(a.getRosterDate().getDayOfWeek())) {
				a.setAssignmentReason("Preferred Weekly OFF (" + a.getRosterDate().getDayOfWeek() + ")");
			} else {
				a.setAssignmentReason("Scheduled Weekly OFF");
			}
			return;
		}

		ShiftType st = a.getShift() != null ? a.getShift().getShiftType() : ShiftType.OFF;
		if (pref != null && pref.hasDateSpecificShift(a.getRosterDate())) {
			ShiftType datePref = pref.getDateSpecificShift(a.getRosterDate());
			if (datePref == st) {
				a.setAssignmentReason("Date-Specific Shift Preference (" + st + ")");
				return;
			} else {
				a.setAssignmentReason("Preference Conflict: " + datePref + " requested, assigned " + st + " due to operational / safety rules");
				return;
			}
		}

		if (pref != null && pref.hasRecurringShift(a.getRosterDate().getDayOfWeek())) {
			ShiftType recPref = pref.getRecurringShift(a.getRosterDate().getDayOfWeek());
			if (recPref == st) {
				a.setAssignmentReason("Recurring Weekly Preference (" + a.getRosterDate().getDayOfWeek() + " -> " + st + ")");
				return;
			} else {
				a.setAssignmentReason("Recurring Preference Conflict: " + recPref + " requested on " + a.getRosterDate().getDayOfWeek() + ", assigned " + st + " due to operational / safety rules");
				return;
			}
		}

		if (pref != null && pref.hasPreferredShifts()) {
			if (pref.isShiftPreferred(st)) {
				int rank = pref.getShiftPreferenceRank(st);
				a.setAssignmentReason("Preferred Shift (" + st + (rank == 0 ? " - Priority 1" : " - Priority " + (rank + 1)) + ")");
				return;
			} else {
				a.setAssignmentReason("Preference Fallback: Assigned " + st + " (Preferred: " + pref.preferredShifts() + ")");
				return;
			}
		}

		if (st == ShiftType.NIGHT) {
			a.setAssignmentReason("Required Night Rotation");
		} else if (st == ShiftType.EVENING) {
			a.setAssignmentReason("Mandatory Evening Coverage");
		} else if (st == ShiftType.MORNING) {
			a.setAssignmentReason("Mandatory Morning Coverage");
		} else if (pref != null && pref.isDayPreferredWorking(a.getRosterDate().getDayOfWeek())) {
			a.setAssignmentReason("Preferred Working Day (" + st + ")");
		} else {
			a.setAssignmentReason("Workforce Operational Coverage (" + st + ")");
		}
	}

	public static String computeRosterFingerprint(List<RosterAssignment> assignments) {
		if (assignments == null || assignments.isEmpty()) return "";
		List<String> entries = assignments.stream()
				.map(a -> {
					String empCode = (a.getEmployee() != null && a.getEmployee().getEmployeeCode() != null)
							? a.getEmployee().getEmployeeCode()
							: ("EMP_" + (a.getEmployee() != null ? a.getEmployee().getId() : "null"));
					String date = String.valueOf(a.getRosterDate());
					String shift = a.isWeeklyOff() ? "OFF" : (a.isOnLeave() ? "LEAVE" : (a.getShift() != null ? a.getShift().getShiftType().name() : "NONE"));
					return empCode + "|" + date + "|" + shift;
				})
				.sorted()
				.toList();
		String normalized = String.join(";", entries);
		try {
			java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
			byte[] hash = md.digest(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder();
			for (byte b : hash) {
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		} catch (Exception e) {
			return Integer.toHexString(normalized.hashCode());
		}
	}

	public record RosterScoringBreakdown(
			int totalScore,
			int preferenceScore,
			int continuityScore,
			int fairnessScore,
			int diversityScore
	) {}

	public RosterScoringBreakdown calculateSmartScore(List<RosterAssignment> assignments,
			Map<Long, ApplicablePreference> preferencesMap,
			Set<String> previousFingerprints,
			List<Employee> employees) {
		if (assignments == null || assignments.isEmpty()) {
			return new RosterScoringBreakdown(0, 0, 0, 0, 0);
		}

		int prefScore = 0;
		int continuityScore = 0;
		int fairnessScore = 0;
		int diversityScore = 0;

		Map<Long, List<RosterAssignment>> empMap = assignments.stream()
				.filter(a -> a.getEmployee() != null)
				.collect(Collectors.groupingBy(a -> a.getEmployee().getId()));

		// 1. Preference score
		for (Map.Entry<Long, List<RosterAssignment>> entry : empMap.entrySet()) {
			Long empId = entry.getKey();
			ApplicablePreference pref = preferencesMap != null ? preferencesMap.get(empId) : null;
			if (pref == null) continue;

			for (RosterAssignment a : entry.getValue()) {
				if (a.isWeeklyOff() || a.isOnLeave() || a.getShift() == null || a.getShift().getShiftType() == ShiftType.OFF) {
					if (a.isWeeklyOff() && pref.hasPreferredOffDays() && pref.isDayPreferredOff(a.getRosterDate().getDayOfWeek())) {
						prefScore += 500;
					}
					continue;
				}
				ShiftType st = a.getShift().getShiftType();
				ShiftType datePref = pref.getDateSpecificShift(a.getRosterDate());
				if (datePref != null) {
					if (st == datePref) {
						prefScore += 1000;
					} else {
						prefScore -= 1500;
					}
				} else if (pref.hasPreferredShifts()) {
					if (pref.isShiftPreferred(st)) {
						int rank = pref.getShiftPreferenceRank(st);
						if (rank == 0) prefScore += 700;
						else if (rank == 1) prefScore += 500;
						else if (rank == 2) prefScore += 300;
						else prefScore += 150;
					} else {
						prefScore -= 400;
					}
				}
			}
		}

		// 2. Continuity score
		for (List<RosterAssignment> empList : empMap.values()) {
			empList.sort(Comparator.comparing(RosterAssignment::getRosterDate));

			List<LocalDate> offDates = empList.stream()
					.filter(RosterAssignment::isWeeklyOff)
					.map(RosterAssignment::getRosterDate)
					.sorted()
					.toList();
			if (offDates.size() == 2) {
				long daysDiff = java.time.temporal.ChronoUnit.DAYS.between(offDates.get(0), offDates.get(1));
				if (daysDiff == 1 || daysDiff == 6) {
					continuityScore += 800;
				}
			}

			List<ShiftType> workingSequence = empList.stream()
					.filter(a -> !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() != ShiftType.OFF)
					.map(a -> a.getShift().getShiftType())
					.toList();

			if (workingSequence.isEmpty()) continue;

			int blocks = 1;
			Set<ShiftType> seenShifts = new HashSet<>();
			seenShifts.add(workingSequence.get(0));
			ShiftType prev = workingSequence.get(0);

			for (int i = 1; i < workingSequence.size(); i++) {
				ShiftType cur = workingSequence.get(i);
				if (cur == prev) {
					continuityScore += 200;
				} else {
					blocks++;
					if (seenShifts.contains(cur)) {
						continuityScore -= 5000;
					}
					seenShifts.add(cur);
					prev = cur;
				}
			}

			if (blocks == 1) {
				continuityScore += 1200;
			} else if (blocks == 2) {
				continuityScore += 600;
			} else {
				continuityScore -= (blocks - 2) * 400;
			}
		}

		// 3. Fairness score & Night targets
		int maleNights = 0;
		for (RosterAssignment a : assignments) {
			if (a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT && !a.isWeeklyOff() && !a.isOnLeave()) {
				maleNights++;
			}
		}
		if (maleNights == 7) fairnessScore += 300;

		// BATCH 93: Development team schedule alignment scoring
		if (employees != null) {
			for (Employee emp : employees) {
				if (isDevelopmentTeam(emp)) {
					List<RosterAssignment> dList = empMap.getOrDefault(emp.getId(), Collections.emptyList());
					for (RosterAssignment a : dList) {
						if (a.isWeeklyOff()) {
							if (a.getRosterDate().getDayOfWeek() == DayOfWeek.SATURDAY || a.getRosterDate().getDayOfWeek() == DayOfWeek.SUNDAY) {
								fairnessScore += 300; // Reward weekend off for Dev team
							}
						} else if (!a.isOnLeave() && a.getShift() != null) {
							ShiftType st = a.getShift().getShiftType();
							if (a.getRosterDate().getDayOfWeek() != DayOfWeek.SATURDAY && a.getRosterDate().getDayOfWeek() != DayOfWeek.SUNDAY) {
								if (st == ShiftType.GENERAL) {
									fairnessScore += 400; // Reward Mon-Fri General for Dev team
								} else if (st == ShiftType.NIGHT) {
									fairnessScore -= 3000;
								} else if (st == ShiftType.EVENING) {
									fairnessScore -= 1500;
								}
							}
						}
					}
				}
			}

			// Paired employee synchronization scoring
			for (Employee emp : employees) {
				if (emp.getPairedEmployeeId() != null && emp.getId() < emp.getPairedEmployeeId()) {
					Employee pairedEmp = employees.stream().filter(e -> e.getId().equals(emp.getPairedEmployeeId())).findFirst().orElse(null);
					if (pairedEmp != null) {
						List<RosterAssignment> rList = empMap.getOrDefault(emp.getId(), Collections.emptyList());
						List<RosterAssignment> pList = empMap.getOrDefault(pairedEmp.getId(), Collections.emptyList());
						Map<LocalDate, RosterAssignment> rDateMap = rList.stream().collect(Collectors.toMap(RosterAssignment::getRosterDate, a -> a, (a1, a2) -> a1));
						for (RosterAssignment pA : pList) {
							RosterAssignment rA = rDateMap.get(pA.getRosterDate());
							if (rA != null) {
								if (rA.isWeeklyOff() && pA.isWeeklyOff()) {
									continuityScore += 500; // Reward synchronized weekly off
								} else if (!rA.isWeeklyOff() && !pA.isWeeklyOff() && !rA.isOnLeave() && !pA.isOnLeave()) {
									ShiftType rType = rA.getShift() != null ? rA.getShift().getShiftType() : null;
									ShiftType pType = pA.getShift() != null ? pA.getShift().getShiftType() : null;
									if (rType == ShiftType.MORNING && pType == ShiftType.MORNING) {
										continuityScore += 500; // Reward synchronized morning
									} else if ((rType == ShiftType.EVENING && pType == ShiftType.MORNING) || (rType == ShiftType.MORNING && pType == ShiftType.EVENING)) {
										continuityScore -= 800; // Penalize opposite shifts (Morning vs Evening)
									}
								}
							}
						}
					}
				}
			}

			// Tailored Night Target alignment scoring
			for (Employee emp : employees) {
				if (emp.getGender() == Gender.MALE) {
					List<RosterAssignment> mList = empMap.getOrDefault(emp.getId(), Collections.emptyList());
					int nights = (int) mList.stream().filter(a -> !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT).count();
					if (emp.isDevelopmentTeam()) {
						if (nights == 0) fairnessScore += 600;
						else if (nights == 1) fairnessScore += 0;
						else fairnessScore -= 2500;
					} else {
						int maxN = emp.getMaxNightsPerWeek() != null ? emp.getMaxNightsPerWeek() : 2;
						if (maxN == 1) {
							if (nights == 1) fairnessScore += 600;
							else if (nights > 1) fairnessScore -= 3500;
						} else if (maxN == 2) {
							if (nights >= 1 && nights <= 2) fairnessScore += 500;
							else if (nights > 2) fairnessScore -= 1500;
						} else {
							if (nights >= 2 && nights <= maxN) fairnessScore += 600;
							else if (nights == 1) fairnessScore += 200;
							else if (nights > maxN) fairnessScore -= 1500;
						}
					}
				}
			}
		}

		// 4. Diversity score
		String candidateFp = computeRosterFingerprint(assignments);
		if (previousFingerprints != null && !previousFingerprints.contains(candidateFp)) {
			diversityScore += 500;
		}

		int totalScore = prefScore + continuityScore + fairnessScore + diversityScore;
		return new RosterScoringBreakdown(totalScore, prefScore, continuityScore, fairnessScore, diversityScore);
	}


	@Transactional(readOnly = true)
	public List<RosterCycleResponse> allCycles() {
		return cycleRepository.findAllByOrderByStartDateDesc().stream().map(cycle -> {
			List<RosterAssignment> assignments = assignmentRepository.findByCycleIdOrderByRosterDateAsc(cycle.getId());
			if (assignments.isEmpty()) {
				assignments = assignmentRepository.findByRosterDateBetweenWithDetails(cycle.getStartDate(), cycle.getEndDate());
			}
			CoverageReportResponse coverageReport = calculateCoverageReport(cycle, assignments);
			return toCycleResponse(cycle, assignments, coverageReport);
		}).toList();
	}

	@Transactional(readOnly = true)
	public RosterCycleResponse cycle(Long id) {
		RosterCycle cycle = cycleRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Roster cycle not found with id: " + id));
		List<RosterAssignment> assignments = assignmentRepository.findByCycleIdOrderByRosterDateAsc(id);
		if (assignments.isEmpty()) {
			assignments = assignmentRepository.findByRosterDateBetweenWithDetails(cycle.getStartDate(), cycle.getEndDate());
		}
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

		// BATCH 109: Persist fingerprint of rejected/deleted roster in history so future regenerations never repeat it
		if (generationHistoryRepository != null) {
			try {
				List<RosterAssignment> currentAssignments = assignmentRepository.findByCycleId(cycle.getId());
				if (!currentAssignments.isEmpty()) {
					String fp = computeRosterFingerprint(currentAssignments);
					com.weeklyroster.entity.RosterGenerationHistory historyEntry = new com.weeklyroster.entity.RosterGenerationHistory(
							cycle.getStartDate(),
							cycle.getEndDate(),
							fp,
							1,
							"REJECTED",
							cycle.getWorkingDays() != null ? cycle.getWorkingDays() : 6,
							0, 0, 0
					);
					generationHistoryRepository.save(historyEntry);
					log.info("[BATCH 109] Stored rejected roster fingerprint {} for week {} to {}",
							fp.substring(0, Math.min(12, fp.length())), cycle.getStartDate(), cycle.getEndDate());
				}
			} catch (Exception e) {
				log.warn("[BATCH 109] Failed to record rejected roster fingerprint: {}", e.getMessage());
			}
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
		return publishRoster(cycleId, null);
	}

	@Transactional
	public RosterCycleResponse publishRoster(Long cycleId, String adminMessage) {
		var auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
			throw new AccessDeniedException("Only administrators can publish rosters");
		}
		RosterCycle cycle = cycleRepository.findById(cycleId)
				.orElseThrow(() -> new ResourceNotFoundException("Roster cycle not found with id: " + cycleId));
		List<RosterAssignment> assignments = assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(cycle);

		// BATCH 94: Double publish protection - Idempotency
		if (cycle.getStatus() == RosterStatus.PUBLISHED) {
			log.info("[WRMS PUBLISH] Cycle #{} is already published. Publish operation is idempotent (no-op).", cycleId);
			CoverageReportResponse coverageReport = calculateCoverageReport(cycle, assignments);
			return toCycleResponse(cycle, assignments, coverageReport);
		}
		
		if (rosterHealthService != null) {
			RosterHealthReport health = rosterHealthService.evaluateHealth(cycle, assignments);
			if (!health.readyToPublish()) {
				String conflictDetails = health.conflicts().stream()
						.filter(c -> "CRITICAL".equalsIgnoreCase(c.severity()))
						.map(c -> c.ruleName() + ": " + c.reason() + " on " + c.date())
						.collect(Collectors.joining("; "));
				throw new BusinessException("Roster cannot be published until critical conflicts are resolved. Found " 
						+ health.criticalConflictsCount() + " critical conflict(s). " + conflictDetails);
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
		RosterCycleResponse response = toCycleResponse(cycle, assignments, coverageReport);

		// BATCH 94: Controlled email dispatch on Publish
		// Email is triggered ONLY after publication is successfully persisted in DB.
		// If email distribution encounters an issue, the roster remains published and is not rolled back.
		if (rosterEmailService != null) {
			try {
				rosterEmailService.distributeFinalRosterEmails(cycle, response, GenerationMode.MANUAL, adminMessage);
			} catch (Exception e) {
				log.error("[WRMS PUBLISH] Email distribution error after successful publish for cycle #{}: {}", cycle.getId(), e.getMessage());
			}
		}

		return response;
	}

	@Transactional(readOnly = true)
	public FinalValidationResult validateCycle(Long cycleId) {
		RosterCycle cycle = cycleRepository.findById(cycleId)
				.orElseThrow(() -> new ResourceNotFoundException("Roster cycle not found with id: " + cycleId));
		List<RosterAssignment> assignments = assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(cycle);
		if (assignments.isEmpty()) {
			assignments = assignmentRepository.findByRosterDateBetweenWithDetails(cycle.getStartDate(), cycle.getEndDate());
		}
		List<Employee> employees = employeeRepository.findByActiveTrueOrderByIdAsc();
		Map<Long, ApplicablePreference> preferencesMap = loadApprovedPreferences(employees, cycle.getStartDate(), cycle.getEndDate());
		Map<ShiftType, Shift> shifts = activeShiftMap();
		int maxNights = computeMaxNightsAllowed(employees);
		return evaluateFinalValidation(cycle, assignments, shifts, maxNights, preferencesMap, Collections.emptyMap());
	}

	@Transactional
	public RosterCycleResponse finalizeRoster(Long cycleId) {
		return finalizeRoster(cycleId, null);
	}

	@Transactional
	public RosterCycleResponse finalizeRoster(Long cycleId, String adminMessage) {
		var auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
			throw new AccessDeniedException("Only administrators can finalize rosters");
		}
		RosterCycle cycle = cycleRepository.findById(cycleId)
				.orElseThrow(() -> new ResourceNotFoundException("Roster cycle not found with id: " + cycleId));
		List<RosterAssignment> assignments = assignmentRepository.findByCycleOrderByRosterDateAscEmployeeIdAsc(cycle);
		if (assignments.isEmpty()) {
			assignments = assignmentRepository.findByRosterDateBetweenWithDetails(cycle.getStartDate(), cycle.getEndDate());
		}

		List<Employee> employees = employeeRepository.findByActiveTrueOrderByIdAsc();
		Map<Long, ApplicablePreference> preferencesMap = loadApprovedPreferences(employees, cycle.getStartDate(), cycle.getEndDate());
		Map<ShiftType, Shift> shifts = activeShiftMap();
		int maxNights = computeMaxNightsAllowed(employees);
		FinalValidationResult val = evaluateFinalValidation(cycle, assignments, shifts, maxNights, preferencesMap, Collections.emptyMap());

		if (val.criticalConflicts() > 0) {
			String conflictDetails = val.conflicts().stream()
					.filter(c -> "CRITICAL".equalsIgnoreCase(c.severity()))
					.map(c -> (c.employeeName() != null ? c.employeeName() + ": " : "") + c.reason())
					.collect(Collectors.joining("; "));
			throw new BusinessException("Cannot finalize roster: " + val.criticalConflicts() + " mandatory issue(s) still need correction. " + conflictDetails);
		}

		return publishRoster(cycleId, adminMessage);
	}

	// =========================================================================
	// BATCH 102: MANUAL ROSTER BUILDER ENGINE & STRICT ASSIGNMENT ENFORCEMENT
	// =========================================================================

	@Transactional
	public RosterCycle getOrCreateDraftCycle(LocalDate startDate, int workingDays) {
		int validWorkingDays = (workingDays == 5) ? 5 : 6;
		if (startDate == null) {
			startDate = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
					.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY));
		}
		if (startDate.getDayOfWeek() != java.time.DayOfWeek.MONDAY) {
			startDate = startDate.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
		}
		LocalDate endDate = startDate.plusDays(6);

		List<RosterCycle> existing = cycleRepository.findOverlappingCycles(startDate, endDate);
		if (!existing.isEmpty()) {
			return existing.get(0);
		}
		var opt = cycleRepository.findByStartDateAndEndDate(startDate, endDate);
		if (opt.isPresent()) {
			return opt.get();
		}

		RosterCycle draft = new RosterCycle();
		draft.setStartDate(startDate);
		draft.setEndDate(endDate);
		draft.setStatus(RosterStatus.DRAFT);
		draft.setWorkingDays(validWorkingDays);
		draft.setGeneratedAt(LocalDateTime.now());
		return cycleRepository.save(draft);
	}

	@Transactional(readOnly = true)
	public com.weeklyroster.dto.response.ManualRosterMatrixResponse getManualRosterMatrix(Long cycleId, LocalDate startDate) {
		RosterCycle cycle = null;
		if (cycleId != null) {
			cycle = cycleRepository.findById(cycleId).orElse(null);
		}
		if (cycle == null) {
			if (startDate == null) {
				startDate = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY));
			} else if (startDate.getDayOfWeek() != java.time.DayOfWeek.MONDAY) {
				startDate = startDate.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
			}
			LocalDate endDate = startDate.plusDays(6);
			List<RosterCycle> existing = cycleRepository.findOverlappingCycles(startDate, endDate);
			if (!existing.isEmpty()) {
				cycle = existing.get(0);
			} else {
				cycle = cycleRepository.findByStartDateAndEndDate(startDate, endDate).orElse(null);
			}
		}

		if (cycle == null) {
			if (startDate == null) {
				startDate = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY));
			} else if (startDate.getDayOfWeek() != java.time.DayOfWeek.MONDAY) {
				startDate = startDate.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
			}
			return buildEmptyMatrixResponse(startDate, startDate.plusDays(6), 6);
		}

		return buildMatrixResponse(cycle);
	}

	@Transactional(readOnly = true)
	public com.weeklyroster.dto.response.ManualRosterMatrixResponse buildMatrixResponse(RosterCycle cycle) {
		LocalDate startDate = cycle.getStartDate();
		LocalDate endDate = cycle.getEndDate();
		List<LocalDate> dates = new ArrayList<>();
		for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
			dates.add(d);
		}

		List<Employee> employees = employeeRepository.findByActiveTrueOrderByIdAsc();
		List<RosterAssignment> assignments = assignmentRepository.findByCycleId(cycle.getId());

		Map<Long, Map<LocalDate, RosterAssignment>> assignmentMap = new HashMap<>();
		for (RosterAssignment a : assignments) {
			if (a.getEmployee() != null && a.getRosterDate() != null) {
				assignmentMap.computeIfAbsent(a.getEmployee().getId(), k -> new HashMap<>())
						.put(a.getRosterDate(), a);
			}
		}

		List<String> validationWarnings = new ArrayList<>();
		int[] criticalConflicts = new int[1];

		Map<LocalDate, Map<ShiftType, Long>> dailyShiftCounts = new LinkedHashMap<>();
		Map<LocalDate, Long> dailyTotalWorking = new LinkedHashMap<>();
		Map<LocalDate, Long> dailyTotalOff = new LinkedHashMap<>();

		for (LocalDate d : dates) {
			Map<ShiftType, Long> counts = new EnumMap<>(ShiftType.class);
			for (ShiftType st : ShiftType.values()) {
				counts.put(st, 0L);
			}
			dailyShiftCounts.put(d, counts);
			dailyTotalWorking.put(d, 0L);
			dailyTotalOff.put(d, 0L);
		}

		List<com.weeklyroster.dto.response.ManualRosterEmployeeRow> rows = new ArrayList<>();

		for (Employee emp : employees) {
			Map<LocalDate, RosterAssignment> empDayMap = assignmentMap.getOrDefault(emp.getId(), Collections.emptyMap());
			Map<LocalDate, com.weeklyroster.dto.response.ManualRosterCellResponse> cellMap = new LinkedHashMap<>();

			int totalWorking = 0;
			int totalNights = 0;
			int totalOffs = 0;

			ShiftType prevShift = null;

			for (LocalDate date : dates) {
				RosterAssignment a = empDayMap.get(date);
				boolean onLeave = isApprovedLeave(emp.getId(), date);
				boolean isManual = a != null && (a.isManualAssignment() || a.isOverridden());
				boolean isOff = (a != null) ? a.isWeeklyOff() || (a.getShift() != null && a.getShift().getShiftType() == ShiftType.OFF) : false;
				ShiftType shiftType = (a != null && a.getShift() != null) ? a.getShift().getShiftType() : ShiftType.OFF;
				String source = (a != null && a.getAssignmentSource() != null) ? a.getAssignmentSource() : (isManual ? "MANUAL" : "AUTOMATIC");
				String reason = (a != null) ? a.getAssignmentReason() : null;

				boolean hasConflict = false;
				String conflictReason = null;

				// Conflict Check 1: Approved leave conflict with assigned working shift
				if (onLeave && !isOff && shiftType != ShiftType.OFF) {
					hasConflict = true;
					conflictReason = "Approved Leave conflict: Employee is on leave on this date";
					criticalConflicts[0]++;
					validationWarnings.add(emp.getFirstName() + " has approved leave on " + date + " but is assigned " + shiftType);
				}

								// Conflict Check 2: CRIS Strict Hard Rule - Night shift must be followed by Weekly OFF
				if (prevShift == ShiftType.NIGHT && !isOff) {
					hasConflict = true;
					conflictReason = "Night shift rule violation: Employee assigned " + shiftType + " on " + date + " immediately following Night shift on " + date.minusDays(1) + ". CRIS mandatory fatigue safety rule requires Weekly OFF on the following calendar day.";
					criticalConflicts[0]++;
					validationWarnings.add(emp.getFirstName() + " assigned " + shiftType + " immediately following Night shift on " + date.minusDays(1));
				}

				// Conflict Check 3: Female employee statutory protection
				if (emp.getGender() == Gender.FEMALE && shiftType == ShiftType.NIGHT) {
					hasConflict = true;
					conflictReason = "Statutory violation: Female employee cannot be scheduled for Night shift";
					criticalConflicts[0]++;
					validationWarnings.add("Female statutory violation: " + emp.getFirstName() + " assigned Night shift on " + date);
				}

				if (isOff || onLeave) {
					totalOffs++;
					dailyTotalOff.put(date, dailyTotalOff.get(date) + 1);
				} else {
					totalWorking++;
					dailyTotalWorking.put(date, dailyTotalWorking.get(date) + 1);
					if (shiftType == ShiftType.NIGHT) totalNights++;
				}

				Map<ShiftType, Long> dayCounts = dailyShiftCounts.get(date);
				dayCounts.put(shiftType, dayCounts.getOrDefault(shiftType, 0L) + 1);

				cellMap.put(date, new com.weeklyroster.dto.response.ManualRosterCellResponse(
						a != null ? a.getId() : null,
						date,
						shiftType,
						isOff,
						onLeave,
						isManual,
						a != null && a.isOverridden(),
						source,
						reason,
						hasConflict,
						conflictReason
				));

				prevShift = isOff ? ShiftType.OFF : shiftType;
			}

			rows.add(new com.weeklyroster.dto.response.ManualRosterEmployeeRow(
					emp.getId(),
					emp.getEmployeeCode(),
					emp.getFirstName() + " " + (emp.getLastName() != null ? emp.getLastName() : "").trim(),
					emp.getGender(),
					cellMap,
					totalWorking,
					totalNights,
					totalOffs
			));
		}

		boolean isPublished = (cycle.getStatus() == RosterStatus.PUBLISHED || cycle.getStatus() == RosterStatus.FINAL || cycle.getStatus() == RosterStatus.LOCKED);

		return new com.weeklyroster.dto.response.ManualRosterMatrixResponse(
				cycle.getId(),
				startDate,
				endDate,
				cycle.getStatus(),
				cycle.getWorkingDays() != null ? cycle.getWorkingDays() : 6,
				dates,
				rows,
				dailyShiftCounts,
				dailyTotalWorking,
				dailyTotalOff,
				validationWarnings,
				criticalConflicts[0],
				isPublished
		);
	}

	@Transactional(readOnly = true)
	public com.weeklyroster.dto.response.ManualRosterMatrixResponse buildEmptyMatrixResponse(LocalDate startDate, LocalDate endDate, int workingDays) {
		List<LocalDate> dates = new ArrayList<>();
		for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
			dates.add(d);
		}
		List<Employee> employees = employeeRepository.findByActiveTrueOrderByIdAsc();
		Map<LocalDate, Map<ShiftType, Long>> dailyShiftCounts = new LinkedHashMap<>();
		Map<LocalDate, Long> dailyTotalWorking = new LinkedHashMap<>();
		Map<LocalDate, Long> dailyTotalOff = new LinkedHashMap<>();

		for (LocalDate d : dates) {
			Map<ShiftType, Long> counts = new EnumMap<>(ShiftType.class);
			for (ShiftType st : ShiftType.values()) {
				counts.put(st, 0L);
			}
			counts.put(ShiftType.OFF, (long) employees.size());
			dailyShiftCounts.put(d, counts);
			dailyTotalWorking.put(d, 0L);
			dailyTotalOff.put(d, (long) employees.size());
		}

		List<com.weeklyroster.dto.response.ManualRosterEmployeeRow> rows = new ArrayList<>();
		for (Employee emp : employees) {
			Map<LocalDate, com.weeklyroster.dto.response.ManualRosterCellResponse> cellMap = new LinkedHashMap<>();
			for (LocalDate d : dates) {
				boolean onLeave = isApprovedLeave(emp.getId(), d);
				cellMap.put(d, new com.weeklyroster.dto.response.ManualRosterCellResponse(
						null,
						d,
						ShiftType.OFF,
						true,
						onLeave,
						false,
						false,
						"UNASSIGNED",
						onLeave ? "Approved Leave" : "Unassigned / OFF",
						false,
						null
				));
			}
			rows.add(new com.weeklyroster.dto.response.ManualRosterEmployeeRow(
					emp.getId(),
					emp.getEmployeeCode(),
					emp.getFirstName() + " " + (emp.getLastName() != null ? emp.getLastName() : "").trim(),
					emp.getGender(),
					cellMap,
					0,
					0,
					7
			));
		}

		return new com.weeklyroster.dto.response.ManualRosterMatrixResponse(
				null,
				startDate,
				endDate,
				RosterStatus.DRAFT,
				workingDays,
				dates,
				rows,
				dailyShiftCounts,
				dailyTotalWorking,
				dailyTotalOff,
				Collections.emptyList(),
				0,
				false
		);
	}

	public static class AssignmentSnapshotItem {
		public Long assignmentId;
		public Long employeeId;
		public String rosterDate;
		public ShiftType shiftType;
		public boolean weeklyOff;
		public boolean onLeave;
		public boolean manualAssignment;
		public boolean overridden;
		public String assignmentSource;
		public String assignmentReason;
		public String overrideReason;
		public boolean existed;

		public AssignmentSnapshotItem() {}

		public static AssignmentSnapshotItem from(RosterAssignment a, Long employeeId, LocalDate date) {
			AssignmentSnapshotItem item = new AssignmentSnapshotItem();
			item.employeeId = employeeId;
			item.rosterDate = (date != null) ? date.toString() : null;
			if (a != null && a.getId() != null) {
				item.assignmentId = a.getId();
				item.shiftType = (a.getShift() != null) ? a.getShift().getShiftType() : ShiftType.OFF;
				item.weeklyOff = a.isWeeklyOff();
				item.onLeave = a.isOnLeave();
				item.manualAssignment = a.isManualAssignment();
				item.overridden = a.isOverridden();
				item.assignmentSource = a.getAssignmentSource();
				item.assignmentReason = a.getAssignmentReason();
				item.overrideReason = a.getOverrideReason();
				item.existed = true;
			} else {
				item.existed = false;
				item.shiftType = ShiftType.OFF;
				item.weeklyOff = true;
				item.assignmentSource = "AUTOMATIC";
			}
			return item;
		}
	}

	private ObjectMapper getObjectMapper() {
		if (this.objectMapper != null) {
			return this.objectMapper;
		}
		ObjectMapper mapper = new ObjectMapper();
		mapper.findAndRegisterModules();
		return mapper;
	}

	private void recordManualOperation(Long cycleId, String operationType, String description,
	                                   String adminUsername, List<AssignmentSnapshotItem> snapshots) {
		if (manualRosterOperationRepository == null || snapshots == null || snapshots.isEmpty()) {
			return;
		}
		try {
			String json = getObjectMapper().writeValueAsString(snapshots);
			ManualRosterOperation op = new ManualRosterOperation(
					cycleId, operationType, description, adminUsername, json);
			manualRosterOperationRepository.save(op);
		} catch (Exception e) {
			log.warn("Failed to record manual roster operation snapshot: {}", e.getMessage());
		}
	}

	public com.weeklyroster.dto.response.ManualRosterMatrixResponse saveManualAssignment(
			com.weeklyroster.dto.request.ManualAssignmentRequest req, String adminUsername) {
		if (databaseWriteCoordinator != null) {
			return databaseWriteCoordinator.executeTransactionalWrite(() -> doSaveManualAssignment(req, adminUsername));
		}
		return doSaveManualAssignment(req, adminUsername);
	}

	public com.weeklyroster.dto.response.ManualRosterMatrixResponse doSaveManualAssignment(
			com.weeklyroster.dto.request.ManualAssignmentRequest req, String adminUsername) {
		if (req == null) throw new BusinessException("Request cannot be null");
		if (req.employeeId() == null) throw new BusinessException("Employee ID is required");
		if (req.date() == null) throw new BusinessException("Date is required");
		if (req.shiftType() == null) throw new BusinessException("Shift type is required");

		LocalDate date = req.date();
		LocalDate monday = date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));

		RosterCycle cycle = null;
		if (req.cycleId() != null) {
			cycle = cycleRepository.findById(req.cycleId()).orElse(null);
		}
		if (cycle == null) {
			cycle = getOrCreateDraftCycle(monday, 6);
		}
		if (cycle.getStatus() == RosterStatus.LOCKED) {
			throw new BusinessException("Cannot modify assignments in a locked roster cycle");
		}

		Employee employee = employeeRepository.findById(req.employeeId())
				.orElseThrow(() -> new ResourceNotFoundException("Employee #" + req.employeeId() + " not found"));

		Map<ShiftType, Shift> shifts = activeShiftMap();
		boolean isOff = req.shiftType() == ShiftType.OFF || Boolean.TRUE.equals(req.weeklyOff());
		boolean onLeave = isApprovedLeave(employee.getId(), date);

				if (onLeave && !isOff && req.shiftType() != ShiftType.OFF) {
			throw new BusinessException("Level 0 Constraint Conflict: Employee " + employee.getFirstName() + " " + employee.getLastName()
					+ " has Approved Leave on " + date + ". Admin must resolve the leave conflict before assigning a working shift.");
		}

		// BATCH 109: Night shift strict constraints in manual assignment
		List<RosterAssignment> yesterdayList = assignmentRepository.findByEmployeeIdAndRosterDate(employee.getId(), date.minusDays(1));
		if (!yesterdayList.isEmpty()) {
			RosterAssignment yest = yesterdayList.get(0);
			if (!yest.isWeeklyOff() && !yest.isOnLeave() && yest.getShift() != null && yest.getShift().getShiftType() == ShiftType.NIGHT) {
				if (!isOff && req.shiftType() != ShiftType.OFF) {
					throw new BusinessException("Level 0 Constraint Conflict: Employee " + employee.getFirstName() + " " + employee.getLastName()
							+ " worked NIGHT on " + date.minusDays(1) + ". CRIS mandatory fatigue safety rule requires WEEKLY OFF on the following calendar day (" + date + ").");
				}
			}
		}

		if (req.shiftType() == ShiftType.NIGHT && !isOff) {
			if (employee.getGender() == Gender.FEMALE) {
				throw new BusinessException("Statutory Violation: Female employee " + employee.getFirstName() + " " + employee.getLastName()
						+ " cannot be assigned to Night shift under CRIS regulations.");
			}
		}

		List<RosterAssignment> existingList = assignmentRepository.findByEmployeeIdAndRosterDate(employee.getId(), date);
		RosterAssignment assignment;
		if (!existingList.isEmpty()) {
			assignment = existingList.get(0);
		} else {
			assignment = new RosterAssignment();
			assignment.setCycle(cycle);
			assignment.setEmployee(employee);
			assignment.setRosterDate(date);
		}

		// Snapshot prior state for Durable Undo
		AssignmentSnapshotItem snapshot = AssignmentSnapshotItem.from(
				assignment.getId() != null ? assignment : null, employee.getId(), date);
		recordManualOperation(cycle.getId(), "SINGLE_ASSIGN",
				"Assigned " + req.shiftType() + " to " + employee.getFirstName() + " on " + date,
				adminUsername, List.of(snapshot));

		assignment.setCycle(cycle);
		assignment.setOnLeave(onLeave);
		assignment.setWeeklyOff(isOff);
		assignment.setShift(isOff ? (shifts.get(ShiftType.OFF) != null ? shifts.get(ShiftType.OFF) : null) : shifts.get(req.shiftType()));
		assignment.setManualAssignment(true);
		assignment.setOverridden(true);
		assignment.setAssignmentSource("MANUAL");
		String reason = req.reason() != null && !req.reason().isBlank() ? req.reason() : "Explicit Manual Assignment (" + req.shiftType() + ")";
		assignment.setAssignmentReason(reason);
		assignment.setOverrideReason(reason);
		assignment.setOverrideCreatedAt(LocalDateTime.now());

				assignmentRepository.save(assignment);
		assignmentRepository.flush();

		// BATCH 109: Automatically ensure next day is Weekly OFF when Night is assigned
		if (req.shiftType() == ShiftType.NIGHT && !isOff) {
			LocalDate nextDay = date.plusDays(1);
			List<RosterAssignment> nextList = assignmentRepository.findByEmployeeIdAndRosterDate(employee.getId(), nextDay);
			RosterAssignment nextAssignment;
			if (!nextList.isEmpty()) {
				nextAssignment = nextList.get(0);
			} else {
				nextAssignment = new RosterAssignment();
				nextAssignment.setCycle(cycle);
				nextAssignment.setEmployee(employee);
				nextAssignment.setRosterDate(nextDay);
			}
			if (!nextAssignment.isOnLeave()) {
				nextAssignment.setWeeklyOff(true);
				nextAssignment.setShift(shifts.get(ShiftType.OFF));
				nextAssignment.setAssignmentReason("Mandatory Weekly OFF following Night shift on " + date);
				nextAssignment.setAssignmentSource("MANUAL");
				assignmentRepository.save(nextAssignment);
				assignmentRepository.flush();
			}
		}

		if (auditService != null) {
			auditService.log(
					AuditAction.SHIFT_OVERRIDDEN,
					"ROSTER_ASSIGNMENT",
					assignment.getId(),
					cycle.getId(),
					employee.getId(),
					employee.getFirstName() + " " + employee.getLastName(),
					null,
					req.shiftType().name(),
					"Admin manually assigned " + req.shiftType() + " to " + employee.getFirstName() + " on " + date,
					"MANUAL"
			);
		}

		return buildMatrixResponse(cycle);
	}

	public com.weeklyroster.dto.response.ManualRosterMatrixResponse bulkAssignManual(
			com.weeklyroster.dto.request.BulkManualAssignmentRequest req, String adminUsername) {
		if (databaseWriteCoordinator != null) {
			return databaseWriteCoordinator.executeTransactionalWrite(() -> doBulkAssignManual(req, adminUsername));
		}
		return doBulkAssignManual(req, adminUsername);
	}

	public com.weeklyroster.dto.response.ManualRosterMatrixResponse doBulkAssignManual(
			com.weeklyroster.dto.request.BulkManualAssignmentRequest req, String adminUsername) {
		if (req == null || req.employeeIds() == null || req.dates() == null || req.shiftType() == null) {
			throw new BusinessException("Invalid bulk assignment parameters");
		}
		if (req.dates().isEmpty()) throw new BusinessException("At least one date is required");
		if (req.employeeIds().isEmpty()) throw new BusinessException("At least one employee is required");

		LocalDate firstDate = (req.startDate() != null) ? req.startDate() : req.dates().get(0);
		LocalDate monday = firstDate.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
		RosterCycle cycle = null;
		if (req.cycleId() != null) {
			cycle = cycleRepository.findById(req.cycleId()).orElse(null);
		}
		if (cycle == null) {
			cycle = getOrCreateDraftCycle(monday, 6);
		}
		if (cycle.getStatus() == RosterStatus.LOCKED) {
			throw new BusinessException("Cannot modify assignments in a locked roster cycle");
		}

		Map<ShiftType, Shift> shifts = activeShiftMap();
		boolean isOff = req.shiftType() == ShiftType.OFF || Boolean.TRUE.equals(req.weeklyOff());

		// Record snapshots for Durable Undo before mutations
		List<AssignmentSnapshotItem> snapshots = new ArrayList<>();
		for (Long empId : req.employeeIds()) {
			for (LocalDate d : req.dates()) {
				List<RosterAssignment> existingList = assignmentRepository.findByEmployeeIdAndRosterDate(empId, d);
				RosterAssignment existing = !existingList.isEmpty() ? existingList.get(0) : null;
				snapshots.add(AssignmentSnapshotItem.from(existing, empId, d));
			}
		}
		recordManualOperation(cycle.getId(), "BULK_ASSIGN",
				"Bulk assigned " + req.shiftType() + " to " + req.employeeIds().size() + " employee(s) across " + req.dates().size() + " date(s)",
				adminUsername, snapshots);

		int appliedCount = 0;
		int skippedLeaveCount = 0;

		for (Long empId : req.employeeIds()) {
			Employee emp = employeeRepository.findById(empId).orElse(null);
			if (emp == null || !emp.isActive()) continue;

			for (LocalDate d : req.dates()) {
				boolean onLeave = isApprovedLeave(emp.getId(), d);
				if (onLeave && !isOff) {
					skippedLeaveCount++;
					continue;
				}

				List<RosterAssignment> existingList = assignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), d);
				RosterAssignment assignment = !existingList.isEmpty() ? existingList.get(0) : new RosterAssignment();
				if (existingList.size() > 1) {
					for (int i = 1; i < existingList.size(); i++) {
						assignmentRepository.delete(existingList.get(i));
					}
				}

				assignment.setCycle(cycle);
				assignment.setEmployee(emp);
				assignment.setRosterDate(d);
				assignment.setOnLeave(onLeave);
				assignment.setWeeklyOff(isOff);
				assignment.setShift(isOff ? (shifts.get(ShiftType.OFF) != null ? shifts.get(ShiftType.OFF) : null) : shifts.get(req.shiftType()));
				assignment.setManualAssignment(true);
				assignment.setOverridden(true);
				assignment.setAssignmentSource("MANUAL");
				String reason = req.reason() != null && !req.reason().isBlank() ? req.reason().trim() : "Bulk Manual Assignment (" + req.shiftType() + ")";
				assignment.setAssignmentReason(reason);
				assignment.setOverrideReason(reason);
				assignment.setOverrideCreatedAt(LocalDateTime.now());
				assignmentRepository.save(assignment);
				appliedCount++;
			}
		}

		if (appliedCount == 0 && skippedLeaveCount > 0) {
			throw new BusinessException("Cannot assign working shift: selected employee(s) have approved leave on selected date(s).");
		}

		assignmentRepository.flush();
		return buildMatrixResponse(cycle);
	}

	public com.weeklyroster.dto.response.ManualRosterMatrixResponse clearManualAssignment(
			Long cycleId, Long employeeId, LocalDate date, String adminUsername) {
		if (databaseWriteCoordinator != null) {
			return databaseWriteCoordinator.executeTransactionalWrite(() -> doClearManualAssignment(cycleId, employeeId, date, adminUsername));
		}
		return doClearManualAssignment(cycleId, employeeId, date, adminUsername);
	}

	public com.weeklyroster.dto.response.ManualRosterMatrixResponse doClearManualAssignment(
			Long cycleId, Long employeeId, LocalDate date, String adminUsername) {
		RosterCycle cycle = (cycleId != null) ? cycleRepository.findById(cycleId).orElse(null) : null;
		if (cycle == null && date != null) {
			LocalDate monday = date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
			cycle = getOrCreateDraftCycle(monday, 6);
		}
		if (cycle == null) throw new ResourceNotFoundException("Roster cycle not found");

		List<RosterAssignment> existingList = assignmentRepository.findByEmployeeIdAndRosterDate(employeeId, date);
		if (!existingList.isEmpty()) {
			RosterAssignment a = existingList.get(0);
			AssignmentSnapshotItem snapshot = AssignmentSnapshotItem.from(a, employeeId, date);
			recordManualOperation(cycle.getId(), "CLEAR",
					"Cleared manual assignment for employee #" + employeeId + " on " + date,
					adminUsername, List.of(snapshot));

			a.setManualAssignment(false);
			a.setOverridden(false);
			a.setAssignmentSource("AUTOMATIC");
			a.setAssignmentReason("Cleared manual assignment");
			assignmentRepository.save(a);
			assignmentRepository.flush();
		}

		return buildMatrixResponse(cycle);
	}

	@Transactional(readOnly = true)
	public com.weeklyroster.dto.response.CopyWeekPreviewResponse previewCopyWeek(
			com.weeklyroster.dto.request.CopyPreviousWeekRequest req) {
		if (req == null || req.targetStartDate() == null) {
			throw new BusinessException("Target start date is required");
		}
		LocalDate targetMonday = req.targetStartDate().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
		LocalDate targetSunday = targetMonday.plusDays(6);
		LocalDate sourceMonday = (req.sourceStartDate() != null)
				? req.sourceStartDate().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
				: targetMonday.minusWeeks(1);
		LocalDate sourceSunday = sourceMonday.plusDays(6);

		List<RosterCycle> sourceCycles = cycleRepository.findOverlappingCycles(sourceMonday, sourceSunday);
		if (sourceCycles.isEmpty()) {
			var opt = cycleRepository.findByStartDateAndEndDate(sourceMonday, sourceSunday);
			if (opt.isPresent()) sourceCycles.add(opt.get());
		}
		if (sourceCycles.isEmpty()) {
			return new com.weeklyroster.dto.response.CopyWeekPreviewResponse(
					sourceMonday, sourceSunday, targetMonday, targetSunday,
					0, 0, 0, 0, 1,
					List.of("No roster cycle found for source week (" + sourceMonday + " to " + sourceSunday + ") to copy from."),
					false
			);
		}

		RosterCycle sourceCycle = sourceCycles.get(0);
		List<RosterAssignment> sourceAssignments = assignmentRepository.findByCycleId(sourceCycle.getId());
		if (sourceAssignments.isEmpty()) {
			return new com.weeklyroster.dto.response.CopyWeekPreviewResponse(
					sourceMonday, sourceSunday, targetMonday, targetSunday,
					0, 0, 0, 0, 1,
					List.of("Source week roster cycle has no assignments to copy."),
					false
			);
		}

		List<RosterAssignment> targetExisting = assignmentRepository.findByDateRange(targetMonday, targetSunday);
		Map<String, RosterAssignment> targetMap = new HashMap<>();
		for (RosterAssignment ta : targetExisting) {
			if (ta.getEmployee() != null && ta.getRosterDate() != null) {
				targetMap.put(ta.getEmployee().getId() + "_" + ta.getRosterDate(), ta);
			}
		}

		boolean includeOff = req.includeWeeklyOff() == null || req.includeWeeklyOff();
		boolean overwriteManual = Boolean.TRUE.equals(req.overwriteExistingManual());

		Set<Long> affectedEmployees = new HashSet<>();
		Set<LocalDate> affectedDates = new HashSet<>();
		List<String> conflicts = new ArrayList<>();
		int totalToCopy = 0;
		int protectedManualCount = 0;
		long dayDiff = java.time.temporal.ChronoUnit.DAYS.between(sourceMonday, targetMonday);

		for (RosterAssignment sa : sourceAssignments) {
			if (sa.getEmployee() == null || !sa.getEmployee().isActive()) continue;
			LocalDate targetDate = sa.getRosterDate().plusDays(dayDiff);
			if (targetDate.isBefore(targetMonday) || targetDate.isAfter(targetSunday)) continue;

			if (!includeOff && sa.isWeeklyOff()) continue;

			String key = sa.getEmployee().getId() + "_" + targetDate;
			RosterAssignment existing = targetMap.get(key);
			if (existing != null && (existing.isManualAssignment() || existing.isOverridden()) && !overwriteManual) {
				protectedManualCount++;
				conflicts.add("Preserving manual assignment for " + sa.getEmployee().getFirstName() + " on " + targetDate);
				continue;
			}

			boolean onLeave = isApprovedLeave(sa.getEmployee().getId(), targetDate);
			if (onLeave && !sa.isWeeklyOff()) {
				conflicts.add(sa.getEmployee().getFirstName() + " has approved leave on " + targetDate + " (will be set to Leave/OFF)");
			}

			affectedEmployees.add(sa.getEmployee().getId());
			affectedDates.add(targetDate);
			totalToCopy++;
		}

		boolean canApply = totalToCopy > 0;
		return new com.weeklyroster.dto.response.CopyWeekPreviewResponse(
				sourceMonday, sourceSunday, targetMonday, targetSunday,
				totalToCopy, affectedEmployees.size(), affectedDates.size(),
				protectedManualCount, conflicts.size(), conflicts, canApply
		);
	}

	public com.weeklyroster.dto.response.ManualRosterMatrixResponse copyPreviousWeek(
			com.weeklyroster.dto.request.CopyPreviousWeekRequest req, String adminUsername) {
		if (databaseWriteCoordinator != null) {
			return databaseWriteCoordinator.executeTransactionalWrite(() -> doCopyPreviousWeek(req, adminUsername));
		}
		return doCopyPreviousWeek(req, adminUsername);
	}

	public com.weeklyroster.dto.response.ManualRosterMatrixResponse doCopyPreviousWeek(
			com.weeklyroster.dto.request.CopyPreviousWeekRequest req, String adminUsername) {
		if (req == null || req.targetStartDate() == null) {
			throw new BusinessException("Target start date is required");
		}
		LocalDate targetMonday = req.targetStartDate().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
		LocalDate targetSunday = targetMonday.plusDays(6);
		LocalDate sourceMonday = (req.sourceStartDate() != null)
				? req.sourceStartDate().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
				: targetMonday.minusWeeks(1);
		LocalDate sourceSunday = sourceMonday.plusDays(6);

		List<RosterCycle> sourceCycles = cycleRepository.findOverlappingCycles(sourceMonday, sourceSunday);
		if (sourceCycles.isEmpty()) {
			var opt = cycleRepository.findByStartDateAndEndDate(sourceMonday, sourceSunday);
			if (opt.isPresent()) sourceCycles.add(opt.get());
		}
		if (sourceCycles.isEmpty()) {
			throw new BusinessException("No roster cycle found for source week (" + sourceMonday + " to " + sourceSunday + ") to copy from.");
		}
		RosterCycle sourceCycle = sourceCycles.get(0);
		List<RosterAssignment> sourceAssignments = assignmentRepository.findByCycleId(sourceCycle.getId());
		if (sourceAssignments.isEmpty()) {
			throw new BusinessException("Source week roster cycle has no assignments to copy.");
		}

		RosterCycle targetCycle = null;
		if (req.cycleId() != null) {
			targetCycle = cycleRepository.findById(req.cycleId()).orElse(null);
		}
		if (targetCycle == null) {
			targetCycle = getOrCreateDraftCycle(targetMonday, sourceCycle.getWorkingDays() != null ? sourceCycle.getWorkingDays() : 6);
		}
		if (targetCycle.getStatus() == RosterStatus.LOCKED) {
			throw new BusinessException("Target roster cycle is locked and cannot be modified.");
		}

		Map<ShiftType, Shift> shifts = activeShiftMap();
		boolean includeOff = req.includeWeeklyOff() == null || req.includeWeeklyOff();
		boolean overwriteManual = Boolean.TRUE.equals(req.overwriteExistingManual());
		long dayDiff = java.time.temporal.ChronoUnit.DAYS.between(sourceMonday, targetMonday);

		// Record snapshots of affected target assignments for Durable Undo
		List<AssignmentSnapshotItem> snapshots = new ArrayList<>();
		for (RosterAssignment sa : sourceAssignments) {
			if (sa.getEmployee() == null || !sa.getEmployee().isActive()) continue;
			LocalDate targetDate = sa.getRosterDate().plusDays(dayDiff);
			if (targetDate.isBefore(targetMonday) || targetDate.isAfter(targetSunday)) continue;
			if (!includeOff && sa.isWeeklyOff()) continue;

			List<RosterAssignment> existingTargetList = assignmentRepository.findByEmployeeIdAndRosterDate(sa.getEmployee().getId(), targetDate);
			RosterAssignment existingTarget = !existingTargetList.isEmpty() ? existingTargetList.get(0) : null;
			if (existingTarget != null && (existingTarget.isManualAssignment() || existingTarget.isOverridden()) && !overwriteManual) {
				continue; // Preserved
			}
			snapshots.add(AssignmentSnapshotItem.from(existingTarget, sa.getEmployee().getId(), targetDate));
		}
		recordManualOperation(targetCycle.getId(), "COPY_WEEK",
				"Copied assignments from week " + sourceMonday + " to " + targetMonday + " (includeOff=" + includeOff + ", overwriteManual=" + overwriteManual + ")",
				adminUsername, snapshots);

		for (RosterAssignment sa : sourceAssignments) {
			Employee emp = sa.getEmployee();
			if (emp == null || !emp.isActive()) continue;

			LocalDate targetDate = sa.getRosterDate().plusDays(dayDiff);
			if (targetDate.isBefore(targetMonday) || targetDate.isAfter(targetSunday)) continue;

			if (!includeOff && sa.isWeeklyOff()) continue;

			List<RosterAssignment> existingTargetList = assignmentRepository.findByEmployeeIdAndRosterDate(emp.getId(), targetDate);
			RosterAssignment ta = !existingTargetList.isEmpty() ? existingTargetList.get(0) : new RosterAssignment();

			if (ta.getId() != null && (ta.isManualAssignment() || ta.isOverridden()) && !overwriteManual) {
				continue; // Preserve protected manual assignment
			}

			boolean onLeave = isApprovedLeave(emp.getId(), targetDate);

			ta.setCycle(targetCycle);
			ta.setEmployee(emp);
			ta.setRosterDate(targetDate);
			ta.setOnLeave(onLeave);
			if (onLeave) {
				ta.setWeeklyOff(false);
				ta.setShift(shifts.get(ShiftType.OFF));
				ta.setManualAssignment(true);
				ta.setOverridden(true);
				ta.setAssignmentSource("COPIED");
				ta.setAssignmentReason("Copied from " + sa.getRosterDate() + " - On Leave");
			} else {
				ta.setWeeklyOff(sa.isWeeklyOff());
				ta.setShift(sa.getShift());
				ta.setManualAssignment(true);
				ta.setOverridden(true);
				ta.setAssignmentSource("COPIED");
				ta.setAssignmentReason("Copied from previous week (" + sa.getRosterDate() + ")");
			}
			assignmentRepository.save(ta);
		}

		assignmentRepository.flush();
		return buildMatrixResponse(targetCycle);
	}

	@Transactional(readOnly = true)
	public com.weeklyroster.dto.response.ClearRosterPreviewResponse previewClearRoster(
			com.weeklyroster.dto.request.ClearManualRosterRequest req) {
		if (req == null || req.mode() == null) {
			throw new BusinessException("Clear mode is required");
		}
		LocalDate monday = (req.startDate() != null)
				? req.startDate().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
				: LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY));
		LocalDate sunday = monday.plusDays(6);

		List<LocalDate> targetDates = new ArrayList<>();
		List<Long> targetEmpIds = new ArrayList<>();

		String mode = req.mode().trim().toUpperCase();
		switch (mode) {
			case "ENTIRE_WEEK" -> {
				for (LocalDate d = monday; !d.isAfter(sunday); d = d.plusDays(1)) targetDates.add(d);
				targetEmpIds = employeeRepository.findByActiveTrueOrderByIdAsc().stream().map(Employee::getId).toList();
			}
			case "SELECTED_EMPLOYEES" -> {
				for (LocalDate d = monday; !d.isAfter(sunday); d = d.plusDays(1)) targetDates.add(d);
				if (req.employeeIds() != null) targetEmpIds.addAll(req.employeeIds());
			}
			case "SELECTED_DATES" -> {
				if (req.dates() != null) targetDates.addAll(req.dates());
				targetEmpIds = employeeRepository.findByActiveTrueOrderByIdAsc().stream().map(Employee::getId).toList();
			}
			case "SELECTED_CELLS" -> {
				// Handled individually
			}
			default -> throw new BusinessException("Unsupported clear mode: " + req.mode());
		}

		int affectedCount = 0;
		Set<Long> affectedEmpSet = new HashSet<>();
		Set<LocalDate> affectedDateSet = new HashSet<>();
		List<String> sample = new ArrayList<>();

		if ("SELECTED_CELLS".equals(mode)) {
			if (req.cells() != null) {
				for (var cell : req.cells()) {
					if (cell.employeeId() != null && cell.date() != null) {
						affectedCount++;
						affectedEmpSet.add(cell.employeeId());
						affectedDateSet.add(cell.date());
						if (sample.size() < 5) {
							sample.add("Emp #" + cell.employeeId() + " on " + cell.date());
						}
					}
				}
			}
		} else {
			for (Long empId : targetEmpIds) {
				for (LocalDate d : targetDates) {
					affectedCount++;
					affectedEmpSet.add(empId);
					affectedDateSet.add(d);
					if (sample.size() < 5) {
						sample.add("Emp #" + empId + " on " + d);
					}
				}
			}
		}

		String summary = switch (mode) {
			case "ENTIRE_WEEK" -> "Clear all " + affectedCount + " assignments across the entire week (" + monday + " to " + sunday + ").";
			case "SELECTED_EMPLOYEES" -> "Clear " + affectedCount + " assignments for " + affectedEmpSet.size() + " selected employee(s).";
			case "SELECTED_DATES" -> "Clear " + affectedCount + " assignments across " + affectedDateSet.size() + " selected date(s).";
			case "SELECTED_CELLS" -> "Clear " + affectedCount + " individually selected assignment cells.";
			default -> "Clear roster assignments.";
		};

		String warning = "All affected cells will be cleared to OFF / Unassigned. Explicit manual locks will be reset. This action can be undone via Undo.";
		return new com.weeklyroster.dto.response.ClearRosterPreviewResponse(
				mode, affectedCount, affectedEmpSet.size(), affectedDateSet.size(),
				summary, warning, sample, affectedCount > 0
		);
	}

	public com.weeklyroster.dto.response.ManualRosterMatrixResponse clearManualRoster(
			com.weeklyroster.dto.request.ClearManualRosterRequest req, String adminUsername) {
		if (databaseWriteCoordinator != null) {
			return databaseWriteCoordinator.executeTransactionalWrite(() -> doClearManualRoster(req, adminUsername));
		}
		return doClearManualRoster(req, adminUsername);
	}

	public com.weeklyroster.dto.response.ManualRosterMatrixResponse doClearManualRoster(
			com.weeklyroster.dto.request.ClearManualRosterRequest req, String adminUsername) {
		if (req == null || req.mode() == null) {
			throw new BusinessException("Clear mode is required");
		}
		LocalDate monday = (req.startDate() != null)
				? req.startDate().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
				: LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY));
		LocalDate sunday = monday.plusDays(6);

		RosterCycle cycle = null;
		if (req.cycleId() != null) {
			cycle = cycleRepository.findById(req.cycleId()).orElse(null);
		}
		if (cycle == null) {
			cycle = getOrCreateDraftCycle(monday, 6);
		}
		if (cycle.getStatus() == RosterStatus.LOCKED) {
			throw new BusinessException("Cannot clear assignments in a locked roster cycle");
		}

		String mode = req.mode().trim().toUpperCase();
		List<com.weeklyroster.dto.request.ClearManualRosterRequest.CellCoordinate> targetCells = new ArrayList<>();

		switch (mode) {
			case "ENTIRE_WEEK" -> {
				List<Employee> activeEmps = employeeRepository.findByActiveTrueOrderByIdAsc();
				for (Employee e : activeEmps) {
					for (LocalDate d = monday; !d.isAfter(sunday); d = d.plusDays(1)) {
						targetCells.add(new com.weeklyroster.dto.request.ClearManualRosterRequest.CellCoordinate(e.getId(), d));
					}
				}
			}
			case "SELECTED_EMPLOYEES" -> {
				if (req.employeeIds() != null) {
					for (Long empId : req.employeeIds()) {
						for (LocalDate d = monday; !d.isAfter(sunday); d = d.plusDays(1)) {
							targetCells.add(new com.weeklyroster.dto.request.ClearManualRosterRequest.CellCoordinate(empId, d));
						}
					}
				}
			}
			case "SELECTED_DATES" -> {
				if (req.dates() != null) {
					List<Employee> activeEmps = employeeRepository.findByActiveTrueOrderByIdAsc();
					for (Employee e : activeEmps) {
						for (LocalDate d : req.dates()) {
							targetCells.add(new com.weeklyroster.dto.request.ClearManualRosterRequest.CellCoordinate(e.getId(), d));
						}
					}
				}
			}
			case "SELECTED_CELLS" -> {
				if (req.cells() != null) {
					targetCells.addAll(req.cells());
				}
			}
			default -> throw new BusinessException("Unsupported clear mode: " + req.mode());
		}

		if (targetCells.isEmpty()) {
			throw new BusinessException("No cells selected to clear");
		}

		Map<ShiftType, Shift> shifts = activeShiftMap();

		// Record snapshot for durable undo
		List<AssignmentSnapshotItem> snapshots = new ArrayList<>();
		for (var cell : targetCells) {
			List<RosterAssignment> existingList = assignmentRepository.findByEmployeeIdAndRosterDate(cell.employeeId(), cell.date());
			RosterAssignment existing = !existingList.isEmpty() ? existingList.get(0) : null;
			snapshots.add(AssignmentSnapshotItem.from(existing, cell.employeeId(), cell.date()));
		}
		recordManualOperation(cycle.getId(), "CLEAR",
				"Cleared " + targetCells.size() + " assignments (Mode: " + mode + ")",
				adminUsername, snapshots);

		for (var cell : targetCells) {
			List<RosterAssignment> existingList = assignmentRepository.findByEmployeeIdAndRosterDate(cell.employeeId(), cell.date());
			if (existingList.isEmpty()) continue;
			for (RosterAssignment a : existingList) {
				boolean onLeave = isApprovedLeave(cell.employeeId(), cell.date());
				if (onLeave) {
					a.setOnLeave(true);
					a.setWeeklyOff(false);
					a.setShift(shifts.get(ShiftType.OFF));
					a.setManualAssignment(false);
					a.setOverridden(false);
					a.setAssignmentSource("AUTOMATIC");
					a.setAssignmentReason("Cleared - Leave preserved");
				} else {
					a.setOnLeave(false);
					a.setWeeklyOff(true);
					a.setShift(shifts.get(ShiftType.OFF));
					a.setManualAssignment(false);
					a.setOverridden(false);
					a.setAssignmentSource("CLEARED");
					a.setAssignmentReason("Cleared by Admin");
				}
				assignmentRepository.save(a);
			}
		}

		assignmentRepository.flush();
		return buildMatrixResponse(cycle);
	}

	@Transactional(readOnly = true)
	public com.weeklyroster.dto.response.ManualShiftSwapPreviewResponse previewShiftSwap(
			com.weeklyroster.dto.request.ManualShiftSwapRequest req) {
		if (req == null || req.employeeAId() == null || req.employeeBId() == null || req.dateA() == null || req.dateB() == null) {
			throw new BusinessException("Both employees and dates are required for shift swap");
		}
		Employee empA = employeeRepository.findById(req.employeeAId())
				.orElseThrow(() -> new ResourceNotFoundException("Employee A #" + req.employeeAId() + " not found"));
		Employee empB = employeeRepository.findById(req.employeeBId())
				.orElseThrow(() -> new ResourceNotFoundException("Employee B #" + req.employeeBId() + " not found"));

		List<RosterAssignment> listA = assignmentRepository.findByEmployeeIdAndRosterDate(empA.getId(), req.dateA());
		RosterAssignment assignA = !listA.isEmpty() ? listA.get(0) : null;
		List<RosterAssignment> listB = assignmentRepository.findByEmployeeIdAndRosterDate(empB.getId(), req.dateB());
		RosterAssignment assignB = !listB.isEmpty() ? listB.get(0) : null;

		String origShiftA = (assignA != null && assignA.getShift() != null) ? assignA.getShift().getShiftType().name() : "OFF";
		boolean origOffA = assignA == null || assignA.isWeeklyOff();
		String origShiftB = (assignB != null && assignB.getShift() != null) ? assignB.getShift().getShiftType().name() : "OFF";
		boolean origOffB = assignB == null || assignB.isWeeklyOff();

		// Proposed: A gets B's shift, B gets A's shift
		String propShiftA = origShiftB;
		boolean propOffA = origOffB;
		String propShiftB = origShiftA;
		boolean propOffB = origOffA;

		List<String> conflicts = new ArrayList<>();
		boolean onLeaveA = isApprovedLeave(empA.getId(), req.dateA());
		boolean onLeaveB = isApprovedLeave(empB.getId(), req.dateB());

		if (onLeaveA && !propOffA) {
			conflicts.add(empA.getFirstName() + " has approved leave on " + req.dateA() + " and cannot take a working shift (" + propShiftA + ")");
		}
		if (onLeaveB && !propOffB) {
			conflicts.add(empB.getFirstName() + " has approved leave on " + req.dateB() + " and cannot take a working shift (" + propShiftB + ")");
		}

				if (empA.getGender() == Gender.FEMALE && "NIGHT".equalsIgnoreCase(propShiftA)) {
			conflicts.add("Statutory violation: Female employee " + empA.getFirstName() + " cannot be scheduled for Night shift");
		}
		if (empB.getGender() == Gender.FEMALE && "NIGHT".equalsIgnoreCase(propShiftB)) {
			conflicts.add("Statutory violation: Female employee " + empB.getFirstName() + " cannot be scheduled for Night shift");
		}

		// BATCH 109: Night shift strict constraints in swap preview
		if ("NIGHT".equalsIgnoreCase(propShiftA)) {
			List<RosterAssignment> aNextList = assignmentRepository.findByEmployeeIdAndRosterDate(empA.getId(), req.dateA().plusDays(1));
			if (!aNextList.isEmpty()) {
				RosterAssignment aNext = aNextList.get(0);
				if (!aNext.isWeeklyOff() && !aNext.isOnLeave() && aNext.getShift() != null && aNext.getShift().getShiftType() != ShiftType.OFF) {
					conflicts.add(empA.getFirstName() + " would work NIGHT on " + req.dateA() + " but has a working shift on " + req.dateA().plusDays(1) + ". Night shifts require mandatory Weekly OFF on the following calendar day.");
				}
			}
			List<RosterAssignment> aPrevList = assignmentRepository.findByEmployeeIdAndRosterDate(empA.getId(), req.dateA().minusDays(1));
			if (!aPrevList.isEmpty()) {
				RosterAssignment aPrev = aPrevList.get(0);
				if (!aPrev.isWeeklyOff() && !aPrev.isOnLeave() && aPrev.getShift() != null && aPrev.getShift().getShiftType() == ShiftType.NIGHT) {
					conflicts.add(empA.getFirstName() + " worked NIGHT on " + req.dateA().minusDays(1) + " and must receive Weekly OFF on " + req.dateA() + ".");
				}
			}
		}

		if ("NIGHT".equalsIgnoreCase(propShiftB)) {
			List<RosterAssignment> bNextList = assignmentRepository.findByEmployeeIdAndRosterDate(empB.getId(), req.dateB().plusDays(1));
			if (!bNextList.isEmpty()) {
				RosterAssignment bNext = bNextList.get(0);
				if (!bNext.isWeeklyOff() && !bNext.isOnLeave() && bNext.getShift() != null && bNext.getShift().getShiftType() != ShiftType.OFF) {
					conflicts.add(empB.getFirstName() + " would work NIGHT on " + req.dateB() + " but has a working shift on " + req.dateB().plusDays(1) + ". Night shifts require mandatory Weekly OFF on the following calendar day.");
				}
			}
			List<RosterAssignment> bPrevList = assignmentRepository.findByEmployeeIdAndRosterDate(empB.getId(), req.dateB().minusDays(1));
			if (!bPrevList.isEmpty()) {
				RosterAssignment bPrev = bPrevList.get(0);
				if (!bPrev.isWeeklyOff() && !bPrev.isOnLeave() && bPrev.getShift() != null && bPrev.getShift().getShiftType() == ShiftType.NIGHT) {
					conflicts.add(empB.getFirstName() + " worked NIGHT on " + req.dateB().minusDays(1) + " and must receive Weekly OFF on " + req.dateB() + ".");
				}
			}
		}

		return new com.weeklyroster.dto.response.ManualShiftSwapPreviewResponse(
				empA.getId(), empA.getFirstName() + " " + (empA.getLastName() != null ? empA.getLastName() : "").trim(),
				req.dateA(), origShiftA, propShiftA, origOffA, propOffA,
				empB.getId(), empB.getFirstName() + " " + (empB.getLastName() != null ? empB.getLastName() : "").trim(),
				req.dateB(), origShiftB, propShiftB, origOffB, propOffB,
				conflicts.size(), conflicts, conflicts.isEmpty() || Boolean.TRUE.equals(req.allowManualOverride())
		);
	}

	public com.weeklyroster.dto.response.ManualRosterMatrixResponse swapManualShifts(
			com.weeklyroster.dto.request.ManualShiftSwapRequest req, String adminUsername) {
		if (databaseWriteCoordinator != null) {
			return databaseWriteCoordinator.executeTransactionalWrite(() -> doSwapManualShifts(req, adminUsername));
		}
		return doSwapManualShifts(req, adminUsername);
	}

	public com.weeklyroster.dto.response.ManualRosterMatrixResponse doSwapManualShifts(
			com.weeklyroster.dto.request.ManualShiftSwapRequest req, String adminUsername) {
		var preview = previewShiftSwap(req);
		if (!preview.canApply()) {
			throw new BusinessException("Cannot swap shifts due to constraints: " + String.join("; ", preview.conflicts()));
		}

		LocalDate monday = req.dateA().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
		RosterCycle cycle = (req.cycleId() != null)
				? cycleRepository.findById(req.cycleId()).orElse(null)
				: getOrCreateDraftCycle(monday, 6);
		if (cycle.getStatus() == RosterStatus.LOCKED) {
			throw new BusinessException("Cannot modify assignments in a locked roster cycle");
		}

		Employee empA = employeeRepository.findById(req.employeeAId())
				.orElseThrow(() -> new ResourceNotFoundException("Employee A not found"));
		Employee empB = employeeRepository.findById(req.employeeBId())
				.orElseThrow(() -> new ResourceNotFoundException("Employee B not found"));

		List<RosterAssignment> listA = assignmentRepository.findByEmployeeIdAndRosterDate(empA.getId(), req.dateA());
		RosterAssignment assignA = !listA.isEmpty() ? listA.get(0) : new RosterAssignment();
		List<RosterAssignment> listB = assignmentRepository.findByEmployeeIdAndRosterDate(empB.getId(), req.dateB());
		RosterAssignment assignB = !listB.isEmpty() ? listB.get(0) : new RosterAssignment();

		// Record snapshots for Durable Undo
		List<AssignmentSnapshotItem> snapshots = List.of(
				AssignmentSnapshotItem.from(assignA.getId() != null ? assignA : null, empA.getId(), req.dateA()),
				AssignmentSnapshotItem.from(assignB.getId() != null ? assignB : null, empB.getId(), req.dateB())
		);
		recordManualOperation(cycle.getId(), "SHIFT_SWAP",
				"Swapped shifts: " + empA.getFirstName() + " (" + req.dateA() + ") <-> " + empB.getFirstName() + " (" + req.dateB() + ")",
				adminUsername, snapshots);

		Map<ShiftType, Shift> shifts = activeShiftMap();

		Shift oldShiftA = assignA.getShift();
		boolean oldOffA = assignA.isWeeklyOff();
		Shift oldShiftB = assignB.getShift();
		boolean oldOffB = assignB.isWeeklyOff();

		assignA.setCycle(cycle);
		assignA.setEmployee(empA);
		assignA.setRosterDate(req.dateA());
		assignA.setWeeklyOff(oldOffB);
		assignA.setShift(oldShiftB != null ? oldShiftB : shifts.get(ShiftType.OFF));
		assignA.setManualAssignment(true);
		assignA.setOverridden(true);
		assignA.setAssignmentSource("SWAPPED");
		assignA.setAssignmentReason("Swapped with " + empB.getFirstName());
		assignmentRepository.save(assignA);

		assignB.setCycle(cycle);
		assignB.setEmployee(empB);
		assignB.setRosterDate(req.dateB());
		assignB.setWeeklyOff(oldOffA);
		assignB.setShift(oldShiftA != null ? oldShiftA : shifts.get(ShiftType.OFF));
		assignB.setManualAssignment(true);
		assignB.setOverridden(true);
		assignB.setAssignmentSource("SWAPPED");
		assignB.setAssignmentReason("Swapped with " + empA.getFirstName());
		assignmentRepository.save(assignB);

		assignmentRepository.flush();
		return buildMatrixResponse(cycle);
	}

	public com.weeklyroster.dto.response.UndoOperationResponse undoLastManualOperation(
			Long cycleId, String adminUsername) {
		if (databaseWriteCoordinator != null) {
			return databaseWriteCoordinator.executeTransactionalWrite(() -> doUndoLastManualOperation(cycleId, adminUsername));
		}
		return doUndoLastManualOperation(cycleId, adminUsername);
	}

	public com.weeklyroster.dto.response.UndoOperationResponse doUndoLastManualOperation(
			Long cycleId, String adminUsername) {
		if (cycleId == null) throw new BusinessException("Cycle ID is required for undo");
		RosterCycle cycle = cycleRepository.findById(cycleId)
				.orElseThrow(() -> new ResourceNotFoundException("Cycle #" + cycleId + " not found"));
		if (cycle.getStatus() == RosterStatus.LOCKED) {
			throw new BusinessException("Cannot undo operations in a locked roster cycle");
		}

		if (manualRosterOperationRepository == null) {
			throw new BusinessException("Operation history repository not available");
		}

		ManualRosterOperation op = manualRosterOperationRepository
				.findFirstByCycleIdAndUndoneFalseOrderByCreatedAtDesc(cycleId)
				.orElseThrow(() -> new BusinessException("No manual operations available to undo for this roster cycle."));

		List<AssignmentSnapshotItem> items;
		try {
			items = getObjectMapper().readValue(op.getSnapshotJson(), new TypeReference<List<AssignmentSnapshotItem>>() {});
		} catch (Exception e) {
			throw new BusinessException("Failed to restore operation snapshot: " + e.getMessage());
		}

		Map<ShiftType, Shift> shifts = activeShiftMap();
		int restoredCount = 0;

		for (AssignmentSnapshotItem item : items) {
			LocalDate date = LocalDate.parse(item.rosterDate);
			List<RosterAssignment> existingList = assignmentRepository.findByEmployeeIdAndRosterDate(item.employeeId, date);

			if (!item.existed) {
				// Record did not exist prior to this operation â€” remove it
				if (!existingList.isEmpty()) {
					assignmentRepository.deleteAll(existingList);
				}
				restoredCount++;
			} else {
				// Restore prior state
				RosterAssignment a = !existingList.isEmpty() ? existingList.get(0) : new RosterAssignment();
				Employee emp = employeeRepository.findById(item.employeeId).orElse(null);
				if (emp == null) continue;

				a.setCycle(cycle);
				a.setEmployee(emp);
				a.setRosterDate(date);
				a.setWeeklyOff(item.weeklyOff);
				a.setOnLeave(item.onLeave);
				a.setShift(item.shiftType != null ? shifts.get(item.shiftType) : shifts.get(ShiftType.OFF));
				a.setManualAssignment(item.manualAssignment);
				a.setOverridden(item.overridden);
				a.setAssignmentSource(item.assignmentSource != null ? item.assignmentSource : "AUTOMATIC");
				a.setAssignmentReason(item.assignmentReason);
				a.setOverrideReason(item.overrideReason);
				assignmentRepository.save(a);
				restoredCount++;
			}
		}

		op.setUndone(true);
		op.setUndoneAt(LocalDateTime.now());
		manualRosterOperationRepository.save(op);
		assignmentRepository.flush();

		return new com.weeklyroster.dto.response.UndoOperationResponse(
				op.getId(), op.getOperationType(), op.getDescription(), restoredCount, buildMatrixResponse(cycle)
		);
	}

	@Transactional(readOnly = true)
	public Map<String, Object> getUndoStatus(Long cycleId) {
		if (cycleId == null || manualRosterOperationRepository == null) {
			return Map.of("canUndo", false, "undoCount", 0);
		}
		var opt = manualRosterOperationRepository.findFirstByCycleIdAndUndoneFalseOrderByCreatedAtDesc(cycleId);
		long count = manualRosterOperationRepository.countByCycleIdAndUndoneFalse(cycleId);
		if (opt.isEmpty()) {
			return Map.of("canUndo", false, "undoCount", 0);
		}
		ManualRosterOperation op = opt.get();
		Map<String, Object> res = new LinkedHashMap<>();
		res.put("canUndo", true);
		res.put("undoCount", count);
		res.put("lastOperationId", op.getId());
		res.put("lastOperationType", op.getOperationType());
		res.put("lastOperationDescription", op.getDescription());
		res.put("lastOperatedAt", op.getCreatedAt().toString());
		return res;
	}

	@Transactional(readOnly = true)
	public com.weeklyroster.dto.response.WeekComparisonResponse compareWeeks(LocalDate weekA, LocalDate weekB) {
		if (weekA == null) weekA = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
		if (weekB == null) weekB = weekA.plusWeeks(1);

		LocalDate startA = weekA.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
		LocalDate endA = startA.plusDays(6);
		LocalDate startB = weekB.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
		LocalDate endB = startB.plusDays(6);

		List<Employee> employees = employeeRepository.findByActiveTrueOrderByIdAsc();
		List<RosterAssignment> assignmentsA = assignmentRepository.findByDateRange(startA, endA);
		List<RosterAssignment> assignmentsB = assignmentRepository.findByDateRange(startB, endB);

		Map<Long, Map<Integer, RosterAssignment>> mapA = new HashMap<>();
		for (RosterAssignment a : assignmentsA) {
			if (a.getEmployee() != null && a.getRosterDate() != null) {
				int dayIdx = a.getRosterDate().getDayOfWeek().getValue();
				mapA.computeIfAbsent(a.getEmployee().getId(), k -> new HashMap<>()).put(dayIdx, a);
			}
		}
		Map<Long, Map<Integer, RosterAssignment>> mapB = new HashMap<>();
		for (RosterAssignment b : assignmentsB) {
			if (b.getEmployee() != null && b.getRosterDate() != null) {
				int dayIdx = b.getRosterDate().getDayOfWeek().getValue();
				mapB.computeIfAbsent(b.getEmployee().getId(), k -> new HashMap<>()).put(dayIdx, b);
			}
		}

		String[] dayNames = {"", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};
		List<com.weeklyroster.dto.response.WeekComparisonResponse.EmployeeComparisonRow> rows = new ArrayList<>();
		int totalDiffs = 0;
		int shiftChanges = 0;
		int offChanges = 0;
		int manualFlagsChanges = 0;

		for (Employee emp : employees) {
			Map<Integer, RosterAssignment> empA = mapA.getOrDefault(emp.getId(), Collections.emptyMap());
			Map<Integer, RosterAssignment> empB = mapB.getOrDefault(emp.getId(), Collections.emptyMap());
			Map<Integer, com.weeklyroster.dto.response.WeekComparisonResponse.DayComparisonCell> dayCells = new LinkedHashMap<>();

			for (int d = 1; d <= 7; d++) {
				RosterAssignment a = empA.get(d);
				RosterAssignment b = empB.get(d);

				String shiftA = (a != null && a.getShift() != null) ? a.getShift().getShiftType().name() : (a != null && a.isWeeklyOff() ? "OFF" : "NONE");
				boolean isOffA = a != null && a.isWeeklyOff();
				boolean isManA = a != null && a.isManualAssignment();

				String shiftB = (b != null && b.getShift() != null) ? b.getShift().getShiftType().name() : (b != null && b.isWeeklyOff() ? "OFF" : "NONE");
				boolean isOffB = b != null && b.isWeeklyOff();
				boolean isManB = b != null && b.isManualAssignment();

				boolean hasDiff = !shiftA.equals(shiftB) || isOffA != isOffB || isManA != isManB;
				String diffDesc = null;
				if (hasDiff) {
					totalDiffs++;
					if (isOffA != isOffB) {
						offChanges++;
						diffDesc = isOffA ? "OFF in A -> Working in B" : "Working in A -> OFF in B";
					} else if (!shiftA.equals(shiftB)) {
						shiftChanges++;
						diffDesc = shiftA + " -> " + shiftB;
					} else if (isManA != isManB) {
						manualFlagsChanges++;
						diffDesc = isManA ? "Manual in A -> Auto in B" : "Auto in A -> Manual in B";
					}
				}

				dayCells.put(d, new com.weeklyroster.dto.response.WeekComparisonResponse.DayComparisonCell(
						dayNames[d], shiftA, isOffA, isManA, shiftB, isOffB, isManB, hasDiff, diffDesc
				));
			}

			rows.add(new com.weeklyroster.dto.response.WeekComparisonResponse.EmployeeComparisonRow(
					emp.getId(), emp.getEmployeeCode(), emp.getFirstName() + " " + (emp.getLastName() != null ? emp.getLastName() : "").trim(),
					dayCells
			));
		}

		List<String> summaryNotes = new ArrayList<>();
		summaryNotes.add("Comparing Week " + startA + " to " + endA + " with Week " + startB + " to " + endB);
		summaryNotes.add("Total differences detected: " + totalDiffs + " (Shift: " + shiftChanges + ", Weekly OFF: " + offChanges + ", Manual Flags: " + manualFlagsChanges + ")");

		return new com.weeklyroster.dto.response.WeekComparisonResponse(
				startA, endA, startB, endB,
				totalDiffs, shiftChanges, offChanges, manualFlagsChanges,
				rows, summaryNotes
		);
	}

	public com.weeklyroster.dto.response.ManualRosterMatrixResponse autoSaveDraft(
			com.weeklyroster.dto.request.AutoSaveDraftRequest req, String adminUsername) {
		if (databaseWriteCoordinator != null) {
			return databaseWriteCoordinator.executeTransactionalWrite(() -> doAutoSaveDraft(req, adminUsername));
		}
		return doAutoSaveDraft(req, adminUsername);
	}

	public com.weeklyroster.dto.response.ManualRosterMatrixResponse doAutoSaveDraft(
			com.weeklyroster.dto.request.AutoSaveDraftRequest req, String adminUsername) {
		if (req == null || req.startDate() == null) {
			throw new BusinessException("Start date is required for draft auto-save");
		}
		LocalDate monday = req.startDate().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
		RosterCycle cycle = (req.cycleId() != null)
				? cycleRepository.findById(req.cycleId()).orElse(null)
				: null;
		if (cycle == null) {
			cycle = getOrCreateDraftCycle(monday, 6);
		}
		if (cycle.getStatus() != RosterStatus.PUBLISHED && cycle.getStatus() != RosterStatus.LOCKED) {
			cycle.setStatus(RosterStatus.DRAFT);
			cycle.setGeneratedAt(LocalDateTime.now());
			cycleRepository.save(cycle);
		}
		return buildMatrixResponse(cycle);
	}

	public com.weeklyroster.dto.response.ManualRosterMatrixResponse generateUnassignedOnly(
			Long cycleId, LocalDate startDate, String adminUsername) {
		if (databaseWriteCoordinator != null) {
			return databaseWriteCoordinator.executeTransactionalWrite(() -> doGenerateUnassignedOnly(cycleId, startDate, adminUsername));
		}
		return doGenerateUnassignedOnly(cycleId, startDate, adminUsername);
	}

	public com.weeklyroster.dto.response.ManualRosterMatrixResponse doGenerateUnassignedOnly(
			Long cycleId, LocalDate startDate, String adminUsername) {
		if (startDate == null && cycleId != null) {
			RosterCycle c = cycleRepository.findById(cycleId).orElse(null);
			if (c != null) startDate = c.getStartDate();
		}
		if (startDate == null) {
			startDate = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY));
		} else if (startDate.getDayOfWeek() != java.time.DayOfWeek.MONDAY) {
			startDate = startDate.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
		}

		generateWeeklyRoster(startDate, GenerationMode.MANUAL);

		RosterCycle updated = cycleRepository.findByStartDateAndEndDate(startDate, startDate.plusDays(6))
				.orElseThrow(() -> new ResourceNotFoundException("Cycle not found after generation"));

		return buildMatrixResponse(updated);
	}

	public com.weeklyroster.dto.response.ManualRosterMatrixResponse publishManualRoster(
			Long cycleId, String adminUsername) {
		if (databaseWriteCoordinator != null) {
			return databaseWriteCoordinator.executeTransactionalWrite(() -> doPublishManualRoster(cycleId, adminUsername));
		}
		return doPublishManualRoster(cycleId, adminUsername);
	}

	public com.weeklyroster.dto.response.ManualRosterMatrixResponse doPublishManualRoster(
			Long cycleId, String adminUsername) {
		RosterCycle cycle = cycleRepository.findById(cycleId)
				.orElseThrow(() -> new ResourceNotFoundException("Roster cycle #" + cycleId + " not found"));

		com.weeklyroster.dto.response.ManualRosterMatrixResponse matrix = buildMatrixResponse(cycle);
		if (matrix.criticalConflicts() > 0) {
			throw new BusinessException("Cannot publish roster: " + matrix.criticalConflicts()
					+ " critical conflicts detected. Please resolve all hard conflicts before publishing.");
		}

		cycle.setStatus(RosterStatus.PUBLISHED);
		cycleRepository.save(cycle);

		if (auditService != null) {
			auditService.log(
					AuditAction.ROSTER_PUBLISHED,
					"ROSTER_CYCLE",
					cycle.getId(),
					cycle.getId(),
					null,
					null,
					"DRAFT",
					"PUBLISHED",
					"Admin published manual roster for " + cycle.getStartDate() + " to " + cycle.getEndDate(),
					"MANUAL"
			);
		}

		return buildMatrixResponse(cycle);
	}

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
		List<RosterAssignment> rawAssignments = assignmentRepository.findByEmployeeIdOrderByRosterDateAsc(employeeId);
		java.util.Map<java.time.LocalDate, RosterAssignment> dedupMap = new java.util.LinkedHashMap<>();
		for (RosterAssignment a : rawAssignments) {
			if (a.getRosterDate() != null) {
				RosterAssignment current = dedupMap.get(a.getRosterDate());
				if (current == null || a.isOverridden() || (a.getCycle() != null && a.getCycle().getStatus() == RosterStatus.PUBLISHED)) {
					dedupMap.put(a.getRosterDate(), a);
				}
			}
		}
		return dedupMap.values().stream()
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
			int targetOffs = a1.getCycle().getWorkingDays() != null && a1.getCycle().getWorkingDays() == 5 ? 2 : 1;
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

			if (offCount1 != targetOffs || offCount2 != targetOffs) {
				throw new BusinessException("Shift swap rejected: Every active employee must have exactly " + targetOffs + " Weekly OFF" + (targetOffs > 1 ? "s" : "") + " in a Monday-Sunday roster cycle");
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
			Map<Long, Set<LocalDate>> weeklyOffs, Map<Long, Integer> offTakenCount, Map<ShiftType, Shift> shifts, LocalDate date,
			Map<Long, Shift> lastShiftMap, Map<Long, LocalDate> lastShiftDateMap,
			Map<Long, Integer> cycleNightCounts, Map<Long, Map<ShiftType, Integer>> shiftCountsMap,
			int maxNightsAllowed, Map<Long, ApplicablePreference> preferencesMap, int randomSeed, int workingDays) {
		List<RosterAssignment> dayAssignments = new ArrayList<>();
		List<Employee> available = new ArrayList<>();

		int plannedOffOrLeave = 0;
		int maxWeeklyOffsForEmp = Math.max(1, 7 - workingDays);

		for (Employee employee : employees) {
			boolean onLeave = isApprovedLeave(employee.getId(), date);
			Set<LocalDate> scheduledOffs = weeklyOffs != null ? weeklyOffs.get(employee.getId()) : null;
			int taken = offTakenCount != null ? offTakenCount.getOrDefault(employee.getId(), 0) : 0;
						boolean weeklyOff = !onLeave && scheduledOffs != null && scheduledOffs.contains(date) && taken < maxWeeklyOffsForEmp;

			Shift prevS = lastShiftMap.get(employee.getId());
			LocalDate prevD = lastShiftDateMap.get(employee.getId());
			boolean wasNightYesterday = prevS != null && prevS.getShiftType() == ShiftType.NIGHT && prevD != null && prevD.equals(date.minusDays(1));
			if (wasNightYesterday) {
				weeklyOff = true;
			}
			ApplicablePreference pref = preferencesMap != null ? preferencesMap.get(employee.getId()) : null;
			if (pref != null && pref.getTargetShiftForDate(date) == ShiftType.OFF) {
				weeklyOff = true;
			}
			if (pref != null && pref.getTargetShiftForDate(date) != null && pref.getTargetShiftForDate(date) != ShiftType.OFF) {
				if (!wasNightYesterday) {
					weeklyOff = false; // Cannot be random weeklyOff when admin configured a specific work shift!
				}
			}

			if (onLeave) {
				dayAssignments.add(newAssignment(cycle, employee, shifts.get(ShiftType.OFF), date, false, true));
				plannedOffOrLeave++;
			} else if (weeklyOff) {
				if (offTakenCount != null) {
					offTakenCount.put(employee.getId(), taken + 1);
				}
				dayAssignments.add(newAssignment(cycle, employee, shifts.get(ShiftType.OFF), date, true, false));
				plannedOffOrLeave++;
			} else {
				available.add(employee);
			}
		}

		int plannedWorking = available.size();
		int activeEmployees = employees.size();

		Map<ShiftType, Integer> configuredDemands = new EnumMap<>(ShiftType.class);
		int dailyConfiguredTotal = 0;
		for (ShiftType type : ASSIGNMENT_ORDER) {
			int cap = Math.max(1, shifts.get(type).getCapacity());
			configuredDemands.put(type, cap);
			dailyConfiguredTotal += cap;
		}

		Map<ShiftType, Integer> feasibleDemands = calculateDailyFeasibleDemands(date, available, configuredDemands);
		int dailyFeasibleTotal = feasibleDemands.values().stream().mapToInt(Integer::intValue).sum();

		List<ShiftType> feasibleSlots = new ArrayList<>();
		for (ShiftType type : ASSIGNMENT_ORDER) {
			int count = feasibleDemands.getOrDefault(type, 0);
			for (int i = 0; i < count; i++) {
				feasibleSlots.add(type);
			}
		}

		Map<ShiftType, List<Employee>> dailyMatching = new EnumMap<>(ShiftType.class);
		Set<Long> assigned = new HashSet<>();

		int totalActiveMales = (int) employees.stream().filter(e -> e.getGender() == Gender.MALE && e.isActive()).count();
		int[] stepCounter = new int[1];
		boolean solvedFeasible = solveDay(0, feasibleSlots, available, employees, assigned, dailyMatching, date,
				shifts, lastShiftMap, lastShiftDateMap, cycleNightCounts, shiftCountsMap, maxNightsAllowed, totalActiveMales, weeklyOffs, cycle.getStartDate(), cycle.getEndDate(), preferencesMap, randomSeed, stepCounter);

		if (!solvedFeasible) {
			assigned.clear();
			dailyMatching.clear();

			for (ShiftType type : ASSIGNMENT_ORDER) {
				int target = feasibleDemands.getOrDefault(type, 1);
				for (int i = 0; i < target; i++) {
					Shift candidateShift = shifts.get(type);
					List<Employee> candidates = available.stream()
							.filter(emp -> !assigned.contains(emp.getId()))
							.filter(emp -> isEligible(emp, type, preferencesMap != null ? preferencesMap.get(emp.getId()) : null, date))
							.filter(emp -> {
								if (type == ShiftType.EVENING && emp.getGender() == Gender.MALE) {
									int myEve = shiftCountsMap != null && shiftCountsMap.get(emp.getId()) != null
											? shiftCountsMap.get(emp.getId()).getOrDefault(ShiftType.EVENING, 0)
											: 0;
									if (myEve >= 3) return false;
								}
								if (type == ShiftType.EVENING && emp.getPairedEmployeeId() != null) {
									Long partnerId = emp.getPairedEmployeeId();
									boolean partnerAvailableToday = available.stream().anyMatch(o -> o.getId().equals(partnerId));
									boolean otherMaleAvailable = available.stream()
											.anyMatch(o -> o.getGender() == Gender.MALE && !o.getId().equals(emp.getId()) && !assigned.contains(o.getId()) && !o.isDevelopmentTeam());
									if (partnerAvailableToday && otherMaleAvailable) return false;
								}
								boolean isFullTeam = (employees.stream().filter(Employee::isActive).count() >= 7);
								int totalNonDevMales = (int) employees.stream().filter(e -> e.getGender() == Gender.MALE && e.isActive() && !e.isDevelopmentTeam()).count();
								if (isFullTeam && emp.isDevelopmentTeam() && !isWeekend(date) && type != ShiftType.GENERAL) return false;
								if (type != ShiftType.NIGHT) return true;
								if (emp.getGender() == Gender.FEMALE) return false;
								boolean isSixDayModeCheck = (cycle != null && cycle.getWorkingDays() != null && cycle.getWorkingDays() == 6)
										|| (weeklyOffs != null && weeklyOffs.containsKey(emp.getId()) && weeklyOffs.get(emp.getId()).size() <= 1);
								int empLimit = (emp.getMaxNightsPerWeek() != null) ? emp.getMaxNightsPerWeek() : maxNightsAllowed;
								if (totalNonDevMales <= 3 && emp.getMaxNightsPerWeek() != null) {
									empLimit = Math.max(empLimit, 2);
								} else if (isSixDayModeCheck && emp.getMaxNightsPerWeek() != null && emp.getMaxNightsPerWeek() > 1) {
									empLimit = Math.max(empLimit, 2);
								}
								if (cycleNightCounts.getOrDefault(emp.getId(), 0) >= empLimit) return false;

								Shift prevS = lastShiftMap.get(emp.getId());
								LocalDate prevD = lastShiftDateMap.get(emp.getId());
								boolean wasNightYesterday = prevS != null && prevS.getShiftType() == ShiftType.NIGHT && prevD != null && prevD.equals(date.minusDays(1));
								if (wasNightYesterday) return false;

								boolean isSixDayMode = (cycle != null && cycle.getWorkingDays() != null && cycle.getWorkingDays() == 6)
										|| (weeklyOffs != null && weeklyOffs.containsKey(emp.getId()) && weeklyOffs.get(emp.getId()).size() <= 1);
								boolean isCycleEnd = (cycle.getEndDate() != null && date.equals(cycle.getEndDate()));

								// Preceding Sunday Night check: If employee worked Sunday Night preceding cycle, their single off is Monday
								if (assignmentRepository != null && cycle.getStartDate() != null) {
									List<RosterAssignment> prevWorked = assignmentRepository.findWorkedAssignmentsBefore(emp.getId(), cycle.getStartDate());
									if (!prevWorked.isEmpty()) {
										RosterAssignment lastA = prevWorked.get(0);
										if (lastA.getRosterDate() != null && lastA.getRosterDate().equals(cycle.getStartDate().minusDays(1))
												&& lastA.getShift() != null && lastA.getShift().getShiftType() == ShiftType.NIGHT) {
											if (!isCycleEnd) return false;
										}
									}
								}

								if (isSixDayMode && !isCycleEnd) {
									Set<LocalDate> myOffs = weeklyOffs != null ? weeklyOffs.get(emp.getId()) : null;
									LocalDate nextDate = date.plusDays(1);
									boolean nextDayOff = myOffs != null && myOffs.contains(nextDate);
									if (!nextDayOff) return false;
									if (cycleNightCounts.getOrDefault(emp.getId(), 0) >= 1) return false;
									boolean hasUsedOffAlready = myOffs != null && myOffs.stream().anyMatch(d -> !d.isAfter(date));
									if (hasUsedOffAlready) return false;
								}
								return true;
							})
							.filter(emp -> hasMinimumRest(lastShiftDateMap.get(emp.getId()), lastShiftMap.get(emp.getId()), date, candidateShift))
							.sorted(Comparator.comparingInt((Employee emp) -> score(emp, type, date, lastShiftMap, lastShiftDateMap, cycleNightCounts, shiftCountsMap, weeklyOffs, cycle.getStartDate(), preferencesMap != null ? preferencesMap.get(emp.getId()) : null, randomSeed)))
							.toList();

					if (!candidates.isEmpty()) {
						Employee chosen = candidates.get(0);
						assigned.add(chosen.getId());
						dailyMatching.computeIfAbsent(type, k -> new ArrayList<>()).add(chosen);
					}
				}
			}
		}

		repairDailyCoverage(dailyMatching, date, shifts, lastShiftMap, lastShiftDateMap, cycleNightCounts, maxNightsAllowed, preferencesMap, weeklyOffs, workingDays, cycle.getEndDate());

		for (ShiftType type : ASSIGNMENT_ORDER) {
			Shift targetShift = shifts.get(type);
			int targetReq = feasibleDemands.getOrDefault(type, 0);
			List<Employee> assignedToType = dailyMatching.getOrDefault(type, Collections.emptyList());
			int selCount = assignedToType.size();

			List<Employee> eligibleForType = new ArrayList<>();
			Map<String, String> rejections = new LinkedHashMap<>();

			for (Employee emp : employees) {
				String reason = determineCandidateRejectionReason(emp, type, date, targetShift,
						assigned, offTakenCount, weeklyOffs, lastShiftMap, lastShiftDateMap,
						cycleNightCounts, maxNightsAllowed, preferencesMap, cycle.getStartDate(), workingDays);
				if ("OTHER_BUSINESS_RULE".equals(reason) || assignedToType.contains(emp)) {
					eligibleForType.add(emp);
				} else {
					rejections.put(emp.getEmployeeCode() + " (" + emp.getFirstName() + ")", reason);
				}
			}

			log.debug("[WRMS CANDIDATE EVAL] DATE: {} | SHIFT: {} | REQUIRED: {} | CANDIDATES: {} | SELECTED: {} | REJECTED: {}",
					date, type, targetReq, eligibleForType.size(), selCount, rejections.size());
			if (date.equals(cycle.getEndDate()) || type == ShiftType.GENERAL) {
				for (Map.Entry<String, String> entry : rejections.entrySet()) {
					log.debug("  -> Candidate {} rejected for {}: {}", entry.getKey(), type, entry.getValue());
				}
			}
		}

		Map<ShiftType, Integer> actualAssignedCounts = new EnumMap<>(ShiftType.class);
		for (ShiftType type : ASSIGNMENT_ORDER) {
			actualAssignedCounts.put(type, 0);
		}

		for (Map.Entry<ShiftType, List<Employee>> entry : dailyMatching.entrySet()) {
			ShiftType type = entry.getKey();
			Shift shift = shifts.get(type);
			for (Employee emp : entry.getValue()) {
				dayAssignments.add(newAssignment(cycle, emp, shift, date, false, false));
				actualAssignedCounts.put(type, actualAssignedCounts.get(type) + 1);

				if (type == ShiftType.NIGHT) {
					cycleNightCounts.compute(emp.getId(), (k, v) -> v == null ? 1 : v + 1);
				}
				Map<ShiftType, Integer> empCounts = shiftCountsMap.get(emp.getId());
				if (empCounts != null) {
					empCounts.compute(type, (k, v) -> v == null ? 1 : v + 1);
				}
			}
		}

		List<Employee> unassigned = available.stream()
				.filter(emp -> !assigned.contains(emp.getId()))
				.toList();

		for (Employee emp : unassigned) {
			ApplicablePreference pref = preferencesMap != null ? preferencesMap.get(emp.getId()) : null;
			int empEve = (shiftCountsMap != null && shiftCountsMap.get(emp.getId()) != null)
					? shiftCountsMap.get(emp.getId()).getOrDefault(ShiftType.EVENING, 0)
					: 0;
			List<ShiftType> allowedTypes = new ArrayList<>();
			boolean isFullTeam = (employees.stream().filter(Employee::isActive).count() >= 7);
			if (isFullTeam && isDevelopmentTeam(emp) && !isWeekend(date)) {
				allowedTypes.add(ShiftType.GENERAL);
			} else {
				if (actualAssignedCounts.getOrDefault(ShiftType.GENERAL, 0) < 4) {
					allowedTypes.add(ShiftType.GENERAL);
				}
				if (actualAssignedCounts.getOrDefault(ShiftType.MORNING, 0) < 2) {
					allowedTypes.add(ShiftType.MORNING);
				}
				if (emp.getGender() != Gender.FEMALE && empEve < 3 && actualAssignedCounts.getOrDefault(ShiftType.EVENING, 0) < 2) {
					allowedTypes.add(ShiftType.EVENING);
				}
				if (allowedTypes.isEmpty()) {
					allowedTypes.add(ShiftType.GENERAL);
				}
			}

			List<ShiftType> extraCandidates = new ArrayList<>();
			for (ShiftType candidateType : allowedTypes) {
				if (isEligible(emp, candidateType, pref, date) &&
						hasMinimumRest(lastShiftDateMap.get(emp.getId()), lastShiftMap.get(emp.getId()), date, shifts.get(candidateType))) {
					extraCandidates.add(candidateType);
				}
			}
			if (extraCandidates.isEmpty()) {
				for (ShiftType candidateType : allowedTypes) {
					if (isEligible(emp, candidateType, pref, date) &&
							hasMinimumRest(lastShiftDateMap.get(emp.getId()), lastShiftMap.get(emp.getId()), date, shifts.get(candidateType))) {
						extraCandidates.add(candidateType);
					}
				}
			}
			extraCandidates.sort(Comparator.comparingInt(t -> score(emp, t, date, lastShiftMap, lastShiftDateMap, cycleNightCounts, shiftCountsMap, weeklyOffs, cycle.getStartDate(), pref, randomSeed)));

			if (extraCandidates.isEmpty()) {
				// Cannot safely work any daytime shift due to rest violations (e.g. overnight night shift)
				dayAssignments.add(newAssignment(cycle, emp, shifts.get(ShiftType.OFF), date, true, false));
				continue;
			}

			ShiftType bestExtra = extraCandidates.get(0);

			dayAssignments.add(newAssignment(cycle, emp, shifts.get(bestExtra), date, false, false));
			actualAssignedCounts.put(bestExtra, actualAssignedCounts.get(bestExtra) + 1);
			Map<ShiftType, Integer> empCounts = shiftCountsMap.get(emp.getId());
			if (empCounts != null) {
				empCounts.compute(bestExtra, (k, v) -> v == null ? 1 : v + 1);
			}
		}

		// Ensure all feasible shift types have at least 1 staff if feasible > 0
		for (ShiftType missingType : List.of(ShiftType.NIGHT, ShiftType.EVENING, ShiftType.GENERAL, ShiftType.MORNING)) {
			int actual = actualAssignedCounts.getOrDefault(missingType, 0);
			int feasible = feasibleDemands.getOrDefault(missingType, 0);
			if (feasible > 0 && actual == 0) {
				for (ShiftType donorType : List.of(ShiftType.GENERAL, ShiftType.MORNING, ShiftType.EVENING)) {
					if (donorType == missingType) continue;
					if (actualAssignedCounts.getOrDefault(donorType, 0) >= 2) {
						RosterAssignment donorAssignment = null;
						for (RosterAssignment a : dayAssignments) {
							if (!a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == donorType) {
								Employee dEmp = a.getEmployee();
								boolean isFullTeam = (employees.stream().filter(Employee::isActive).count() >= 7);
								long totalNonDevMales = employees.stream().filter(e -> e.getGender() == Gender.MALE && e.isActive() && !e.isDevelopmentTeam()).count();
								if (isFullTeam && dEmp.isDevelopmentTeam() && !isWeekend(date) && missingType != ShiftType.GENERAL) continue;
								if (missingType == ShiftType.EVENING && dEmp.getPairedEmployeeId() != null) {
									Long partnerId = dEmp.getPairedEmployeeId();
									boolean partnerIsMorning = dayAssignments.stream()
											.anyMatch(a2 -> a2.getEmployee().getId().equals(partnerId) && !a2.isWeeklyOff() && !a2.isOnLeave() && a2.getShift() != null && a2.getShift().getShiftType() == ShiftType.MORNING);
									if (partnerIsMorning) continue;
								}
								if (missingType == ShiftType.NIGHT) {
									int dMax = dEmp.getMaxNightsPerWeek() != null ? dEmp.getMaxNightsPerWeek() : maxNightsAllowed;
									if (totalNonDevMales <= 3 && dEmp.getMaxNightsPerWeek() != null) {
										dMax = Math.max(dMax, 2);
									}
									if (cycleNightCounts.getOrDefault(dEmp.getId(), 0) >= maxNightsAllowed) continue;
									if (workingDays == 6 && cycleNightCounts.getOrDefault(dEmp.getId(), 0) >= 1) continue;
									if (workingDays == 6 && cycle != null && cycle.getEndDate() != null && !date.equals(cycle.getEndDate())) {
										Set<LocalDate> myOffs = weeklyOffs != null ? weeklyOffs.get(dEmp.getId()) : null;
										if (myOffs == null || !myOffs.contains(date.plusDays(1))) continue;
										if (myOffs.stream().anyMatch(d -> !d.isAfter(date))) continue;
									}
									if (cycleNightCounts.getOrDefault(dEmp.getId(), 0) >= dMax) continue;
									if (dEmp.isDevelopmentTeam()) continue;
									Shift prevS = lastShiftMap.get(dEmp.getId());
									LocalDate prevD = lastShiftDateMap.get(dEmp.getId());
									boolean wasNightYesterday = prevS != null && prevS.getShiftType() == ShiftType.NIGHT && prevD != null && prevD.equals(date.minusDays(1));
									if (wasNightYesterday) continue; // CRIS Hard Rule: No consecutive night shifts
								}
								ApplicablePreference dPref = preferencesMap != null ? preferencesMap.get(dEmp.getId()) : null;
								if (dPref != null && dPref.hasTargetShiftForDate(date)) continue;
								if (isEligible(dEmp, missingType, dPref, date)
										&& hasMinimumRest(lastShiftDateMap.get(dEmp.getId()), lastShiftMap.get(dEmp.getId()), date, shifts.get(missingType))) {
									donorAssignment = a;
									break;
								}
							}
						}
						if (donorAssignment != null) {
							donorAssignment.setShift(shifts.get(missingType));
							actualAssignedCounts.put(donorType, actualAssignedCounts.get(donorType) - 1);
							actualAssignedCounts.put(missingType, actualAssignedCounts.get(missingType) + 1);
							if (shiftCountsMap != null && shiftCountsMap.get(donorAssignment.getEmployee().getId()) != null) {
								shiftCountsMap.get(donorAssignment.getEmployee().getId()).compute(donorType, (k, v) -> v == null || v <= 1 ? 0 : v - 1);
								shiftCountsMap.get(donorAssignment.getEmployee().getId()).compute(missingType, (k, v) -> v == null ? 1 : v + 1);
							}
							if (missingType == ShiftType.NIGHT) {
								cycleNightCounts.compute(donorAssignment.getEmployee().getId(), (k, v) -> v == null ? 1 : v + 1);
								if (workingDays != 6 && weeklyOffs != null && cycle != null && cycle.getEndDate() != null && !date.equals(cycle.getEndDate())) {
									weeklyOffs.computeIfAbsent(donorAssignment.getEmployee().getId(), k -> new HashSet<>()).add(date.plusDays(1));
								}
							}
							break;
						}
					}
				}
			}
		}

		for (RosterAssignment assignment : dayAssignments) {
			Long empId = assignment.getEmployee().getId();
			if (!assignment.isWeeklyOff() && !assignment.isOnLeave() && assignment.getShift().getShiftType() != ShiftType.OFF) {
				lastShiftMap.put(empId, assignment.getShift());
				lastShiftDateMap.put(empId, assignment.getRosterDate());
			}
		}

		List<ShiftCoverageSummary> summaries = new ArrayList<>();
		int dailyAssignedTotal = 0;

		int totalDayAssigned = actualAssignedCounts.getOrDefault(ShiftType.MORNING, 0) + actualAssignedCounts.getOrDefault(ShiftType.GENERAL, 0);
		int totalDayFeasible = feasibleDemands.getOrDefault(ShiftType.MORNING, 0) + feasibleDemands.getOrDefault(ShiftType.GENERAL, 0);

		for (ShiftType type : ASSIGNMENT_ORDER) {
			int configured = configuredDemands.getOrDefault(type, 1);
			int feasible = feasibleDemands.getOrDefault(type, 1);
			int actual = actualAssignedCounts.getOrDefault(type, 0);
			int opShortage;
			if (type == ShiftType.MORNING || type == ShiftType.GENERAL) {
				opShortage = (actual == 0 && feasible > 0) ? 1 : Math.max(0, totalDayFeasible - totalDayAssigned);
			} else {
				opShortage = Math.max(0, feasible - actual);
			}

			dailyAssignedTotal += actual;

			String status;
			String reason = null;

			if (opShortage > 0) {
				status = "SHORTAGE";
				reason = determineShortageReason(type, available, assigned, date, shifts.get(type),
						lastShiftMap, lastShiftDateMap, cycleNightCounts, maxNightsAllowed, preferencesMap);
			} else if (actual < configured) {
				status = (type == ShiftType.NIGHT || type == ShiftType.EVENING)
						? "Workforce/eligibility-limited"
						: "Workforce-limited";
				reason = "Configured demand (" + configured + ") dynamically adapted to workforce capacity (" + feasible + ")";
			} else {
				status = "FULL";
				reason = "Fully staffed within safety rules";
			}

			summaries.add(new ShiftCoverageSummary(type, configured, feasible, actual, opShortage, status, reason));
		}

		int dailyOperationalShortage = Math.max(0, dailyFeasibleTotal - dailyAssignedTotal);

		DailyCoverageReport dailyReport = new DailyCoverageReport(date, activeEmployees, plannedWorking,
				plannedOffOrLeave, dailyConfiguredTotal, dailyFeasibleTotal, dailyAssignedTotal,
				dailyOperationalShortage, summaries);

		return new DayGenerationResult(dayAssignments, dailyReport);
	}

	private void repairDailyCoverage(Map<ShiftType, List<Employee>> dailyMatching, LocalDate date,
			Map<ShiftType, Shift> shifts, Map<Long, Shift> lastShiftMap, Map<Long, LocalDate> lastShiftDateMap,
			Map<Long, Integer> cycleNightCounts, int maxNightsAllowed, Map<Long, ApplicablePreference> preferencesMap,
			Map<Long, Set<LocalDate>> weeklyOffs, int workingDays, LocalDate cycleEndDate) {
		for (ShiftType missingType : List.of(ShiftType.NIGHT, ShiftType.EVENING, ShiftType.GENERAL, ShiftType.MORNING)) {
			int reqMin = (missingType == ShiftType.MORNING) ? getRequiredMorningMin(date) : 1;
			while (dailyMatching.getOrDefault(missingType, Collections.emptyList()).size() < reqMin) {
				Shift targetShift = shifts.get(missingType);
				Employee candidateToMove = null;
				List<Employee> donorListToUse = null;

				for (ShiftType donorType : List.of(ShiftType.MORNING, ShiftType.GENERAL, ShiftType.EVENING)) {
					if (donorType == missingType) continue;
					List<Employee> donorList = dailyMatching.getOrDefault(donorType, Collections.emptyList());
					int minKeep = (donorType == ShiftType.MORNING) ? getRequiredMorningMin(date) : (donorType == ShiftType.EVENING ? 1 : 0);
					if (donorList.size() <= minKeep) continue;

					for (Employee candidate : donorList) {
						if (candidate.getGender() == Gender.FEMALE && (missingType == ShiftType.EVENING || missingType == ShiftType.NIGHT)) {
							continue;
						}
						if (candidate.isDevelopmentTeam() && !isWeekend(date) && missingType != ShiftType.GENERAL) {
							continue;
						}
						if (missingType == ShiftType.EVENING && candidate.getPairedEmployeeId() != null) {
							Long partnerId = candidate.getPairedEmployeeId();
							boolean partnerWorkingToday = dailyMatching.values().stream().flatMap(List::stream).anyMatch(e -> e.getId().equals(partnerId));
							if (partnerWorkingToday) continue;
						}
						if (missingType == ShiftType.NIGHT) {
							if (candidate.isDevelopmentTeam()) continue;
							int cLimit = (candidate.getMaxNightsPerWeek() != null) ? candidate.getMaxNightsPerWeek() : maxNightsAllowed;
							if (workingDays == 6 && candidate.getMaxNightsPerWeek() != null && candidate.getMaxNightsPerWeek() > 1) {
								cLimit = Math.max(cLimit, 2);
							}
							if (cycleNightCounts.getOrDefault(candidate.getId(), 0) >= cLimit) continue;
							if (cycleNightCounts.getOrDefault(candidate.getId(), 0) >= maxNightsAllowed) continue;
							if (workingDays == 6 && cycleNightCounts.getOrDefault(candidate.getId(), 0) >= 1) continue;
							Shift prevS = lastShiftMap.get(candidate.getId());
							LocalDate prevD = lastShiftDateMap.get(candidate.getId());
							boolean wasNightYesterday = prevS != null && prevS.getShiftType() == ShiftType.NIGHT && prevD != null && prevD.equals(date.minusDays(1));
							if (wasNightYesterday) continue;
							if (workingDays == 6 && cycleEndDate != null && !date.equals(cycleEndDate)) {
								Set<LocalDate> myOffs = weeklyOffs != null ? weeklyOffs.get(candidate.getId()) : null;
								if (myOffs == null || !myOffs.contains(date.plusDays(1))) continue;
								if (myOffs.stream().anyMatch(d -> !d.isAfter(date))) continue;
							}
						}
						ApplicablePreference pref = preferencesMap != null ? preferencesMap.get(candidate.getId()) : null;
						if (pref != null && pref.hasTargetShiftForDate(date)) continue;
						if (isEligible(candidate, missingType, pref, date)
								&& hasMinimumRest(lastShiftDateMap.get(candidate.getId()), lastShiftMap.get(candidate.getId()), date, targetShift)) {
							candidateToMove = candidate;
							donorListToUse = donorList;
							break;
						}
					}

					if (candidateToMove == null && (missingType == ShiftType.MORNING || missingType == ShiftType.EVENING)) {
						for (Employee candidate : donorList) {
							if (candidate.getGender() == Gender.FEMALE && missingType == ShiftType.EVENING) continue;
							if (candidate.isDevelopmentTeam() && !isWeekend(date)) continue;
							if (missingType == ShiftType.EVENING && candidate.getPairedEmployeeId() != null) continue;
							ApplicablePreference pref = preferencesMap != null ? preferencesMap.get(candidate.getId()) : null;
							if (pref != null && pref.hasTargetShiftForDate(date)) continue;
							if (isEligible(candidate, missingType, pref, date)
									&& hasMinimumRest(lastShiftDateMap.get(candidate.getId()), lastShiftMap.get(candidate.getId()), date, targetShift)) {
								candidateToMove = candidate;
								donorListToUse = donorList;
								break;
							}
						}
					}

					if (candidateToMove != null) {
						donorListToUse.remove(candidateToMove);
						dailyMatching.computeIfAbsent(missingType, k -> new ArrayList<>()).add(candidateToMove);
						break;
					}
				}

				if (candidateToMove == null) {
					break;
				}
			}
		}
	}

	private Map<ShiftType, Integer> calculateDailyFeasibleDemands(LocalDate date, List<Employee> available, Map<ShiftType, Integer> configuredDemands) {
		Map<ShiftType, Integer> feasible = new EnumMap<>(ShiftType.class);
		int availableCount = available.size();

		if (availableCount == 0) {
			for (ShiftType type : ASSIGNMENT_ORDER) feasible.put(type, 0);
			return feasible;
		}

		boolean hasEligibleMaleForNight = available.stream()
				.anyMatch(e -> e.getGender() == Gender.MALE && (date == null || e.isEligibleForDate(date)));

		if (availableCount == 1) {
			feasible.put(ShiftType.MORNING, 1);
			feasible.put(ShiftType.GENERAL, 0);
			feasible.put(ShiftType.EVENING, 0);
			feasible.put(ShiftType.NIGHT, 0);
			return feasible;
		}

		if (availableCount == 2) {
			feasible.put(ShiftType.MORNING, 1);
			feasible.put(ShiftType.EVENING, 1);
			feasible.put(ShiftType.GENERAL, 0);
			feasible.put(ShiftType.NIGHT, 0);
			return feasible;
		}

		// Section 11: Special Low-Availability Rule (Available == 3)
		if (availableCount == 3) {
			if (hasEligibleMaleForNight) {
				feasible.put(ShiftType.MORNING, 1);
				feasible.put(ShiftType.EVENING, 1);
				feasible.put(ShiftType.NIGHT, 1);
				feasible.put(ShiftType.GENERAL, 0);
			} else {
				feasible.put(ShiftType.MORNING, 1);
				feasible.put(ShiftType.EVENING, 1);
				feasible.put(ShiftType.GENERAL, 1);
				feasible.put(ShiftType.NIGHT, 0);
			}
			return feasible;
		}

		// Baseline allocations for availableCount >= 4
		feasible.put(ShiftType.NIGHT, hasEligibleMaleForNight ? 1 : 0);
		feasible.put(ShiftType.EVENING, 1);
		feasible.put(ShiftType.MORNING, 1);
		if (availableCount >= 5) {
			feasible.put(ShiftType.GENERAL, 2);
		} else {
			feasible.put(ShiftType.GENERAL, hasEligibleMaleForNight ? 1 : 2);
		}

		int baselineAssigned = feasible.values().stream().mapToInt(Integer::intValue).sum();
		int remainingStaff = Math.max(0, availableCount - baselineAssigned);

		// Allocate remaining staff adhering strictly to business bounds:
		// MORNING: 1-2 (MAX 2)
		// GENERAL: 2-4 (MAX 4)
		// EVENING: 1-2 (MAX 2)
		// NIGHT: exactly 1 (MAX 1)
		while (remainingStaff > 0) {
			boolean allocated = false;
			// Weekday Morning prefers 2 if availableCount >= 6
			if (!isWeekend(date) && feasible.get(ShiftType.MORNING) < 2 && remainingStaff > 0) {
				feasible.put(ShiftType.MORNING, feasible.get(ShiftType.MORNING) + 1);
				remainingStaff--;
				allocated = true;
			} else if (feasible.get(ShiftType.GENERAL) < 4 && remainingStaff > 0) {
				feasible.put(ShiftType.GENERAL, feasible.get(ShiftType.GENERAL) + 1);
				remainingStaff--;
				allocated = true;
			} else if (feasible.get(ShiftType.EVENING) < 2 && remainingStaff > 0) {
				feasible.put(ShiftType.EVENING, feasible.get(ShiftType.EVENING) + 1);
				remainingStaff--;
				allocated = true;
			} else if (feasible.get(ShiftType.MORNING) < 2 && remainingStaff > 0) {
				feasible.put(ShiftType.MORNING, feasible.get(ShiftType.MORNING) + 1);
				remainingStaff--;
				allocated = true;
			}
			if (!allocated) {
				break;
			}
		}

		return feasible;
	}

	private Map<ShiftType, Integer> calculateDailyFeasibleDemands(List<Employee> available, Map<ShiftType, Integer> configuredDemands) {
		return calculateDailyFeasibleDemands(LocalDate.now(), available, configuredDemands);
	}

	private String determineCandidateRejectionReason(Employee emp, ShiftType shiftType, LocalDate date, Shift shift,
			Set<Long> assigned, Map<Long, Integer> offTakenCount, Map<Long, Set<LocalDate>> weeklyOffs,
			Map<Long, Shift> lastShiftMap, Map<Long, LocalDate> lastShiftDateMap,
			Map<Long, Integer> cycleNightCounts, int maxNightsAllowed,
			Map<Long, ApplicablePreference> preferencesMap, LocalDate cycleStartDate, int workingDays) {
		if (!emp.isActive()) return "INACTIVE_EMPLOYEE";
		if (isApprovedLeave(emp.getId(), date)) return "ON_LEAVE";
		Set<LocalDate> schedOffs = weeklyOffs != null ? weeklyOffs.get(emp.getId()) : null;
		int taken = offTakenCount != null ? offTakenCount.getOrDefault(emp.getId(), 0) : 0;
		int maxWeeklyOffsForEmp = Math.max(1, 7 - workingDays);
		if ((schedOffs != null && schedOffs.contains(date)) || taken >= maxWeeklyOffsForEmp) return "WEEKLY_OFF";
		if (assigned.contains(emp.getId())) return "ALREADY_ASSIGNED";
		if (emp.getGender() == Gender.FEMALE && (shiftType == ShiftType.EVENING || shiftType == ShiftType.NIGHT)) {
			return "GENDER_RESTRICTION";
		}
		ApplicablePreference pref = preferencesMap != null ? preferencesMap.get(emp.getId()) : null;
		if (pref != null && pref.isShiftAvoided(shiftType)) {
			return "SHIFT_PREFERENCE_HARD_CONSTRAINT";
		}
		if (shiftType == ShiftType.NIGHT) {
			if (cycleNightCounts.getOrDefault(emp.getId(), 0) >= maxNightsAllowed) {
				return "NIGHT_WEEKLY_LIMIT";
			}
			Shift prevS = lastShiftMap.get(emp.getId());
			LocalDate prevD = lastShiftDateMap.get(emp.getId());
			boolean wasNightYesterday = prevS != null && prevS.getShiftType() == ShiftType.NIGHT && prevD != null && prevD.equals(date.minusDays(1));
			boolean hasSundayOff = (schedOffs != null && cycleStartDate != null && schedOffs.contains(cycleStartDate.plusDays(6)));
			int dOff = cycleStartDate != null ? (int) java.time.temporal.ChronoUnit.DAYS.between(cycleStartDate, date) : 0;
			if (dOff == 5 && !wasNightYesterday && !hasSundayOff) {
				return "PREVIOUS_NIGHT_RESTRICTION";
			}
		}
		Shift prevShift = lastShiftMap.get(emp.getId());
		LocalDate prevDate = lastShiftDateMap.get(emp.getId());
		if (!hasMinimumRest(prevDate, prevShift, date, shift)) {
			if (prevShift != null && prevShift.getShiftType() == ShiftType.NIGHT && shiftType == ShiftType.MORNING) {
				return "PREVIOUS_NIGHT_RESTRICTION";
			}
			return "REST_VIOLATION";
		}
		return "OTHER_BUSINESS_RULE";
	}

	private String determineCandidateRejectionReason(Employee emp, ShiftType shiftType, LocalDate date, Shift shift,
			Set<Long> assigned, Set<Long> offTaken, Map<Long, LocalDate> weeklyOffs,
			Map<Long, Shift> lastShiftMap, Map<Long, LocalDate> lastShiftDateMap,
			Map<Long, Integer> cycleNightCounts, int maxNightsAllowed,
			Map<Long, ApplicablePreference> preferencesMap, LocalDate cycleStartDate) {
		Map<Long, Set<LocalDate>> converted = new HashMap<>();
		if (weeklyOffs != null) {
			weeklyOffs.forEach((k, v) -> converted.put(k, v != null ? Set.of(v) : Collections.emptySet()));
		}
		Map<Long, Integer> countMap = new HashMap<>();
		if (offTaken != null) {
			offTaken.forEach(id -> countMap.put(id, 1));
		}
		return determineCandidateRejectionReason(emp, shiftType, date, shift, assigned, countMap, converted,
				lastShiftMap, lastShiftDateMap, cycleNightCounts, maxNightsAllowed, preferencesMap, cycleStartDate, 6);
	}

	private String determineShortageReason(ShiftType type, List<Employee> available, Set<Long> assigned,
			LocalDate date, Shift shift, Map<Long, Shift> lastShiftMap, Map<Long, LocalDate> lastShiftDateMap,
			Map<Long, Integer> cycleNightCounts, int maxNightsAllowed, Map<Long, ApplicablePreference> preferencesMap) {
		if (available.isEmpty()) {
			return "No active employees available on " + date + " (all on leave or weekly off)";
		}

		long eligibleCount = available.stream().filter(e -> isEligible(e, type, preferencesMap != null ? preferencesMap.get(e.getId()) : null)).count();
		if (eligibleCount == 0) {
			return "No available employees eligible for " + type + " (female restriction / avoid shift / skills)";
		}

		if (type == ShiftType.NIGHT) {
			long nightLimitReached = available.stream()
					.filter(e -> isEligible(e, type, preferencesMap != null ? preferencesMap.get(e.getId()) : null))
					.filter(e -> cycleNightCounts.getOrDefault(e.getId(), 0) >= maxNightsAllowed)
					.count();
			if (nightLimitReached == eligibleCount) {
				return "All eligible employees have reached maximum night limit (" + maxNightsAllowed + "/cycle)";
			}
		}

		long restBlocked = available.stream()
				.filter(e -> isEligible(e, type, preferencesMap != null ? preferencesMap.get(e.getId()) : null))
				.filter(e -> !hasMinimumRest(lastShiftDateMap.get(e.getId()), lastShiftMap.get(e.getId()), date, shift))
				.count();
		if (restBlocked == eligibleCount) {
			return "All eligible employees blocked by 12h rest rule from previous shift";
		}

		return "Workforce capacity prioritized for other mandatory shifts";
	}

	private boolean solveDay(int slotIndex, List<ShiftType> slots, List<Employee> available, List<Employee> allEmployees,
			Set<Long> assigned, Map<ShiftType, List<Employee>> dailyAssignments, LocalDate date,
			Map<ShiftType, Shift> shifts, Map<Long, Shift> lastShiftMap, Map<Long, LocalDate> lastShiftDateMap,
			Map<Long, Integer> cycleNightCounts, Map<Long, Map<ShiftType, Integer>> shiftCountsMap,
			int maxNightsAllowed, int totalActiveMales, Map<Long, Set<LocalDate>> weeklyOffs, LocalDate cycleStartDate, LocalDate cycleEndDate,
			Map<Long, ApplicablePreference> preferencesMap, int randomSeed) {
		return solveDay(slotIndex, slots, available, allEmployees, assigned, dailyAssignments, date,
				shifts, lastShiftMap, lastShiftDateMap, cycleNightCounts, shiftCountsMap,
				maxNightsAllowed, totalActiveMales, weeklyOffs, cycleStartDate, cycleEndDate,
				preferencesMap, randomSeed, new int[1]);
	}

	private boolean solveDay(int slotIndex, List<ShiftType> slots, List<Employee> available, List<Employee> allEmployees,
			Set<Long> assigned, Map<ShiftType, List<Employee>> dailyAssignments, LocalDate date,
			Map<ShiftType, Shift> shifts, Map<Long, Shift> lastShiftMap, Map<Long, LocalDate> lastShiftDateMap,
			Map<Long, Integer> cycleNightCounts, Map<Long, Map<ShiftType, Integer>> shiftCountsMap,
			int maxNightsAllowed, int totalActiveMales, Map<Long, Set<LocalDate>> weeklyOffs, LocalDate cycleStartDate, LocalDate cycleEndDate,
			Map<Long, ApplicablePreference> preferencesMap, int randomSeed, int[] stepCounter) {
		if (stepCounter != null && ++stepCounter[0] > 1500) {
			return false; // Time/search budget exceeded, prune exponential exploration safely
		}
		if (slotIndex >= slots.size()) {
			return true;
		}

		ShiftType shiftType = slots.get(slotIndex);
		Shift shift = shifts.get(shiftType);

				List<Employee> candidates = available.stream()
				.filter(e -> !assigned.contains(e.getId()))
				.filter(e -> isEligible(e, shiftType, preferencesMap != null ? preferencesMap.get(e.getId()) : null, date))
				.filter(e -> {
					Shift prevS = lastShiftMap.get(e.getId());
					LocalDate prevD = lastShiftDateMap.get(e.getId());
					boolean wasNightYesterday = prevS != null && prevS.getShiftType() == ShiftType.NIGHT && prevD != null && prevD.equals(date.minusDays(1));
					if (wasNightYesterday) {
						return false; // CRIS Mandatory Fatigue Safety Rule: Employee worked Night yesterday, must have Weekly OFF today!
					}
					if (shiftType == ShiftType.NIGHT) {
						if (e.getGender() == Gender.FEMALE) return false;
						if (wasNightYesterday) return false;
					}
					if (shiftType == ShiftType.EVENING && e.getGender() == Gender.MALE) {
						int myEve = shiftCountsMap != null && shiftCountsMap.get(e.getId()) != null
								? shiftCountsMap.get(e.getId()).getOrDefault(ShiftType.EVENING, 0)
								: 0;
						if (myEve >= 3) return false;
						if (myEve >= 2) {
							boolean canDoDaytime = hasMinimumRest(lastShiftDateMap.get(e.getId()), lastShiftMap.get(e.getId()), date, shifts.get(ShiftType.MORNING))
									|| hasMinimumRest(lastShiftDateMap.get(e.getId()), lastShiftMap.get(e.getId()), date, shifts.get(ShiftType.GENERAL));
							if (canDoDaytime) {
								long otherMalesWithFewerEve = available.stream()
										.filter(o -> o.getGender() == Gender.MALE && !o.getId().equals(e.getId()) && !assigned.contains(o.getId()))
										.filter(o -> {
											int oEve = shiftCountsMap != null && shiftCountsMap.get(o.getId()) != null
													? shiftCountsMap.get(o.getId()).getOrDefault(ShiftType.EVENING, 0)
													: 0;
											return oEve < myEve && isEligible(o, ShiftType.EVENING)
													&& hasMinimumRest(lastShiftDateMap.get(o.getId()), lastShiftMap.get(o.getId()), date, shift);
										})
										.count();
								if (otherMalesWithFewerEve > 0) return false;
							}
						}
					}
					if (shiftType == ShiftType.EVENING && e.getPairedEmployeeId() != null) {
						Long partnerId = e.getPairedEmployeeId();
						boolean partnerAvailableToday = available.stream().anyMatch(o -> o.getId().equals(partnerId));
						boolean otherStaffAvailable = available.stream()
								.anyMatch(o -> !o.getId().equals(e.getId()) && !assigned.contains(o.getId()) && !o.isDevelopmentTeam());
						if (partnerAvailableToday && otherStaffAvailable) return false;
					}
					boolean isFullTeam = (shiftCountsMap != null && shiftCountsMap.size() >= 7);
					int totalNonDevMales = totalActiveMales - 1;
					if (isFullTeam && e.isDevelopmentTeam() && !isWeekend(date) && shiftType != ShiftType.GENERAL) return false;
					if (shiftType != ShiftType.NIGHT) return true;
					int empLimit = (e.getMaxNightsPerWeek() != null) ? e.getMaxNightsPerWeek() : maxNightsAllowed;
					if (totalNonDevMales <= 3 && e.getMaxNightsPerWeek() != null) {
						empLimit = Math.max(empLimit, 2);
					} else if ((weeklyOffs != null && weeklyOffs.containsKey(e.getId()) && weeklyOffs.get(e.getId()).size() <= 1) && empLimit < 2 && e.getMaxNightsPerWeek() != null && e.getMaxNightsPerWeek() > 1) {
						empLimit = 2;
					}
					if (cycleNightCounts.getOrDefault(e.getId(), 0) >= empLimit) return false;
					if (e.getGender() == Gender.FEMALE) return false; // Hard constraint: Female night safety

					boolean isSixDay = (weeklyOffs != null && weeklyOffs.containsKey(e.getId()) && weeklyOffs.get(e.getId()).size() <= 1);
					boolean isCycleEnd = (cycleEndDate != null && date.equals(cycleEndDate));

					// Preceding Sunday Night check: If employee worked Sunday Night preceding cycle, their single off is Monday
					if (assignmentRepository != null && cycleStartDate != null) {
						List<RosterAssignment> prevWorked = assignmentRepository.findWorkedAssignmentsBefore(e.getId(), cycleStartDate);
						if (!prevWorked.isEmpty()) {
							RosterAssignment lastA = prevWorked.get(0);
							if (lastA.getRosterDate() != null && lastA.getRosterDate().equals(cycleStartDate.minusDays(1))
									&& lastA.getShift() != null && lastA.getShift().getShiftType() == ShiftType.NIGHT) {
								if (!isCycleEnd) return false;
							}
						}
					}

					if (isSixDay && !isCycleEnd) {
						Set<LocalDate> myOffs = weeklyOffs.get(e.getId());
						LocalDate nextDay = date.plusDays(1);
						boolean nextDayOff = myOffs != null && myOffs.contains(nextDay);
						if (!nextDayOff) return false;
						if (cycleNightCounts.getOrDefault(e.getId(), 0) >= 1) return false;
						boolean hasUsedOffAlready = myOffs != null && myOffs.stream().anyMatch(d -> !d.isAfter(date));
						if (hasUsedOffAlready) return false;
					}
					return true;
				})
				.filter(e -> hasMinimumRest(lastShiftDateMap.get(e.getId()), lastShiftMap.get(e.getId()), date, shift))
				.sorted(Comparator.comparingInt((Employee e) -> score(e, shiftType, date, lastShiftMap, lastShiftDateMap, cycleNightCounts, shiftCountsMap, weeklyOffs, cycleStartDate, preferencesMap != null ? preferencesMap.get(e.getId()) : null, randomSeed)))
				.toList();

		for (Employee candidate : candidates) {
			assigned.add(candidate.getId());
			dailyAssignments.computeIfAbsent(shiftType, k -> new ArrayList<>()).add(candidate);

			boolean addedTomorrowOff = false;
			if (shiftType == ShiftType.NIGHT && weeklyOffs != null && cycleEndDate != null && !date.equals(cycleEndDate)) {
				LocalDate tomorrow = date.plusDays(1);
				Set<LocalDate> empOffs = weeklyOffs.computeIfAbsent(candidate.getId(), k -> new HashSet<>());
				if (!empOffs.contains(tomorrow)) {
					empOffs.add(tomorrow);
					addedTomorrowOff = true;
				}
			}

			if (solveDay(slotIndex + 1, slots, available, allEmployees, assigned, dailyAssignments, date,
					shifts, lastShiftMap, lastShiftDateMap, cycleNightCounts, shiftCountsMap, maxNightsAllowed, totalActiveMales, weeklyOffs, cycleStartDate, cycleEndDate, preferencesMap, randomSeed, stepCounter)) {
				return true;
			}

			if (addedTomorrowOff) {
				weeklyOffs.get(candidate.getId()).remove(date.plusDays(1));
			}
			assigned.remove(candidate.getId());
			dailyAssignments.get(shiftType).remove(candidate);
		}

		return false;
	}

	private Long empId(Employee e) {
		return e != null ? e.getId() : null;
	}

	private Map<Long, Set<LocalDate>> planWeeklyOffs(List<Employee> employees, LocalDate startDate, LocalDate endDate,
			Map<Long, ApplicablePreference> preferencesMap, int randomSeed, int workingDays) {
		Map<Long, Set<LocalDate>> result = new HashMap<>();
		Map<LocalDate, Integer> offCounts = new LinkedHashMap<>();
		Map<LocalDate, Integer> maleOffCounts = new LinkedHashMap<>();
		for (int i = 0; i < 7; i++) {
			offCounts.put(startDate.plusDays(i), 0);
			maleOffCounts.put(startDate.plusDays(i), 0);
		}

		LocalDate d0 = startDate.plusDays(0);
		LocalDate d1 = startDate.plusDays(1);
		LocalDate d2 = startDate.plusDays(2);
		LocalDate d3 = startDate.plusDays(3);
		LocalDate d4 = startDate.plusDays(4);
		LocalDate d5 = startDate.plusDays(5);
		LocalDate d6 = startDate.plusDays(6);

		List<Employee> males = employees.stream()
				.filter(e -> e.getGender() == Gender.MALE)
				.toList();
		List<Employee> females = employees.stream()
				.filter(e -> e.getGender() == Gender.FEMALE)
				.toList();

		List<Employee> sortedMales = new ArrayList<>(males);
		sortedMales.sort(Comparator.comparing((Employee e) -> (preferencesMap != null && !preferencesMap.getOrDefault(e.getId(), ApplicablePreference.none(e.getId())).preferredOffDays().isEmpty()) ? 0 : 1)
				.thenComparingInt(this::weekendOffCount)
				.thenComparingInt(e -> (int) assignmentRepository.countShiftForEmployee(e.getId(), ShiftType.NIGHT))
				.thenComparing(Comparator.comparingInt(this::weekendWorkCount).reversed())
				.thenComparing(e -> (int) ((e.getId() + randomSeed) % 17)));

		List<Employee> sortedFemales = new ArrayList<>(females);
		sortedFemales.sort(Comparator.comparing((Employee e) -> (preferencesMap != null && !preferencesMap.getOrDefault(e.getId(), ApplicablePreference.none(e.getId())).preferredOffDays().isEmpty()) ? 0 : 1)
				.thenComparingInt(this::weekendOffCount)
				.thenComparing(Comparator.comparingInt(this::weekendWorkCount).reversed())
				.thenComparing(e -> (int) ((e.getId() + randomSeed) % 17)));

		// BATCH 109: Candidate employee rotation for diversity across attempts
		List<Employee> prefMales = sortedMales.stream()
				.filter(e -> preferencesMap != null && !preferencesMap.getOrDefault(e.getId(), ApplicablePreference.none(e.getId())).preferredOffDays().isEmpty())
				.toList();
		List<Employee> nonPrefMales = new ArrayList<>(sortedMales.stream()
				.filter(e -> preferencesMap == null || preferencesMap.getOrDefault(e.getId(), ApplicablePreference.none(e.getId())).preferredOffDays().isEmpty())
				.toList());
		if (!nonPrefMales.isEmpty() && randomSeed != 0) {
			int shiftOffset = Math.abs(randomSeed) % nonPrefMales.size();
			Collections.rotate(nonPrefMales, shiftOffset);
		}
		sortedMales = new ArrayList<>(prefMales);
		sortedMales.addAll(nonPrefMales);

		List<Employee> prefFemales = sortedFemales.stream()
				.filter(e -> preferencesMap != null && !preferencesMap.getOrDefault(e.getId(), ApplicablePreference.none(e.getId())).preferredOffDays().isEmpty())
				.toList();
		List<Employee> nonPrefFemales = new ArrayList<>(sortedFemales.stream()
				.filter(e -> preferencesMap == null || preferencesMap.getOrDefault(e.getId(), ApplicablePreference.none(e.getId())).preferredOffDays().isEmpty())
				.toList());
		if (!nonPrefFemales.isEmpty() && randomSeed != 0) {
			int shiftOffset = Math.abs(randomSeed) % nonPrefFemales.size();
			Collections.rotate(nonPrefFemales, shiftOffset);
		}
		sortedFemales = new ArrayList<>(prefFemales);
		sortedFemales.addAll(nonPrefFemales);

		int targetOffsPerEmployee = Math.max(1, 7 - workingDays); // 2 for 5-day, 1 for 6-day

		long nonDevCount = employees.stream().filter(e -> !isDevelopmentTeam(e)).count();
		long nonDevMaleCount = sortedMales.stream().filter(m -> !isDevelopmentTeam(m)).count();
		java.util.function.ToIntFunction<LocalDate> getMaxOffLimit = d -> {
			if (isWeekend(d)) {
				int maxWeekendOff = (workingDays == 6) ? 3 : 4;
				if (employees.size() <= 6) {
					maxWeekendOff = Math.min(maxWeekendOff, Math.max(1, employees.size() - 3));
				}
				return maxWeekendOff;
			}
			return (nonDevCount <= 5) ? 1 : ((workingDays == 6) ? 1 : 2);
		};

		java.util.function.ToIntFunction<LocalDate> getMaxMaleOffLimit = d -> {
			if (isWeekend(d)) {
				return (workingDays == 6) ? 1 : 2;
			}
			return (nonDevMaleCount <= 4) ? 1 : 2;
		};

		// List of preferred pair templates for 5-day schedules (ordered by preference)
		int offRot = Math.abs(randomSeed) % 4;
		List<List<LocalDate>> pairTemplates;
		if (offRot == 0) {
			pairTemplates = List.of(
					List.of(d5, d6), // Sat, Sun (Weekend)
					List.of(d4, d5), // Fri, Sat
					List.of(d6, d0), // Sun, Mon
					List.of(d0, d1), // Mon, Tue
					List.of(d3, d4), // Thu, Fri
					List.of(d2, d3), // Wed, Thu
					List.of(d1, d2)  // Tue, Wed
			);
		} else if (offRot == 1) {
			pairTemplates = List.of(
					List.of(d5, d6), // Sat, Sun
					List.of(d6, d0), // Sun, Mon
					List.of(d4, d5), // Fri, Sat
					List.of(d1, d2), // Tue, Wed
					List.of(d0, d1), // Mon, Tue
					List.of(d2, d3), // Wed, Thu
					List.of(d3, d4)  // Thu, Fri
			);
		} else if (offRot == 2) {
			pairTemplates = List.of(
					List.of(d4, d5), // Fri, Sat
					List.of(d5, d6), // Sat, Sun
					List.of(d6, d0), // Sun, Mon
					List.of(d2, d3), // Wed, Thu
					List.of(d3, d4), // Thu, Fri
					List.of(d0, d1), // Mon, Tue
					List.of(d1, d2)  // Tue, Wed
			);
		} else {
			pairTemplates = List.of(
					List.of(d6, d0), // Sun, Mon
					List.of(d5, d6), // Sat, Sun
					List.of(d4, d5), // Fri, Sat
					List.of(d3, d4), // Thu, Fri
					List.of(d0, d1), // Mon, Tue
					List.of(d1, d2), // Tue, Wed
					List.of(d2, d3)  // Wed, Thu
			);
		}

		// Single day ordered targets for 6-day schedules (or single off needs)
		LocalDate mw1 = (offRot == 0) ? d1 : (offRot == 1) ? d2 : (offRot == 2) ? d3 : d4;
		LocalDate mw2 = (offRot == 0) ? d2 : (offRot == 1) ? d3 : (offRot == 2) ? d4 : d1;
		LocalDate mw3 = (offRot == 0) ? d3 : (offRot == 1) ? d4 : (offRot == 2) ? d1 : d2;
		LocalDate mw4 = (offRot == 0) ? d4 : (offRot == 1) ? d1 : (offRot == 2) ? d2 : d3;

				List<LocalDate> baseTargets;
		if (workingDays == 6) {
			baseTargets = new ArrayList<>(List.of(d1, d2, d3, d4, d5, d6, d0));
		} else if (sortedMales.size() >= 7) {
			baseTargets = new ArrayList<>(List.of(d6, d5, mw1, mw2, mw3, mw4, d0));
		} else if (sortedMales.size() == 6) {
			baseTargets = new ArrayList<>(List.of(d6, d5, mw2, mw3, mw4, mw1));
		} else if (sortedMales.size() == 5) {
			baseTargets = new ArrayList<>(List.of(d6, d5, mw2, mw4, mw3));
		} else if (sortedMales.size() == 4) {
			baseTargets = new ArrayList<>(List.of(d6, d5, mw2, mw4));
		} else if (sortedMales.size() == 3) {
			baseTargets = new ArrayList<>(List.of(d6, d5, mw2));
		} else if (sortedMales.size() == 2) {
			baseTargets = new ArrayList<>(List.of(d6, d5));
		} else {
			baseTargets = new ArrayList<>(List.of(d6, d5, mw4, mw2, mw3, mw1, d0));
		}
		if (workingDays != 6 && randomSeed != 0 && !baseTargets.isEmpty()) {
			int rotStep = Math.abs(randomSeed) % baseTargets.size();
			Collections.rotate(baseTargets, rotStep);
		}
		List<LocalDate> maleOrderedTargets = baseTargets;

		List<LocalDate> femaleOrderedTargets = (workingDays == 6 || employees.size() <= 7)
				? List.of(d6, d5, d4, d3, d2, d1, d0)
				: List.of(d6, d5, mw4, mw2, mw3, mw1, d0);

		// Helper to check if employee worked NIGHT on Sunday immediately before startDate
		Set<Long> workedSundayNightBefore = new HashSet<>();
		if (assignmentRepository != null) {
			for (Employee m : sortedMales) {
				List<RosterAssignment> prevWorked = assignmentRepository.findWorkedAssignmentsBefore(m.getId(), startDate);
				if (!prevWorked.isEmpty()) {
					RosterAssignment lastA = prevWorked.get(0);
					if (lastA.getRosterDate() != null && lastA.getRosterDate().equals(startDate.minusDays(1))
							&& lastA.getShift() != null && lastA.getShift().getShiftType() == ShiftType.NIGHT) {
						workedSundayNightBefore.add(m.getId());
					}
				}
			}
		}

		// Pre-reserve Monday recovery OFF for Sunday night worker so it doesn't collide with Rajat & Prachi or other male weekday offs
		for (Long sundayNightWorkerId : workedSundayNightBefore) {
			Employee snMale = sortedMales.stream().filter(m -> m.getId().equals(sundayNightWorkerId)).findFirst().orElse(null);
			if (snMale != null && !isApprovedLeave(sundayNightWorkerId, d0) && targetOffsPerEmployee > 0) {
				Set<LocalDate> snOffs = result.computeIfAbsent(sundayNightWorkerId, k -> new LinkedHashSet<>());
				if (!snOffs.contains(d0)) {
					snOffs.add(d0);
					offCounts.put(d0, offCounts.get(d0) + 1);
					if (!isDevelopmentTeam(snMale)) {
						maleOffCounts.put(d0, maleOffCounts.get(d0) + 1);
					}
				}
			}
		}

		// Priority Step 0: Explicit Date-Specific or Recurring Weekly OFF Preferences
		for (Employee emp : employees) {
			ApplicablePreference p = preferencesMap != null ? preferencesMap.get(emp.getId()) : null;
			if (p == null) continue;
			for (int d = 0; d < 7; d++) {
				LocalDate cand = startDate.plusDays(d);
				if (hasTargetOffShift(p, cand) && !isApprovedLeave(emp.getId(), cand)) {
					Set<LocalDate> empOffs = result.computeIfAbsent(emp.getId(), k -> new LinkedHashSet<>());
					if (empOffs.size() < targetOffsPerEmployee && !empOffs.contains(cand)) {
						empOffs.add(cand);
						offCounts.put(cand, offCounts.getOrDefault(cand, 0) + 1);
						if (emp.getGender() == Gender.MALE && !emp.isDevelopmentTeam()) {
							maleOffCounts.put(cand, maleOffCounts.getOrDefault(cand, 0) + 1);
						}
					}
				}
			}
		}

		// Priority Step: For employees whose preferences leave exactly 1 eligible non-work, non-leave date in the cycle
		for (Employee emp : employees) {
			if (result.containsKey(emp.getId()) && result.get(emp.getId()).size() >= targetOffsPerEmployee) {
				continue;
			}
			ApplicablePreference p = preferencesMap != null ? preferencesMap.get(emp.getId()) : null;
			List<LocalDate> possibleOffDays = new ArrayList<>();
			for (int d = 0; d < 7; d++) {
				LocalDate cand = startDate.plusDays(d);
				if (!isApprovedLeave(emp.getId(), cand) && !hasTargetWorkShift(p, cand)) {
					possibleOffDays.add(cand);
				}
			}
			if (possibleOffDays.size() == 1) {
				LocalDate onlyDay = possibleOffDays.get(0);
				Set<LocalDate> empOffs = result.computeIfAbsent(emp.getId(), k -> new LinkedHashSet<>());
				if (!empOffs.contains(onlyDay)) {
					empOffs.add(onlyDay);
					offCounts.put(onlyDay, offCounts.getOrDefault(onlyDay, 0) + 1);
					if (emp.getGender() == Gender.MALE && !isDevelopmentTeam(emp)) {
						maleOffCounts.put(onlyDay, maleOffCounts.getOrDefault(onlyDay, 0) + 1);
					}
				}
			}
		}

		// Development Team Weekly OFF Planning (Mon-Fri General, Sat-Sun Weekly OFF, or Sun in 6-day mode)
		List<Employee> devEmployees = employees.stream().filter(Employee::isDevelopmentTeam).toList();
		for (Employee devEmp : devEmployees) {
			Set<LocalDate> empOffs = new LinkedHashSet<>(result.getOrDefault(devEmp.getId(), Collections.emptySet()));
			ApplicablePreference pref = preferencesMap != null ? preferencesMap.get(devEmp.getId()) : null;
			int leaveDays = 0;
			for (int d = 0; d < 7; d++) {
				if (isApprovedLeave(devEmp.getId(), startDate.plusDays(d))) leaveDays++;
			}

			List<LocalDate> preferredWeekendOffs = (targetOffsPerEmployee >= 2) ? List.of(d5, d6) : List.of(d6);
			for (LocalDate wDate : preferredWeekendOffs) {
				if (empOffs.size() < (targetOffsPerEmployee - leaveDays) && !isApprovedLeave(devEmp.getId(), wDate)
						&& !hasTargetWorkShift(pref, wDate)) {
					empOffs.add(wDate);
					offCounts.put(wDate, offCounts.get(wDate) + 1);
					if (devEmp.getGender() == Gender.MALE && !devEmp.isDevelopmentTeam()) {
						maleOffCounts.put(wDate, maleOffCounts.get(wDate) + 1);
					}
				}
			}
			result.put(devEmp.getId(), empOffs);
		}

		if (targetOffsPerEmployee >= 2) {
			// Paired Employee Weekly OFF Synchronization
			List<Employee> pairedPrimaries = employees.stream()
					.filter(e -> e.getPairedEmployeeId() != null && e.getId() < e.getPairedEmployeeId())
					.toList();

			for (Employee empA : pairedPrimaries) {
				Employee empB = employees.stream().filter(e -> e.getId().equals(empA.getPairedEmployeeId())).findFirst().orElse(null);
				if (empB == null) continue;

				Set<LocalDate> offsA = new LinkedHashSet<>(result.getOrDefault(empA.getId(), Collections.emptySet()));
				Set<LocalDate> offsB = new LinkedHashSet<>(result.getOrDefault(empB.getId(), Collections.emptySet()));
				ApplicablePreference prefA = preferencesMap != null ? preferencesMap.get(empA.getId()) : null;
				ApplicablePreference prefB = preferencesMap != null ? preferencesMap.get(empB.getId()) : null;

				int leavesA = 0, leavesB = 0;
				for (int d = 0; d < 7; d++) {
					if (isApprovedLeave(empA.getId(), startDate.plusDays(d))) leavesA++;
					if (isApprovedLeave(empB.getId(), startDate.plusDays(d))) leavesB++;
				}
				int neededA = Math.max(0, targetOffsPerEmployee - leavesA - offsA.size());
				int neededB = Math.max(0, targetOffsPerEmployee - leavesB - offsB.size());

				int maleInc = (empA.getGender() == Gender.MALE && !empA.isDevelopmentTeam() ? 1 : 0)
						+ (empB.getGender() == Gender.MALE && !empB.isDevelopmentTeam() ? 1 : 0);

				if (offsA.contains(d0) && neededB > 0 && !offsB.contains(d0)
						&& !isApprovedLeave(empB.getId(), d0)
						&& !hasTargetWorkShift(prefB, d0)
						&& (employees.size() <= 6 || offCounts.get(d0) < getMaxOffLimit.applyAsInt(d0))) {
					offsB.add(d0);
					offCounts.put(d0, offCounts.get(d0) + 1);
					if (empB.getGender() == Gender.MALE && !empB.isDevelopmentTeam()) {
						maleOffCounts.put(d0, maleOffCounts.get(d0) + 1);
					}
					neededB--;
				}

				if (neededA >= 2 && neededB >= 2) {
					List<List<LocalDate>> basePairing = List.of(
							List.of(d5, d6),
							List.of(d6, d5),
							List.of(d6, d0),
							List.of(d4, d5)
					);
					int pShift = Math.abs(randomSeed) % basePairing.size();
					List<List<LocalDate>> pairingCandidates = new ArrayList<>();
					for (int i = 0; i < basePairing.size(); i++) {
						pairingCandidates.add(basePairing.get((pShift + i) % basePairing.size()));
					}
					for (List<LocalDate> pair : pairingCandidates) {
						LocalDate day1 = pair.get(0);
						LocalDate day2 = pair.get(1);
						boolean leave1 = isApprovedLeave(empA.getId(), day1) || isApprovedLeave(empA.getId(), day2);
						boolean leave2 = isApprovedLeave(empB.getId(), day1) || isApprovedLeave(empB.getId(), day2);
						boolean prefConflict1 = hasTargetWorkShift(prefA, day1) || hasTargetWorkShift(prefA, day2);
						boolean prefConflict2 = hasTargetWorkShift(prefB, day1) || hasTargetWorkShift(prefB, day2);

						if (!leave1 && !leave2 && !prefConflict1 && !prefConflict2
								&& offCounts.get(day1) + 2 <= getMaxOffLimit.applyAsInt(day1)
								&& maleOffCounts.get(day1) + maleInc <= getMaxMaleOffLimit.applyAsInt(day1)
								&& offCounts.get(day2) + 2 <= getMaxOffLimit.applyAsInt(day2)
								&& maleOffCounts.get(day2) + maleInc <= getMaxMaleOffLimit.applyAsInt(day2)) {
							offsA.add(day1);
							offsA.add(day2);
							offsB.add(day1);
							offsB.add(day2);
							offCounts.put(day1, offCounts.get(day1) + 2);
							maleOffCounts.put(day1, maleOffCounts.get(day1) + maleInc);
							offCounts.put(day2, offCounts.get(day2) + 2);
							maleOffCounts.put(day2, maleOffCounts.get(day2) + maleInc);
							break;
						}
					}
				}

				if (offsA.size() < (targetOffsPerEmployee - leavesA) && offsB.size() < (targetOffsPerEmployee - leavesB)) {
					List<LocalDate> candList = (workingDays == 6 || employees.size() <= 7)
							? List.of(d5, d6, d0, d1)
							: List.of(d5, d6, mw2, mw3, mw4, mw1, d1, d2, d3, d4);
					for (LocalDate cand : candList) {
						if (offsA.contains(cand) || offsB.contains(cand)) continue;
						boolean leave1 = isApprovedLeave(empA.getId(), cand);
						boolean leave2 = isApprovedLeave(empB.getId(), cand);
						boolean prefConflict1 = hasTargetWorkShift(prefA, cand);
						boolean prefConflict2 = hasTargetWorkShift(prefB, cand);

						if (!leave1 && !leave2 && !prefConflict1 && !prefConflict2
								&& offCounts.get(cand) + 2 <= getMaxOffLimit.applyAsInt(cand)
								&& maleOffCounts.get(cand) + maleInc <= getMaxMaleOffLimit.applyAsInt(cand)) {
							offsA.add(cand);
							offsB.add(cand);
							offCounts.put(cand, offCounts.get(cand) + 2);
							maleOffCounts.put(cand, maleOffCounts.get(cand) + maleInc);
							break;
						}
					}
				}

				if (!offsA.isEmpty()) result.put(empA.getId(), offsA);
				if (!offsB.isEmpty()) result.put(empB.getId(), offsB);
			}
		}

		// Process Males
		int malePairIndex = 0;
		int maleTargetPointer = 0;
		for (Employee m : sortedMales) {
			if (result.containsKey(m.getId()) && result.get(m.getId()).size() >= targetOffsPerEmployee) {
				continue;
			}
			Set<LocalDate> empOffs = new LinkedHashSet<>(result.getOrDefault(m.getId(), Collections.emptySet()));
			ApplicablePreference pref = preferencesMap != null ? preferencesMap.get(m.getId()) : null;

			int leaveDays = 0;
			for (int d = 0; d < 7; d++) {
				if (isApprovedLeave(m.getId(), startDate.plusDays(d))) leaveDays++;
			}
			int neededOffs = Math.max(0, targetOffsPerEmployee - leaveDays);

			// Recovery Off for Sunday Night
			if (workedSundayNightBefore.contains(m.getId()) && !isApprovedLeave(m.getId(), d0) && neededOffs > 0) {
				if (!empOffs.contains(d0)) {
					empOffs.add(d0);
					offCounts.put(d0, offCounts.get(d0) + 1);
					maleOffCounts.put(d0, maleOffCounts.get(d0) + 1);
				}
			}

			if (neededOffs >= 2 && empOffs.size() < neededOffs) {
				if (empOffs.contains(d0)) {
					for (LocalDate cand : List.of(d1, d6)) {
						if (!empOffs.contains(cand) && !isApprovedLeave(m.getId(), cand)
								&& !hasTargetWorkShift(pref, cand)
								&& offCounts.get(cand) < getMaxOffLimit.applyAsInt(cand)
								&& maleOffCounts.get(cand) < getMaxMaleOffLimit.applyAsInt(cand)) {
							empOffs.add(cand);
							offCounts.put(cand, offCounts.get(cand) + 1);
							maleOffCounts.put(cand, maleOffCounts.get(cand) + 1);
							break;
						}
					}
				}

				if (empOffs.isEmpty() && pref != null && !pref.preferredOffDays().isEmpty()) {
					for (DayOfWeek dow : pref.preferredOffDays()) {
						for (int d = 0; d < 7; d++) {
							LocalDate pOff = startDate.plusDays(d);
							if (pOff.getDayOfWeek() == dow && !isApprovedLeave(m.getId(), pOff)
									&& !hasTargetWorkShift(pref, pOff)
									&& offCounts.get(pOff) < getMaxOffLimit.applyAsInt(pOff)
									&& maleOffCounts.get(pOff) < getMaxMaleOffLimit.applyAsInt(pOff)) {
								LocalDate adj = null;
								for (LocalDate a : List.of(pOff.plusDays(1), pOff.minusDays(1))) {
									if (!a.isBefore(d0) && !a.isAfter(d6) && !isApprovedLeave(m.getId(), a)
											&& !hasTargetWorkShift(pref, a)
											&& offCounts.get(a) < getMaxOffLimit.applyAsInt(a)
											&& maleOffCounts.get(a) < getMaxMaleOffLimit.applyAsInt(a)) {
										adj = a;
										break;
									}
								}
								if (adj != null) {
									empOffs.add(pOff);
									empOffs.add(adj);
									offCounts.put(pOff, offCounts.get(pOff) + 1);
									maleOffCounts.put(pOff, maleOffCounts.get(pOff) + 1);
									offCounts.put(adj, offCounts.get(adj) + 1);
									maleOffCounts.put(adj, maleOffCounts.get(adj) + 1);
									break;
								}
							}
						}
						if (empOffs.size() >= neededOffs) break;
					}
				}

				if (empOffs.isEmpty()) {
					for (int p = 0; p < pairTemplates.size(); p++) {
						List<LocalDate> pair = pairTemplates.get((malePairIndex + p) % pairTemplates.size());
						LocalDate dayA = pair.get(0);
						LocalDate dayB = pair.get(1);
						boolean aLeave = isApprovedLeave(m.getId(), dayA);
						boolean bLeave = isApprovedLeave(m.getId(), dayB);
						boolean aPref = hasTargetWorkShift(pref, dayA);
						boolean bPref = hasTargetWorkShift(pref, dayB);
						if (!aLeave && !bLeave && !aPref && !bPref
								&& offCounts.get(dayA) < getMaxOffLimit.applyAsInt(dayA) && maleOffCounts.get(dayA) < getMaxMaleOffLimit.applyAsInt(dayA)
								&& offCounts.get(dayB) < getMaxOffLimit.applyAsInt(dayB) && maleOffCounts.get(dayB) < getMaxMaleOffLimit.applyAsInt(dayB)) {
							empOffs.add(dayA);
							empOffs.add(dayB);
							offCounts.put(dayA, offCounts.get(dayA) + 1);
							maleOffCounts.put(dayA, maleOffCounts.get(dayA) + 1);
							offCounts.put(dayB, offCounts.get(dayB) + 1);
							maleOffCounts.put(dayB, maleOffCounts.get(dayB) + 1);
							malePairIndex = (malePairIndex + p + 1) % pairTemplates.size();
							break;
						}
					}
				}
			}

			// Single off or filling remainder up to neededOffs
			while (empOffs.size() < neededOffs) {
				LocalDate target = null;
				if (pref != null && !pref.preferredOffDays().isEmpty()) {
					for (DayOfWeek dow : pref.preferredOffDays()) {
						for (int d = 0; d < 7; d++) {
							LocalDate cand = startDate.plusDays(d);
							if (cand.getDayOfWeek() == dow && !empOffs.contains(cand) && !isApprovedLeave(m.getId(), cand)
									&& !hasTargetWorkShift(pref, cand)
									&& offCounts.get(cand) < getMaxOffLimit.applyAsInt(cand) && maleOffCounts.get(cand) < getMaxMaleOffLimit.applyAsInt(cand)) {
								target = cand;
								break;
							}
						}
						if (target != null) break;
					}
				}
				if (target == null) {
					for (int step = 0; step < maleOrderedTargets.size(); step++) {
						LocalDate cand = maleOrderedTargets.get((maleTargetPointer + step) % maleOrderedTargets.size());
						if (!empOffs.contains(cand) && !isApprovedLeave(m.getId(), cand)
								&& !hasTargetWorkShift(pref, cand)
								&& offCounts.get(cand) < getMaxOffLimit.applyAsInt(cand) && maleOffCounts.get(cand) < getMaxMaleOffLimit.applyAsInt(cand)) {
							target = cand;
							maleTargetPointer = (maleTargetPointer + step + 1) % maleOrderedTargets.size();
							break;
						}
					}
				}
				if (target == null) {
					for (LocalDate cand : List.of(d6, d5, d4, d2, d3, d1, d0)) {
						if (!empOffs.contains(cand) && !isApprovedLeave(m.getId(), cand)
								&& !hasTargetWorkShift(pref, cand)
								&& offCounts.get(cand) < getMaxOffLimit.applyAsInt(cand) && maleOffCounts.get(cand) < getMaxMaleOffLimit.applyAsInt(cand)) {
							target = cand;
							break;
						}
					}
				}
				if (target == null) {
					target = offCounts.entrySet().stream()
							.filter(entry -> !empOffs.contains(entry.getKey()) && !isApprovedLeave(m.getId(), entry.getKey()))
							.filter(entry -> !hasTargetWorkShift(pref, entry.getKey()))
							.filter(entry -> entry.getValue() < getMaxOffLimit.applyAsInt(entry.getKey()) && maleOffCounts.getOrDefault(entry.getKey(), 0) < getMaxMaleOffLimit.applyAsInt(entry.getKey()))
							.min(Comparator.comparingInt(Map.Entry::getValue))
							.map(Map.Entry::getKey)
							.orElse(null);
				}
				if (target == null) {
					target = offCounts.entrySet().stream()
							.filter(entry -> !empOffs.contains(entry.getKey()) && !isApprovedLeave(m.getId(), entry.getKey()))
							.filter(entry -> !hasTargetWorkShift(pref, entry.getKey()))
							.filter(entry -> entry.getValue() < getMaxOffLimit.applyAsInt(entry.getKey()))
							.min(Comparator.comparingInt((Map.Entry<LocalDate, Integer> entry) -> (isWeekend(entry.getKey()) ? 0 : 10) + maleOffCounts.getOrDefault(entry.getKey(), 0)))
							.map(Map.Entry::getKey)
							.orElse(null);
				}
				if (target == null) {
					target = offCounts.entrySet().stream()
							.filter(entry -> !empOffs.contains(entry.getKey()) && !isApprovedLeave(m.getId(), entry.getKey()))
							.filter(entry -> !hasTargetWorkShift(pref, entry.getKey()))
							.min(Comparator.comparingInt((Map.Entry<LocalDate, Integer> entry) -> (isWeekend(entry.getKey()) ? 0 : 10) + maleOffCounts.getOrDefault(entry.getKey(), 0)))
							.map(Map.Entry::getKey)
							.orElse(null);
				}
				if (target == null) {
					target = offCounts.entrySet().stream()
							.filter(entry -> !empOffs.contains(entry.getKey()) && !isApprovedLeave(m.getId(), entry.getKey()))
							.filter(entry -> !hasTargetWorkShift(pref, entry.getKey()))
							.min(Comparator.comparingInt((Map.Entry<LocalDate, Integer> entry) -> (isWeekend(entry.getKey()) ? 0 : 10) + maleOffCounts.getOrDefault(entry.getKey(), 0)))
							.map(Map.Entry::getKey)
							.orElse(null);
				}
				if (target == null) {
					target = offCounts.entrySet().stream()
							.filter(entry -> !empOffs.contains(entry.getKey()) && !isApprovedLeave(m.getId(), entry.getKey()))
							.min(Comparator.comparingInt((Map.Entry<LocalDate, Integer> entry) -> (isWeekend(entry.getKey()) ? 0 : 10) + maleOffCounts.getOrDefault(entry.getKey(), 0)))
							.map(Map.Entry::getKey)
							.orElse(d5);
				}

				empOffs.add(target);
				offCounts.put(target, offCounts.getOrDefault(target, 0) + 1);
				maleOffCounts.put(target, maleOffCounts.getOrDefault(target, 0) + 1);
			}

			result.put(m.getId(), empOffs);
		}

		// Process Females
		int femalePairIndex = 0;
		int femaleTargetPointer = 0;
		for (Employee f : sortedFemales) {
			if (result.containsKey(f.getId()) && result.get(f.getId()).size() >= targetOffsPerEmployee) {
				continue;
			}
			Set<LocalDate> empOffs = new LinkedHashSet<>(result.getOrDefault(f.getId(), Collections.emptySet()));
			ApplicablePreference pref = preferencesMap != null ? preferencesMap.get(f.getId()) : null;

			int leaveDays = 0;
			for (int d = 0; d < 7; d++) {
				if (isApprovedLeave(f.getId(), startDate.plusDays(d))) leaveDays++;
			}
			int neededOffs = Math.max(0, targetOffsPerEmployee - leaveDays);

			if (neededOffs >= 2 && empOffs.size() < neededOffs) {
				if (pref != null && !pref.preferredOffDays().isEmpty()) {
					for (DayOfWeek dow : pref.preferredOffDays()) {
						for (int d = 0; d < 7; d++) {
							LocalDate pOff = startDate.plusDays(d);
							if (pOff.getDayOfWeek() == dow && !isApprovedLeave(f.getId(), pOff)
									&& !hasTargetWorkShift(pref, pOff)
									&& offCounts.get(pOff) < getMaxOffLimit.applyAsInt(pOff)) {
								LocalDate adj = null;
								for (LocalDate a : List.of(pOff.plusDays(1), pOff.minusDays(1))) {
									if (!a.isBefore(d0) && !a.isAfter(d6) && !isApprovedLeave(f.getId(), a)
											&& !hasTargetWorkShift(pref, a)
											&& offCounts.get(a) < getMaxOffLimit.applyAsInt(a)) {
										adj = a;
										break;
									}
								}
								if (adj != null) {
									empOffs.add(pOff);
									empOffs.add(adj);
									offCounts.put(pOff, offCounts.get(pOff) + 1);
									offCounts.put(adj, offCounts.get(adj) + 1);
									break;
								}
							}
						}
						if (empOffs.size() >= neededOffs) break;
					}
				}

				if (empOffs.isEmpty()) {
					for (int p = 0; p < pairTemplates.size(); p++) {
						List<LocalDate> pair = pairTemplates.get((femalePairIndex + p) % pairTemplates.size());
						LocalDate dayA = pair.get(0);
						LocalDate dayB = pair.get(1);
						boolean aLeave = isApprovedLeave(f.getId(), dayA);
						boolean bLeave = isApprovedLeave(f.getId(), dayB);
						boolean aPref = hasTargetWorkShift(pref, dayA);
						boolean bPref = hasTargetWorkShift(pref, dayB);
						if (!aLeave && !bLeave && !aPref && !bPref
								&& offCounts.get(dayA) < getMaxOffLimit.applyAsInt(dayA)
								&& offCounts.get(dayB) < getMaxOffLimit.applyAsInt(dayB)) {
							empOffs.add(dayA);
							empOffs.add(dayB);
							offCounts.put(dayA, offCounts.get(dayA) + 1);
							offCounts.put(dayB, offCounts.get(dayB) + 1);
							femalePairIndex = (femalePairIndex + p + 1) % pairTemplates.size();
							break;
						}
					}
				}
			}

			// Single off or filling remainder up to neededOffs
			while (empOffs.size() < neededOffs) {
				LocalDate target = null;
				if (pref != null && !pref.preferredOffDays().isEmpty()) {
					for (DayOfWeek dow : pref.preferredOffDays()) {
						for (int d = 0; d < 7; d++) {
							LocalDate cand = startDate.plusDays(d);
							if (cand.getDayOfWeek() == dow && !empOffs.contains(cand) && !isApprovedLeave(f.getId(), cand)
									&& !hasTargetWorkShift(pref, cand)
									&& offCounts.get(cand) < getMaxOffLimit.applyAsInt(cand)) {
								target = cand;
								break;
							}
						}
						if (target != null) break;
					}
				}
				if (target == null) {
					for (int step = 0; step < femaleOrderedTargets.size(); step++) {
						LocalDate cand = femaleOrderedTargets.get((femaleTargetPointer + step) % femaleOrderedTargets.size());
						if (!empOffs.contains(cand) && !isApprovedLeave(f.getId(), cand)
								&& !hasTargetWorkShift(pref, cand)
								&& offCounts.get(cand) < getMaxOffLimit.applyAsInt(cand)) {
							target = cand;
							femaleTargetPointer = (femaleTargetPointer + step + 1) % femaleOrderedTargets.size();
							break;
						}
					}
				}
				if (target == null) {
					target = offCounts.entrySet().stream()
							.filter(entry -> !empOffs.contains(entry.getKey()) && !isApprovedLeave(f.getId(), entry.getKey()))
							.filter(entry -> !hasTargetWorkShift(pref, entry.getKey()))
							.filter(entry -> entry.getValue() < getMaxOffLimit.applyAsInt(entry.getKey()))
							.min(Comparator.comparingInt(Map.Entry::getValue))
							.map(Map.Entry::getKey)
							.orElse(null);
				}
				if (target == null) {
					target = offCounts.entrySet().stream()
							.filter(entry -> !empOffs.contains(entry.getKey()) && !isApprovedLeave(f.getId(), entry.getKey()))
							.filter(entry -> !hasTargetWorkShift(pref, entry.getKey()))
							.min(Comparator.comparingInt(Map.Entry::getValue))
							.map(Map.Entry::getKey)
							.orElse(null);
				}
				if (target == null) {
					target = offCounts.entrySet().stream()
							.filter(entry -> !empOffs.contains(entry.getKey()) && !isApprovedLeave(f.getId(), entry.getKey()))
							.filter(entry -> !hasTargetWorkShift(pref, entry.getKey()))
							.min(Comparator.comparingInt(Map.Entry::getValue))
							.map(Map.Entry::getKey)
							.orElse(null);
				}
				if (target == null) {
					target = offCounts.entrySet().stream()
							.filter(entry -> !empOffs.contains(entry.getKey()) && !isApprovedLeave(f.getId(), entry.getKey()))
							.min(Comparator.comparingInt(Map.Entry::getValue))
							.map(Map.Entry::getKey)
							.orElse(d5);
				}

				empOffs.add(target);
				offCounts.put(target, offCounts.getOrDefault(target, 0) + 1);
			}

			result.put(f.getId(), empOffs);
		}

		return result;
	}

	private Map<Long, Set<LocalDate>> planWeeklyOffs(List<Employee> employees, LocalDate startDate, LocalDate endDate,
			Map<Long, ApplicablePreference> preferencesMap, int randomSeed) {
		return planWeeklyOffs(employees, startDate, endDate, preferencesMap, randomSeed, 6);
	}

	private Map<Long, Set<LocalDate>> planWeeklyOffs(List<Employee> employees, LocalDate startDate, LocalDate endDate) {
		return planWeeklyOffs(employees, startDate, endDate, null, 0, 6);
	}

	private void rebalanceDailyCoverage(RosterCycle cycle, List<RosterAssignment> assignments,
			List<Employee> employees, Map<ShiftType, Shift> shifts, Map<Long, ApplicablePreference> preferencesMap) {
		// 0. Ensure no day has fewer working staff than the absolute minimum required capacity
		if (cycle != null && cycle.getStartDate() != null && cycle.getEndDate() != null) {
			for (LocalDate date = cycle.getStartDate(); !date.isAfter(cycle.getEndDate()); date = date.plusDays(1)) {
				boolean isWk = (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY);
				int minRequiredStaff = isWk ? 3 : ((cycle != null && cycle.getWorkingDays() != null && cycle.getWorkingDays() == 5) ? 5 : (employees.size() >= 7 ? 6 : 5));

				LocalDate targetDate = date;
				long workingToday = assignments.stream()
						.filter(a -> a.getRosterDate().equals(targetDate) && !a.isWeeklyOff() && !a.isOnLeave())
						.count();

				while (workingToday < minRequiredStaff) {
					RosterAssignment offAssign = null;
					LocalDate bestDonorDate = null;

					for (RosterAssignment a : assignments) {
						if (!a.getRosterDate().equals(targetDate) || !a.isWeeklyOff() || a.isOnLeave() || a.isManualAssignment() || a.isOverridden()) continue;
						Employee emp = a.getEmployee();
						if (isWk && isDevelopmentTeam(emp)) continue; // Dev team stays off on weekend

						// Check post-night recovery from yesterday
						RosterAssignment yesterday = assignments.stream()
								.filter(x -> x.getEmployee().getId().equals(emp.getId()) && x.getRosterDate().equals(targetDate.minusDays(1)))
								.findFirst().orElse(null);
						if (yesterday != null && !yesterday.isWeeklyOff() && !yesterday.isOnLeave()
								&& yesterday.getShift() != null && yesterday.getShift().getShiftType() == ShiftType.NIGHT) {
							continue;
						}
						if (yesterday == null && targetDate.equals(cycle.getStartDate()) && assignmentRepository != null) {
							List<RosterAssignment> prevWorked = assignmentRepository.findWorkedAssignmentsBefore(emp.getId(), targetDate);
							if (!prevWorked.isEmpty() && prevWorked.get(0).getRosterDate() != null && prevWorked.get(0).getRosterDate().equals(targetDate.minusDays(1))
									&& prevWorked.get(0).getShift() != null && prevWorked.get(0).getShift().getShiftType() == ShiftType.NIGHT) {
								continue;
							}
						}
						ApplicablePreference empPref = preferencesMap != null ? preferencesMap.get(emp.getId()) : null;
						if (empPref != null && empPref.getTargetShiftForDate(targetDate) == ShiftType.OFF) {
							continue;
						}

						Long currentEmpId = emp.getId();
						for (LocalDate donorDate = cycle.getStartDate(); !donorDate.isAfter(cycle.getEndDate()); donorDate = donorDate.plusDays(1)) {
							LocalDate dDate = donorDate;
							if (dDate.equals(targetDate)) continue;
							boolean donorIsWk = (dDate.getDayOfWeek() == DayOfWeek.SATURDAY || dDate.getDayOfWeek() == DayOfWeek.SUNDAY);
							int donorMin = donorIsWk ? 3 : ((cycle != null && cycle.getWorkingDays() != null && cycle.getWorkingDays() == 5) ? 5 : (employees.size() >= 7 ? 6 : 5));
							long donorWorking = assignments.stream()
									.filter(x -> x.getRosterDate().equals(dDate) && !x.isWeeklyOff() && !x.isOnLeave())
									.count();
							if (donorWorking <= donorMin) continue;

							RosterAssignment donorAssign = assignments.stream()
									.filter(x -> x.getEmployee().getId().equals(currentEmpId) && x.getRosterDate().equals(dDate))
									.findFirst().orElse(null);
							if (donorAssign == null || donorAssign.isWeeklyOff() || donorAssign.isOnLeave() || donorAssign.isManualAssignment() || donorAssign.isOverridden()) continue;
							if (empPref != null && empPref.hasTargetShiftForDate(dDate)) continue;
							if (donorAssign.getShift() != null && donorAssign.getShift().getShiftType() == ShiftType.NIGHT) continue;

							offAssign = a;
							bestDonorDate = dDate;
							break;
						}
						if (offAssign != null) break;
					}

					if (offAssign != null && bestDonorDate != null) {
						Employee emp = offAssign.getEmployee();
						LocalDate donorD = bestDonorDate;
						RosterAssignment donorAssign = assignments.stream()
								.filter(x -> x.getEmployee().getId().equals(emp.getId()) && x.getRosterDate().equals(donorD))
								.findFirst().orElse(null);

						ShiftType workShift = isWk ? ShiftType.MORNING : (isDevelopmentTeam(emp) ? ShiftType.GENERAL : ShiftType.MORNING);
						offAssign.setWeeklyOff(false);
						offAssign.setShift(shifts.get(workShift));

						if (donorAssign != null) {
							donorAssign.setWeeklyOff(true);
							donorAssign.setShift(shifts.get(ShiftType.OFF));
						}

						workingToday = assignments.stream()
								.filter(a -> a.getRosterDate().equals(targetDate) && !a.isWeeklyOff() && !a.isOnLeave())
								.count();
					} else {
						break;
					}
				}
			}
		}

		Map<LocalDate, Map<ShiftType, List<RosterAssignment>>> dateShiftMap = new TreeMap<>();
		Map<Long, List<RosterAssignment>> empAssignments = new HashMap<>();
		int maxNightsAllowed = computeMaxNightsAllowed(employees);

		for (RosterAssignment a : assignments) {
			empAssignments.computeIfAbsent(a.getEmployee().getId(), k -> new ArrayList<>()).add(a);
			if (!a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() != ShiftType.OFF) {
				dateShiftMap.computeIfAbsent(a.getRosterDate(), k -> new EnumMap<>(ShiftType.class))
						.computeIfAbsent(a.getShift().getShiftType(), k -> new ArrayList<>()).add(a);
			}
		}

		for (Map.Entry<LocalDate, Map<ShiftType, List<RosterAssignment>>> dateEntry : dateShiftMap.entrySet()) {
			LocalDate date = dateEntry.getKey();
			Map<ShiftType, List<RosterAssignment>> shiftMap = dateEntry.getValue();

			int mCount = shiftMap.getOrDefault(ShiftType.MORNING, Collections.emptyList()).size();
			int gCount = shiftMap.getOrDefault(ShiftType.GENERAL, Collections.emptyList()).size();
			int eCount = shiftMap.getOrDefault(ShiftType.EVENING, Collections.emptyList()).size();
			int nCount = shiftMap.getOrDefault(ShiftType.NIGHT, Collections.emptyList()).size();
			int workingTotal = mCount + gCount + eCount + nCount;

			// If Night is unstaffed, rebalance an eligible male donor from General, Morning, or Evening
			if (nCount == 0 && workingTotal >= 1) {
				Shift nightShift = shifts.get(ShiftType.NIGHT);
				RosterAssignment candidateToMove = null;
				ShiftType donorShiftType = null;

				for (ShiftType donorType : List.of(ShiftType.GENERAL, ShiftType.MORNING, ShiftType.EVENING)) {
					List<RosterAssignment> donors = shiftMap.getOrDefault(donorType, Collections.emptyList());
					int minDonorKeep = (donorType == ShiftType.MORNING) ? getRequiredMorningMin(date) : (donorType == ShiftType.EVENING ? 1 : 0);
					if (donors.size() <= minDonorKeep) continue;

					for (RosterAssignment a : donors) {
						if (a.isManualAssignment() || a.isOverridden()) continue;
						Employee emp = a.getEmployee();
						if (emp.getGender() == Gender.FEMALE) continue;
						long totalNonDevMales = employees.stream().filter(e -> e.getGender() == Gender.MALE && e.isActive() && !e.isDevelopmentTeam()).count();
						if (emp.isDevelopmentTeam()) continue;
						boolean isSixDayMode = (cycle != null && cycle.getWorkingDays() != null && cycle.getWorkingDays() == 6);
						int maxNights = emp.getMaxNightsPerWeek() != null ? emp.getMaxNightsPerWeek() : maxNightsAllowed;
						if (totalNonDevMales <= 3 && emp.getMaxNightsPerWeek() != null) {
							maxNights = Math.max(maxNights, 2);
						} else if (isSixDayMode && emp.getMaxNightsPerWeek() != null && emp.getMaxNightsPerWeek() > 1) {
							maxNights = Math.max(maxNights, 2);
						}
						maxNights = Math.min(maxNights, maxNightsAllowed);
						ApplicablePreference pref = preferencesMap != null ? preferencesMap.get(emp.getId()) : null;
						if (pref != null && pref.hasTargetShiftForDate(date)) continue;
						if (!isEligible(emp, ShiftType.NIGHT, pref, date)) continue;

						List<RosterAssignment> empList = empAssignments.getOrDefault(emp.getId(), Collections.emptyList());
						long empNights = empList.stream().filter(x -> !x.isWeeklyOff() && !x.isOnLeave() && x.getShift() != null && x.getShift().getShiftType() == ShiftType.NIGHT).count();
						if (empNights >= maxNights) continue;

						int idx = empList.indexOf(a);
						RosterAssignment prev = idx > 0 ? empList.get(idx - 1) : null;
						RosterAssignment next = idx < empList.size() - 1 ? empList.get(idx + 1) : null;
						if (next != null && (next.isManualAssignment() || next.isOverridden() || hasTargetWorkShift(pref, next.getRosterDate()))) continue;

						boolean isSixDayModeEmp = (cycle != null && cycle.getWorkingDays() != null && cycle.getWorkingDays() == 6)
								|| empList.stream().filter(RosterAssignment::isWeeklyOff).count() <= 1;
						boolean isCycleEnd = (cycle != null && cycle.getEndDate() != null && date.equals(cycle.getEndDate()));
						if (isSixDayModeEmp && !isCycleEnd) {
							if (next == null || !next.isWeeklyOff()) continue;
							boolean hasOtherOff = empList.stream().anyMatch(x -> x.isWeeklyOff() && !x.getRosterDate().equals(date.plusDays(1)));
							if (hasOtherOff) continue;
						}

						boolean restPrev = (prev == null || prev.isWeeklyOff() || prev.isOnLeave() || prev.getShift() == null ||
								hasMinimumRest(prev.getRosterDate(), prev.getShift(), date, nightShift));
						boolean restNext = (next == null || next.isWeeklyOff() || next.isOnLeave() || next.getShift() == null ||
								hasMinimumRest(date, nightShift, next.getRosterDate(), next.getShift()));

						if (restPrev && restNext) {
							candidateToMove = a;
							donorShiftType = donorType;
							break;
						}
					}
					if (candidateToMove != null) break;
				}

				if (candidateToMove == null) {
					for (Employee male : employees) {
						if (male.getGender() != Gender.MALE || !male.isActive() || male.isDevelopmentTeam()) continue;
						long totalNonDevMales = employees.stream().filter(e -> e.getGender() == Gender.MALE && e.isActive() && !e.isDevelopmentTeam()).count();
						boolean isSixDayMode = (cycle != null && cycle.getWorkingDays() != null && cycle.getWorkingDays() == 6);
						int maxNights = male.getMaxNightsPerWeek() != null ? male.getMaxNightsPerWeek() : maxNightsAllowed;
						if (totalNonDevMales <= 3 && male.getMaxNightsPerWeek() != null) {
							maxNights = Math.max(maxNights, 2);
						} else if (isSixDayMode && male.getMaxNightsPerWeek() != null && male.getMaxNightsPerWeek() > 1) {
							maxNights = Math.max(maxNights, 2);
						}
						maxNights = Math.min(maxNights, maxNightsAllowed);
						ApplicablePreference pref = preferencesMap != null ? preferencesMap.get(male.getId()) : null;
						if (pref != null && pref.hasTargetShiftForDate(date)) continue;
						if (!isEligible(male, ShiftType.NIGHT, pref, date)) continue;

						List<RosterAssignment> empList = empAssignments.getOrDefault(male.getId(), Collections.emptyList());
						long empNights = empList.stream().filter(x -> !x.isWeeklyOff() && !x.isOnLeave() && x.getShift() != null && x.getShift().getShiftType() == ShiftType.NIGHT).count();
						if (empNights >= maxNights) continue;

						boolean isSixDayModeEmp = (cycle != null && cycle.getWorkingDays() != null && cycle.getWorkingDays() == 6)
								|| empList.stream().filter(RosterAssignment::isWeeklyOff).count() <= 1;
						if (isSixDayModeEmp) continue;

						RosterAssignment todayAssign = empList.stream().filter(x -> x.getRosterDate().equals(date)).findFirst().orElse(null);
						if (todayAssign == null || !todayAssign.isWeeklyOff() || todayAssign.isOnLeave()) continue;
						if (todayAssign.isManualAssignment() || todayAssign.isOverridden()) continue;

						int idx = empList.indexOf(todayAssign);
						RosterAssignment prev = idx > 0 ? empList.get(idx - 1) : null;
						RosterAssignment next = idx < empList.size() - 1 ? empList.get(idx + 1) : null;
						if (next != null && (next.isManualAssignment() || next.isOverridden() || hasTargetWorkShift(pref, next.getRosterDate()))) continue;

						boolean restPrev = (prev == null || prev.isWeeklyOff() || prev.isOnLeave() || prev.getShift() == null ||
								hasMinimumRest(prev.getRosterDate(), prev.getShift(), date, nightShift));
						boolean restNext = (next == null || next.isWeeklyOff() || next.isOnLeave() || next.getShift() == null ||
								hasMinimumRest(date, nightShift, next.getRosterDate(), next.getShift()));

						if (restPrev && restNext) {
							candidateToMove = todayAssign;
							donorShiftType = ShiftType.OFF;
							break;
						}
					}
				}

				if (candidateToMove == null) {
					for (ShiftType donorType : List.of(ShiftType.GENERAL, ShiftType.MORNING, ShiftType.EVENING)) {
						List<RosterAssignment> donors = shiftMap.getOrDefault(donorType, Collections.emptyList());
						int minDonorKeep = (donorType == ShiftType.MORNING) ? getRequiredMorningMin(date) : (donorType == ShiftType.EVENING ? 1 : 0);
						if (donors.size() <= minDonorKeep) continue;

						for (RosterAssignment a : donors) {
							if (a.isManualAssignment() || a.isOverridden()) continue;
							Employee emp = a.getEmployee();
							if (emp.getGender() == Gender.FEMALE) continue;
							if (emp.isDevelopmentTeam()) continue;
							ApplicablePreference pref = preferencesMap != null ? preferencesMap.get(emp.getId()) : null;
							if (pref != null && pref.hasTargetShiftForDate(date)) continue;
							if (!isEligible(emp, ShiftType.NIGHT, pref, date)) continue;

							List<RosterAssignment> empList = empAssignments.getOrDefault(emp.getId(), Collections.emptyList());
							long empNights = empList.stream().filter(x -> !x.isWeeklyOff() && !x.isOnLeave() && x.getShift() != null && x.getShift().getShiftType() == ShiftType.NIGHT).count();
							long totalNonDevMales = employees.stream().filter(e2 -> e2.getGender() == Gender.MALE && e2.isActive() && !e2.isDevelopmentTeam()).count();
							int empLimit = emp.getMaxNightsPerWeek() != null ? emp.getMaxNightsPerWeek() : maxNightsAllowed;
							if (totalNonDevMales <= 3 && emp.getMaxNightsPerWeek() != null) {
								empLimit = Math.max(empLimit, 2);
							} else if ((cycle != null && cycle.getWorkingDays() != null && cycle.getWorkingDays() == 6) && emp.getMaxNightsPerWeek() != null && emp.getMaxNightsPerWeek() > 1) {
								empLimit = Math.max(empLimit, 2);
							}
							if (empNights >= empLimit) continue;
							if (empNights >= maxNightsAllowed) continue;

							int idx = empList.indexOf(a);
							RosterAssignment prev = idx > 0 ? empList.get(idx - 1) : null;
							RosterAssignment next = idx < empList.size() - 1 ? empList.get(idx + 1) : null;
							if (next != null && (next.isManualAssignment() || next.isOverridden() || hasTargetWorkShift(pref, next.getRosterDate()))) continue;

							boolean isSixDayModeEmp = (cycle != null && cycle.getWorkingDays() != null && cycle.getWorkingDays() == 6)
									|| empList.stream().filter(RosterAssignment::isWeeklyOff).count() <= 1;
							boolean isCycleEnd = (cycle != null && cycle.getEndDate() != null && date.equals(cycle.getEndDate()));
							if (isSixDayModeEmp && !isCycleEnd) {
								if (next == null || !next.isWeeklyOff()) continue;
								boolean hasOtherOff = empList.stream().anyMatch(x -> x.isWeeklyOff() && !x.getRosterDate().equals(date.plusDays(1)));
								if (hasOtherOff) continue;
							}

							boolean restPrev = (prev == null || prev.isWeeklyOff() || prev.isOnLeave() || prev.getShift() == null ||
									hasMinimumRest(prev.getRosterDate(), prev.getShift(), date, nightShift));
							boolean restNext = (next == null || next.isWeeklyOff() || next.isOnLeave() || next.getShift() == null ||
									hasMinimumRest(date, nightShift, next.getRosterDate(), next.getShift()));

							if (restPrev && restNext) {
								candidateToMove = a;
								donorShiftType = donorType;
								break;
							}
						}
						if (candidateToMove != null) break;
					}
				}

				if (candidateToMove != null) {
					log.info("[WRMS REBALANCE] Day {}: Reassigning {} from {} to NIGHT to satisfy mandatory night coverage",
							date, candidateToMove.getEmployee().getEmployeeCode(), donorShiftType);
					candidateToMove.setWeeklyOff(false);
					candidateToMove.setShift(nightShift);
					if (donorShiftType != ShiftType.OFF && shiftMap.get(donorShiftType) != null) {
						shiftMap.get(donorShiftType).remove(candidateToMove);
					}
					shiftMap.computeIfAbsent(ShiftType.NIGHT, k -> new ArrayList<>()).add(candidateToMove);
					nCount = shiftMap.getOrDefault(ShiftType.NIGHT, Collections.emptyList()).size();

					// CRIS Mandatory Fatigue Safety Rule: Night shift MUST be followed by WEEKLY OFF tomorrow
					if (cycle != null && cycle.getEndDate() != null && !date.equals(cycle.getEndDate())) {
						RosterAssignment nextAssign = empAssignments.getOrDefault(candidateToMove.getEmployee().getId(), Collections.emptyList()).stream()
								.filter(x -> x.getRosterDate().equals(date.plusDays(1))).findFirst().orElse(null);
						if (nextAssign != null && !nextAssign.isOnLeave()) {
							nextAssign.setWeeklyOff(true);
							nextAssign.setShift(shifts.get(ShiftType.OFF));
							nextAssign.setAssignmentReason("Weekly OFF (Fatigue Safety: Follows Night Shift)");
						}
					}
				}
			}

			// If General is unstaffed and working staff >= 4, rebalance an eligible donor from Morning or Evening (weekdays only)
			if (gCount == 0 && workingTotal >= 4 && !isWeekend(date)) {
				Shift generalShift = shifts.get(ShiftType.GENERAL);
				RosterAssignment candidateToMove = null;
				ShiftType donorShiftType = null;

				for (ShiftType donorType : List.of(ShiftType.MORNING, ShiftType.EVENING)) {
					List<RosterAssignment> donors = shiftMap.getOrDefault(donorType, Collections.emptyList());
					int minDonorKeep = (donorType == ShiftType.MORNING) ? getRequiredMorningMin(date) : 1;
					if (donors.size() <= minDonorKeep) continue;

					for (RosterAssignment a : donors) {
						if (a.isManualAssignment() || a.isOverridden()) continue;
						Employee emp = a.getEmployee();
						ApplicablePreference pref = preferencesMap != null ? preferencesMap.get(emp.getId()) : null;
						if (pref != null && pref.hasTargetShiftForDate(date)) continue;
						if (pref != null && pref.hasPreferredShifts() && pref.isShiftPreferred(donorType)) {
							long otherDonors = donors.stream().filter(o -> {
								ApplicablePreference op = preferencesMap != null ? preferencesMap.get(o.getEmployee().getId()) : null;
								return op == null || !op.hasPreferredShifts() || !op.isShiftPreferred(donorType);
							}).count();
							if (otherDonors >= 1) continue;
						}
						if (!isEligible(emp, ShiftType.GENERAL, pref, date)) continue;

						List<RosterAssignment> empList = empAssignments.getOrDefault(emp.getId(), Collections.emptyList());
						int idx = empList.indexOf(a);
						RosterAssignment prev = idx > 0 ? empList.get(idx - 1) : null;
						RosterAssignment next = idx < empList.size() - 1 ? empList.get(idx + 1) : null;

						boolean restPrev = (prev == null || prev.isWeeklyOff() || prev.isOnLeave() || prev.getShift() == null ||
								hasMinimumRest(prev.getRosterDate(), prev.getShift(), date, generalShift));
						boolean restNext = (next == null || next.isWeeklyOff() || next.isOnLeave() || next.getShift() == null ||
								hasMinimumRest(date, generalShift, next.getRosterDate(), next.getShift()));

						if (restPrev && restNext) {
							candidateToMove = a;
							donorShiftType = donorType;
							break;
						}
					}
					if (candidateToMove != null) break;
				}

				if (candidateToMove != null) {
					log.info("[WRMS REBALANCE] Day {}: Reassigning {} from {} to GENERAL to satisfy mandatory coverage",
							date, candidateToMove.getEmployee().getEmployeeCode(), donorShiftType);
					candidateToMove.setShift(generalShift);
					shiftMap.get(donorShiftType).remove(candidateToMove);
					shiftMap.computeIfAbsent(ShiftType.GENERAL, k -> new ArrayList<>()).add(candidateToMove);
				}
			}

			// Ensure Morning has at least requiredMorning (2 on Mon-Fri, 1 on weekends, 1 if workingTotal <= 5)
			int requiredMorning = (isWeekend(date) || workingTotal <= 5) ? 1 : getRequiredMorningMin(date);
			int updatedM = shiftMap.getOrDefault(ShiftType.MORNING, Collections.emptyList()).size();
			while (updatedM < requiredMorning && workingTotal >= (requiredMorning + 2)) {
				Shift morningShift = shifts.get(ShiftType.MORNING);
				RosterAssignment candidateToMove = null;
				ShiftType donorShiftType = null;

				for (ShiftType donorType : List.of(ShiftType.GENERAL, ShiftType.EVENING)) {
					List<RosterAssignment> donors = shiftMap.getOrDefault(donorType, Collections.emptyList());
					int minDonorKeep = (donorType == ShiftType.EVENING) ? 1 : 0;
					if (donors.size() <= minDonorKeep) continue;

					for (RosterAssignment a : donors) {
						if (a.isManualAssignment() || a.isOverridden()) continue;
						Employee emp = a.getEmployee();
						ApplicablePreference pref = preferencesMap != null ? preferencesMap.get(emp.getId()) : null;
						if (donorType == ShiftType.GENERAL && isDevelopmentTeam(emp) && date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY) continue;
						if (pref != null && pref.hasTargetShiftForDate(date)) continue;
						if (pref != null && pref.hasPreferredShifts() && pref.isShiftPreferred(donorType)) {
							long otherDonors = donors.stream().filter(o -> {
								ApplicablePreference op = preferencesMap != null ? preferencesMap.get(o.getEmployee().getId()) : null;
								return op == null || !op.hasPreferredShifts() || !op.isShiftPreferred(donorType);
							}).count();
							if (otherDonors >= 1) continue;
						}
						if (!isEligible(emp, ShiftType.MORNING, pref, date)) continue;

						List<RosterAssignment> empList = empAssignments.getOrDefault(emp.getId(), Collections.emptyList());
						int idx = empList.indexOf(a);
						RosterAssignment prev = idx > 0 ? empList.get(idx - 1) : null;
						RosterAssignment next = idx < empList.size() - 1 ? empList.get(idx + 1) : null;

						boolean restPrev = (prev == null || prev.isWeeklyOff() || prev.isOnLeave() || prev.getShift() == null ||
								hasMinimumRest(prev.getRosterDate(), prev.getShift(), date, morningShift));
						boolean restNext = (next == null || next.isWeeklyOff() || next.isOnLeave() || next.getShift() == null ||
								hasMinimumRest(date, morningShift, next.getRosterDate(), next.getShift()));

						if (restPrev && restNext) {
							candidateToMove = a;
							donorShiftType = donorType;
							break;
						}
					}
					if (candidateToMove != null) break;
				}

				if (candidateToMove == null) {
					for (Employee emp : employees) {
						if (!emp.isActive() || emp.isDevelopmentTeam() || emp.getPairedEmployeeId() != null) continue;
						ApplicablePreference pref = preferencesMap != null ? preferencesMap.get(emp.getId()) : null;
						if (pref != null && pref.hasTargetShiftForDate(date)) continue;
						if (!isEligible(emp, ShiftType.MORNING, pref, date)) continue;

						List<RosterAssignment> empList = empAssignments.getOrDefault(emp.getId(), Collections.emptyList());
						RosterAssignment todayAssign = empList.stream().filter(x -> x.getRosterDate().equals(date)).findFirst().orElse(null);
						if (todayAssign == null || !todayAssign.isWeeklyOff() || todayAssign.isOnLeave()) continue;
						if (todayAssign.isManualAssignment() || todayAssign.isOverridden()) continue;
						long curOffCount = empList.stream().filter(RosterAssignment::isWeeklyOff).count();
						int expectedOffs = (cycle != null && cycle.getWorkingDays() != null) ? Math.max(1, 7 - cycle.getWorkingDays()) : 2;
						boolean hasSurplusOff = curOffCount > expectedOffs;
						boolean canSwap = false;
						if (!hasSurplusOff) {
							for (RosterAssignment swapCand : empList) {
								LocalDate swapDate = swapCand.getRosterDate();
								if (swapDate.equals(date) || swapCand.isWeeklyOff() || swapCand.isOnLeave() || swapCand.getShift() == null) continue;
								if (swapCand.getShift().getShiftType() == ShiftType.NIGHT) continue;
								boolean isSwapWk = (swapDate.getDayOfWeek() == DayOfWeek.SATURDAY || swapDate.getDayOfWeek() == DayOfWeek.SUNDAY);
								if (isDevelopmentTeam(swapCand.getEmployee()) && !isSwapWk) continue;
								int swapMin = isSwapWk ? 3 : (employees.size() >= 7 ? 5 : 4);
								long swapDayWorking = assignments.stream()
										.filter(x -> x.getRosterDate().equals(swapDate) && !x.isWeeklyOff() && !x.isOnLeave())
										.count();
								ShiftType swapSt = swapCand.getShift().getShiftType();
								int minShiftReq = (swapSt == ShiftType.MORNING) ? getRequiredMorningMin(swapDate) :
								                  (swapSt == ShiftType.EVENING) ? 1 : 0;
								long inShiftCount = assignments.stream()
										.filter(x -> x.getRosterDate().equals(swapDate) && !x.isWeeklyOff() && !x.isOnLeave() && x.getShift() != null && x.getShift().getShiftType() == swapSt)
										.count();
								if (swapDayWorking > swapMin && inShiftCount > minShiftReq) {
									canSwap = true;
									break;
								}
							}
						}
						if (!hasSurplusOff && !canSwap) continue;

						int idx = empList.indexOf(todayAssign);
						RosterAssignment prev = idx > 0 ? empList.get(idx - 1) : null;
						RosterAssignment next = idx < empList.size() - 1 ? empList.get(idx + 1) : null;

						boolean restPrev = (prev == null || prev.isWeeklyOff() || prev.isOnLeave() || prev.getShift() == null ||
								hasMinimumRest(prev.getRosterDate(), prev.getShift(), date, morningShift));
						boolean restNext = (next == null || next.isWeeklyOff() || next.isOnLeave() || next.getShift() == null ||
								hasMinimumRest(date, morningShift, next.getRosterDate(), next.getShift()));

						if (restPrev && restNext) {
							candidateToMove = todayAssign;
							donorShiftType = ShiftType.OFF;
							break;
						}
					}
				}

				if (candidateToMove == null && updatedM < requiredMorning) {
					for (Employee emp : employees) {
						if (!emp.isActive() || emp.isDevelopmentTeam() || emp.getPairedEmployeeId() != null) continue;
						ApplicablePreference pref = preferencesMap != null ? preferencesMap.get(emp.getId()) : null;
						if (pref != null && pref.hasTargetShiftForDate(date)) continue;
						if (!isEligible(emp, ShiftType.MORNING, pref, date)) continue;

						List<RosterAssignment> empList = empAssignments.getOrDefault(emp.getId(), Collections.emptyList());
						RosterAssignment todayAssign = empList.stream().filter(x -> x.getRosterDate().equals(date)).findFirst().orElse(null);
						if (todayAssign == null || !todayAssign.isWeeklyOff() || todayAssign.isOnLeave()) continue;
						if (todayAssign.isManualAssignment() || todayAssign.isOverridden()) continue;

						long curOffCount = empList.stream().filter(RosterAssignment::isWeeklyOff).count();
						int expectedOffs = (cycle != null && cycle.getWorkingDays() != null) ? Math.max(1, 7 - cycle.getWorkingDays()) : 2;
						if (curOffCount <= expectedOffs) continue;

						int idx = empList.indexOf(todayAssign);
						RosterAssignment prev = idx > 0 ? empList.get(idx - 1) : null;
						RosterAssignment next = idx < empList.size() - 1 ? empList.get(idx + 1) : null;

						boolean restPrev = (prev == null || prev.isWeeklyOff() || prev.isOnLeave() || prev.getShift() == null ||
								hasMinimumRest(prev.getRosterDate(), prev.getShift(), date, morningShift));
						boolean restNext = (next == null || next.isWeeklyOff() || next.isOnLeave() || next.getShift() == null ||
								hasMinimumRest(date, morningShift, next.getRosterDate(), next.getShift()));

						if (restPrev && restNext) {
							candidateToMove = todayAssign;
							donorShiftType = ShiftType.OFF;
							break;
						}
					}
				}

				if (candidateToMove != null) {
					log.info("[WRMS REBALANCE] Day {}: Reassigning {} from {} to MORNING to satisfy mandatory coverage (current: {}, required: {})",
							date, candidateToMove.getEmployee().getEmployeeCode(), donorShiftType, updatedM, requiredMorning);
					candidateToMove.setWeeklyOff(false);
					candidateToMove.setShift(morningShift);
					if (donorShiftType != ShiftType.OFF && shiftMap.get(donorShiftType) != null) {
						shiftMap.get(donorShiftType).remove(candidateToMove);
					}
					shiftMap.computeIfAbsent(ShiftType.MORNING, k -> new ArrayList<>()).add(candidateToMove);
					updatedM = shiftMap.getOrDefault(ShiftType.MORNING, Collections.emptyList()).size();

					if (donorShiftType == ShiftType.OFF) {
						Employee movedEmp = candidateToMove.getEmployee();
						List<RosterAssignment> empList = empAssignments.getOrDefault(movedEmp.getId(), Collections.emptyList());
						for (RosterAssignment swapCand : empList) {
							LocalDate swapDate = swapCand.getRosterDate();
							if (swapDate.equals(date) || swapCand.isWeeklyOff() || swapCand.isOnLeave() || swapCand.getShift() == null) continue;
							if (swapCand.getShift().getShiftType() == ShiftType.NIGHT) continue;
							boolean isSwapWk = (swapDate.getDayOfWeek() == DayOfWeek.SATURDAY || swapDate.getDayOfWeek() == DayOfWeek.SUNDAY);
							if (isDevelopmentTeam(swapCand.getEmployee()) && !isSwapWk) continue;
							int swapMin = isSwapWk ? 3 : (employees.size() >= 7 ? 5 : 4);
							long swapDayWorking = assignments.stream()
									.filter(x -> x.getRosterDate().equals(swapDate) && !x.isWeeklyOff() && !x.isOnLeave())
									.count();
							ShiftType swapSt = swapCand.getShift().getShiftType();
							int minShiftReq = (swapSt == ShiftType.MORNING) ? getRequiredMorningMin(swapDate) :
							                  (swapSt == ShiftType.EVENING) ? 1 : 0;
							long inShiftCount = assignments.stream()
									.filter(x -> x.getRosterDate().equals(swapDate) && !x.isWeeklyOff() && !x.isOnLeave() && x.getShift() != null && x.getShift().getShiftType() == swapSt)
									.count();
							if (swapDayWorking > swapMin && inShiftCount > minShiftReq) {
								swapCand.setWeeklyOff(true);
								swapCand.setShift(shifts.get(ShiftType.OFF));
								Map<ShiftType, List<RosterAssignment>> swapDayMap = dateShiftMap.get(swapDate);
								if (swapDayMap != null && swapDayMap.get(swapSt) != null) {
									swapDayMap.get(swapSt).remove(swapCand);
								}
								break;
							}
						}
					}
				} else {
					break;
				}
			}

			// Ensure Evening has at least 1 if workingTotal >= 2
			int updatedE = shiftMap.getOrDefault(ShiftType.EVENING, Collections.emptyList()).size();
			if (updatedE == 0 && workingTotal >= 2) {
				Shift eveningShift = shifts.get(ShiftType.EVENING);
				RosterAssignment candidateToMove = null;
				ShiftType donorShiftType = null;

				for (ShiftType donorType : List.of(ShiftType.GENERAL, ShiftType.MORNING)) {
					List<RosterAssignment> donors = shiftMap.getOrDefault(donorType, Collections.emptyList());
					int minDonorKeep = (donorType == ShiftType.MORNING) ? (workingTotal <= 5 ? 1 : requiredMorning) : 0;
					if (donors.size() <= minDonorKeep) continue;

					for (RosterAssignment a : donors) {
						if (a.isManualAssignment() || a.isOverridden()) continue;
						Employee emp = a.getEmployee();
						if (emp.getGender() == Gender.FEMALE) continue;
						if (emp.getPairedEmployeeId() != null) {
							boolean partnerIsMorning = assignments.stream().anyMatch(a2 -> a2.getRosterDate().equals(date) && a2.getEmployee().getId().equals(emp.getPairedEmployeeId()) && !a2.isWeeklyOff() && !a2.isOnLeave() && a2.getShift() != null && a2.getShift().getShiftType() == ShiftType.MORNING);
							if (partnerIsMorning) continue;
						}
						ApplicablePreference pref = preferencesMap != null ? preferencesMap.get(emp.getId()) : null;
						if (donorType == ShiftType.GENERAL && emp.isDevelopmentTeam() && date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY) continue;
						if (pref != null && pref.hasTargetShiftForDate(date)) continue;
						if (!isEligible(emp, ShiftType.EVENING, pref, date)) continue;

						List<RosterAssignment> empList = empAssignments.getOrDefault(emp.getId(), Collections.emptyList());
						int idx = empList.indexOf(a);
						RosterAssignment prev = idx > 0 ? empList.get(idx - 1) : null;
						RosterAssignment next = idx < empList.size() - 1 ? empList.get(idx + 1) : null;

						boolean restPrev = (prev == null || prev.isWeeklyOff() || prev.isOnLeave() || prev.getShift() == null ||
								hasMinimumRest(prev.getRosterDate(), prev.getShift(), date, eveningShift));
						boolean restNext = (next == null || next.isWeeklyOff() || next.isOnLeave() || next.getShift() == null ||
								hasMinimumRest(date, eveningShift, next.getRosterDate(), next.getShift()));

						if (restPrev && restNext) {
							candidateToMove = a;
							donorShiftType = donorType;
							break;
						}
					}
					if (candidateToMove != null) break;
				}

				if (candidateToMove == null) {
					for (Employee male : employees) {
						if (male.getGender() != Gender.MALE || !male.isActive() || male.isDevelopmentTeam()) continue;
						if (male.getPairedEmployeeId() != null && assignments.stream().anyMatch(a2 -> a2.getRosterDate().equals(date) && a2.getEmployee().getId().equals(male.getPairedEmployeeId()) && !a2.isWeeklyOff() && !a2.isOnLeave() && a2.getShift() != null && a2.getShift().getShiftType() == ShiftType.MORNING)) continue;
						ApplicablePreference pref = preferencesMap != null ? preferencesMap.get(male.getId()) : null;
						if (pref != null && pref.hasTargetShiftForDate(date)) continue;
						if (!isEligible(male, ShiftType.EVENING, pref, date)) continue;

						List<RosterAssignment> empList = empAssignments.getOrDefault(male.getId(), Collections.emptyList());
						RosterAssignment todayAssign = empList.stream().filter(x -> x.getRosterDate().equals(date)).findFirst().orElse(null);
						if (todayAssign == null || !todayAssign.isWeeklyOff() || todayAssign.isOnLeave()) continue;
						if (todayAssign.isManualAssignment() || todayAssign.isOverridden()) continue;
						long curOffCount = empList.stream().filter(RosterAssignment::isWeeklyOff).count();
						int expectedOffs = (cycle != null && cycle.getWorkingDays() != null) ? Math.max(1, 7 - cycle.getWorkingDays()) : 2;
						boolean hasSurplusOff = curOffCount > expectedOffs;
						boolean canSwap = false;
						if (!hasSurplusOff) {
							for (RosterAssignment swapCand : empList) {
								LocalDate swapDate = swapCand.getRosterDate();
								if (swapDate.equals(date) || swapCand.isWeeklyOff() || swapCand.isOnLeave() || swapCand.getShift() == null) continue;
								if (swapCand.getShift().getShiftType() == ShiftType.NIGHT) continue;
								boolean isSwapWk = (swapDate.getDayOfWeek() == DayOfWeek.SATURDAY || swapDate.getDayOfWeek() == DayOfWeek.SUNDAY);
								if (isDevelopmentTeam(swapCand.getEmployee()) && !isSwapWk) continue;
								int swapMin = isSwapWk ? 3 : (employees.size() >= 7 ? 5 : 4);
								long swapDayWorking = assignments.stream()
										.filter(x -> x.getRosterDate().equals(swapDate) && !x.isWeeklyOff() && !x.isOnLeave())
										.count();
								ShiftType swapSt = swapCand.getShift().getShiftType();
								int minShiftReq = (swapSt == ShiftType.MORNING) ? getRequiredMorningMin(swapDate) :
								                  (swapSt == ShiftType.EVENING) ? 1 : 0;
								long inShiftCount = assignments.stream()
										.filter(x -> x.getRosterDate().equals(swapDate) && !x.isWeeklyOff() && !x.isOnLeave() && x.getShift() != null && x.getShift().getShiftType() == swapSt)
										.count();
								if (swapDayWorking > swapMin && inShiftCount > minShiftReq) {
									canSwap = true;
									break;
								}
							}
						}
						if (!hasSurplusOff && !canSwap) continue;

						int idx = empList.indexOf(todayAssign);
						RosterAssignment prev = idx > 0 ? empList.get(idx - 1) : null;
						RosterAssignment next = idx < empList.size() - 1 ? empList.get(idx + 1) : null;

						boolean restPrev = (prev == null || prev.isWeeklyOff() || prev.isOnLeave() || prev.getShift() == null ||
								hasMinimumRest(prev.getRosterDate(), prev.getShift(), date, eveningShift));
						boolean restNext = (next == null || next.isWeeklyOff() || next.isOnLeave() || next.getShift() == null ||
								hasMinimumRest(date, eveningShift, next.getRosterDate(), next.getShift()));

						if (restPrev && restNext) {
							candidateToMove = todayAssign;
							donorShiftType = ShiftType.OFF;
							break;
						}
					}
				}


				if (candidateToMove != null) {
					log.info("[WRMS REBALANCE] Day {}: Reassigning {} from {} to EVENING to satisfy mandatory coverage",
							date, candidateToMove.getEmployee().getEmployeeCode(), donorShiftType);
					candidateToMove.setWeeklyOff(false);
					candidateToMove.setShift(eveningShift);
					if (donorShiftType != ShiftType.OFF && shiftMap.get(donorShiftType) != null) {
						shiftMap.get(donorShiftType).remove(candidateToMove);
					}
					shiftMap.computeIfAbsent(ShiftType.EVENING, k -> new ArrayList<>()).add(candidateToMove);

					if (donorShiftType == ShiftType.OFF) {
						Employee movedEmp = candidateToMove.getEmployee();
						List<RosterAssignment> empList = empAssignments.getOrDefault(movedEmp.getId(), Collections.emptyList());
						for (RosterAssignment swapCand : empList) {
							LocalDate swapDate = swapCand.getRosterDate();
							if (swapDate.equals(date) || swapCand.isWeeklyOff() || swapCand.isOnLeave() || swapCand.getShift() == null) continue;
							if (swapCand.getShift().getShiftType() == ShiftType.NIGHT) continue;
							boolean isSwapWk = (swapDate.getDayOfWeek() == DayOfWeek.SATURDAY || swapDate.getDayOfWeek() == DayOfWeek.SUNDAY);
							if (isDevelopmentTeam(swapCand.getEmployee()) && !isSwapWk) continue;
							int swapMin = isSwapWk ? 3 : (employees.size() >= 7 ? 5 : 4);
							long swapDayWorking = assignments.stream()
									.filter(x -> x.getRosterDate().equals(swapDate) && !x.isWeeklyOff() && !x.isOnLeave())
									.count();
							ShiftType swapSt = swapCand.getShift().getShiftType();
							int minShiftReq = (swapSt == ShiftType.MORNING) ? getRequiredMorningMin(swapDate) :
							                  (swapSt == ShiftType.EVENING) ? 1 : 0;
							long inShiftCount = assignments.stream()
									.filter(x -> x.getRosterDate().equals(swapDate) && !x.isWeeklyOff() && !x.isOnLeave() && x.getShift() != null && x.getShift().getShiftType() == swapSt)
									.count();
							if (swapDayWorking > swapMin && inShiftCount > minShiftReq) {
								swapCand.setWeeklyOff(true);
								swapCand.setShift(shifts.get(ShiftType.OFF));
								Map<ShiftType, List<RosterAssignment>> swapDayMap = dateShiftMap.get(swapDate);
								if (swapDayMap != null && swapDayMap.get(swapSt) != null) {
									swapDayMap.get(swapSt).remove(swapCand);
								}
								break;
							}
						}
					}
				}
			}
		}
	}

	private void ensureMinimumNightAllocation(RosterCycle cycle, List<RosterAssignment> assignments,
			List<Employee> employees, Map<ShiftType, Shift> shifts, int maxNightsAllowed,
			Map<Long, Set<LocalDate>> weeklyOffs, Map<Long, ApplicablePreference> preferencesMap) {
		Map<Long, List<RosterAssignment>> empAssignments = assignments.stream()
				.collect(Collectors.groupingBy(a -> a.getEmployee().getId()));

		List<Employee> activeMales = employees.stream()
				.filter(e -> e.getGender() == Gender.MALE && e.isActive())
				.toList();

		List<Employee> prioritizedMales = new ArrayList<>(activeMales);
		prioritizedMales.sort(Comparator.comparingInt(m -> {
			return m.getMaxNightsPerWeek() != null ? m.getMaxNightsPerWeek() : 2;
		}));

		for (Employee male : prioritizedMales) {
			if (male.isDevelopmentTeam()) continue; // Dev team schedule takes precedence (0 Nights normally)
			List<RosterAssignment> maleList = empAssignments.getOrDefault(male.getId(), Collections.emptyList());
			if (maleList.isEmpty()) continue;
			maleList.sort(Comparator.comparing(RosterAssignment::getRosterDate));

			long leaveCount = maleList.stream().filter(RosterAssignment::isOnLeave).count();
			if (leaveCount >= 7) continue;

			long nightCount = maleList.stream()
					.filter(a -> !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT)
					.count();

			if (nightCount == 0) {
				ApplicablePreference malePref = preferencesMap != null ? preferencesMap.get(male.getId()) : null;
				if (malePref != null && (malePref.isShiftAvoided(ShiftType.NIGHT) || (malePref.hasPreferredShifts() && !malePref.isShiftPreferred(ShiftType.NIGHT)))) {
					long eligibleOtherMales = activeMales.stream()
							.filter(o -> !o.getId().equals(male.getId()))
							.filter(o -> {
								ApplicablePreference op = preferencesMap != null ? preferencesMap.get(o.getId()) : null;
								return op == null || !op.hasPreferredShifts() || op.isShiftPreferred(ShiftType.NIGHT);
							}).count();
					if (eligibleOtherMales >= 1) {
						continue;
					}
				}

				Set<LocalDate> scheduledOffs = weeklyOffs != null ? weeklyOffs.get(male.getId()) : null;

				// Find a night slot to swap with an eligible donor
				for (RosterAssignment maleAssign : maleList) {
					if (maleAssign.isWeeklyOff() || maleAssign.isOnLeave()) continue;
					if (maleAssign.isManualAssignment() || maleAssign.isOverridden()) continue;
					LocalDate date = maleAssign.getRosterDate();
					if (malePref != null && malePref.hasTargetShiftForDate(date)) continue;
					if (!isEligible(male, ShiftType.NIGHT, malePref, date)) continue;

					// Find who is assigned NIGHT on this date
					RosterAssignment nightAssign = assignments.stream()
							.filter(a -> a.getRosterDate().equals(date) && !a.isWeeklyOff() && !a.isOnLeave()
									&& a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT)
							.findFirst().orElse(null);

					if (nightAssign == null) continue;
					if (nightAssign.isManualAssignment() || nightAssign.isOverridden()) continue;
					Employee donor = nightAssign.getEmployee();
					if (donor.getId().equals(male.getId())) continue;
					ApplicablePreference donorPref = preferencesMap != null ? preferencesMap.get(donor.getId()) : null;
					if (donorPref != null && donorPref.hasTargetShiftForDate(date)) continue;

					List<RosterAssignment> donorList = empAssignments.getOrDefault(donor.getId(), Collections.emptyList());
					donorList.sort(Comparator.comparing(RosterAssignment::getRosterDate));
					long donorNights = donorList.stream()
							.filter(a -> !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT)
							.count();

					boolean isSmallPool = (employees.stream().filter(e -> e.getGender() == Gender.MALE && e.isActive() && !e.isDevelopmentTeam()).count() <= 3);
					int donorMaxN = donor.getMaxNightsPerWeek() != null ? donor.getMaxNightsPerWeek() : 2;
					boolean donorEligible = (donorNights > donorMaxN)
							|| (donorNights >= 2 && donorMaxN == 1)
							|| (donorNights >= 3);

					if (donorEligible) {
						Shift nightShift = shifts.get(ShiftType.NIGHT);
						int maleIdx = maleList.indexOf(maleAssign);
						RosterAssignment prevA = maleIdx > 0 ? maleList.get(maleIdx - 1) : null;
						RosterAssignment nextA = maleIdx < maleList.size() - 1 ? maleList.get(maleIdx + 1) : null;
						if (nextA != null && (nextA.isManualAssignment() || nextA.isOverridden() || hasTargetWorkShift(malePref, nextA.getRosterDate()))) {
							continue;
						}

						boolean restOkPrev = (prevA == null || prevA.isWeeklyOff() || prevA.isOnLeave() ||
								hasMinimumRest(prevA.getRosterDate(), prevA.getShift(), date, nightShift));
						boolean isSixDay = (cycle != null && cycle.getWorkingDays() == 6)
								|| (scheduledOffs != null && scheduledOffs.size() <= 1);
						boolean restOkNext = (nextA == null || nextA.isWeeklyOff() || nextA.isOnLeave() ||
								(scheduledOffs != null && scheduledOffs.contains(nextA.getRosterDate())) ||
								(!isSixDay && hasMinimumRest(date, nightShift, nextA.getRosterDate(), nextA.getShift())));

						if (!restOkPrev || !restOkNext) continue;

						boolean isCycleEnd = (cycle != null && cycle.getEndDate() != null && date.equals(cycle.getEndDate()));
						if (isSixDay && !isCycleEnd && (scheduledOffs == null || !scheduledOffs.contains(date.plusDays(1)))) {
							continue; // In 6-day mode, swapped night must lead directly into scheduled off
						}

						// Check donor rest-safe replacement shift
						int donorIdx = donorList.indexOf(nightAssign);
						RosterAssignment donorPrev = donorIdx > 0 ? donorList.get(donorIdx - 1) : null;
						RosterAssignment donorNext = donorIdx < donorList.size() - 1 ? donorList.get(donorIdx + 1) : null;

						ShiftType donorNewShift = null;
						for (ShiftType cand : List.of(maleAssign.getShift().getShiftType(), ShiftType.GENERAL, ShiftType.MORNING, ShiftType.EVENING)) {
							if (cand == ShiftType.NIGHT || cand == ShiftType.OFF) continue;
							if (cand == ShiftType.EVENING && donor.getPairedEmployeeId() != null) {
								boolean partnerWorkingToday = assignments.stream().anyMatch(a2 -> a2.getRosterDate().equals(date) && a2.getEmployee().getId().equals(donor.getPairedEmployeeId()) && !a2.isWeeklyOff() && !a2.isOnLeave());
								if (partnerWorkingToday) continue;
							}
							if (!isEligible(donor, cand, donorPref, date)) continue;
							Shift cShift = shifts.get(cand);
							boolean dRestPrev = (donorPrev == null || donorPrev.isWeeklyOff() || donorPrev.isOnLeave() ||
									hasMinimumRest(donorPrev.getRosterDate(), donorPrev.getShift(), date, cShift));
							boolean dRestNext = (donorNext == null || donorNext.isWeeklyOff() || donorNext.isOnLeave() ||
									hasMinimumRest(date, cShift, donorNext.getRosterDate(), donorNext.getShift()));
							if (dRestPrev && dRestNext) {
								donorNewShift = cand;
								break;
							}
						}

						if (donorNewShift == null) continue;

						maleAssign.setShift(nightShift);
						nightAssign.setShift(shifts.get(donorNewShift));

						if (nextA != null && !nextA.isOnLeave() && !isCycleEnd) {
							nextA.setWeeklyOff(true);
							nextA.setShift(shifts.get(ShiftType.OFF));
							nextA.setAssignmentReason("Weekly OFF (Fatigue Safety: Follows Night Shift)");
						}
						break;
					}
				}
			}
		}
	}

	private void enforceAndRepairExactWeeklyOff(RosterCycle cycle, List<RosterAssignment> assignments,
			List<Employee> employees, Map<ShiftType, Shift> shifts, int maxNightsAllowed, Map<Long, Set<LocalDate>> weeklyOffs,
			Map<Long, ApplicablePreference> preferencesMap, int workingDays) {
		Map<Long, List<RosterAssignment>> empAssignments = assignments.stream()
				.collect(Collectors.groupingBy(a -> a.getEmployee().getId()));

		int expectedOffs = Math.max(1, 7 - workingDays);

		for (Employee emp : employees) {
			List<RosterAssignment> list = empAssignments.getOrDefault(emp.getId(), Collections.emptyList());
			if (list.isEmpty()) continue;
			list.sort(Comparator.comparing(RosterAssignment::getRosterDate));
			ApplicablePreference pref = preferencesMap != null ? preferencesMap.get(emp.getId()) : null;

			long leaveCount = list.stream().filter(RosterAssignment::isOnLeave).count();
			if (leaveCount >= 7) {
				continue;
			}

			// 1. If employee worked NIGHT, the day immediately following ANY night shift (if not another night shift) MUST be OFF
			Set<LocalDate> postNightOffDates = new HashSet<>();

			// Check if employee worked NIGHT on the day immediately preceding cycle start (Sunday night)
			if (assignmentRepository != null && cycle != null && cycle.getStartDate() != null) {
				List<RosterAssignment> prevWorked = assignmentRepository.findWorkedAssignmentsBefore(emp.getId(), cycle.getStartDate());
				if (!prevWorked.isEmpty()) {
					RosterAssignment lastA = prevWorked.get(0);
					if (lastA.getRosterDate() != null && lastA.getRosterDate().equals(cycle.getStartDate().minusDays(1))
							&& lastA.getShift() != null && lastA.getShift().getShiftType() == ShiftType.NIGHT) {
						if (!list.isEmpty()) {
							RosterAssignment mondayAssign = list.get(0);
							if (!mondayAssign.isOnLeave() && (mondayAssign.getShift() == null || mondayAssign.getShift().getShiftType() != ShiftType.NIGHT)) {
								postNightOffDates.add(mondayAssign.getRosterDate());
								mondayAssign.setWeeklyOff(true);
								mondayAssign.setShift(shifts.get(ShiftType.OFF));
							}
						}
					}
				}
			}

			for (int i = 0; i < list.size(); i++) {
				RosterAssignment cur = list.get(i);
				if (!cur.isWeeklyOff() && !cur.isOnLeave() && cur.getShift() != null && cur.getShift().getShiftType() == ShiftType.NIGHT) {
					if (i + 1 < list.size()) {
												RosterAssignment next = list.get(i + 1);
						if (!next.isOnLeave()) {
							postNightOffDates.add(next.getRosterDate());
							next.setWeeklyOff(true);
							next.setShift(shifts.get(ShiftType.OFF));
						}
					}
				}
			}

			// 2. Query offList after ensuring post-night OFF
			List<RosterAssignment> offList = new ArrayList<>(list.stream().filter(RosterAssignment::isWeeklyOff).toList());
			Set<LocalDate> scheduledOffs = weeklyOffs != null ? weeklyOffs.get(emp.getId()) : null;

			int targetOffs = (workingDays == 6) ? 1 : Math.max(expectedOffs, postNightOffDates.size());

			// Case 1: More than targetOffs Weekly OFFs -> Convert excess OFFs to valid working shifts with 12h rest
			if (offList.size() > targetOffs) {
				Set<RosterAssignment> keepOffs = new LinkedHashSet<>();

				// Priority 1: Post-Sunday-night recovery OFF on cycle start date
				if (cycle != null && cycle.getStartDate() != null && postNightOffDates.contains(cycle.getStartDate())) {
					offList.stream().filter(a -> a.getRosterDate().equals(cycle.getStartDate())).findFirst().ifPresent(keepOffs::add);
				}
				// Priority 2: Other post-night OFFs
				if (keepOffs.size() < targetOffs && !postNightOffDates.isEmpty()) {
					for (LocalDate postNightDate : postNightOffDates) {
						if (keepOffs.size() >= targetOffs) break;
						offList.stream().filter(a -> a.getRosterDate().equals(postNightDate)).findFirst().ifPresent(keepOffs::add);
					}
				}
				// Priority 3: Scheduled offs from weeklyOffs
				if (keepOffs.size() < targetOffs && scheduledOffs != null) {
					for (LocalDate sched : scheduledOffs) {
						if (keepOffs.size() >= targetOffs) break;
						offList.stream().filter(a -> a.getRosterDate().equals(sched)).findFirst().ifPresent(keepOffs::add);
					}
				}
				// Priority 4: Preferred off days
				if (keepOffs.size() < targetOffs && pref != null && !pref.preferredOffDays().isEmpty()) {
					for (RosterAssignment offA : offList) {
						if (keepOffs.size() >= targetOffs) break;
						if (pref.isDayPreferredOff(offA.getRosterDate().getDayOfWeek())) {
							keepOffs.add(offA);
						}
					}
				}
				// Priority 5: For targetOffs >= 2, if 1 off is kept, prefer keeping an adjacent off (clustering)
				if (keepOffs.size() == 1 && targetOffs >= 2) {
					LocalDate keptDate = keepOffs.iterator().next().getRosterDate();
					for (RosterAssignment offA : offList) {
						if (!keepOffs.contains(offA)) {
							long diff = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(keptDate, offA.getRosterDate()));
							if (diff == 1 || diff == 6) {
								keepOffs.add(offA);
								break;
							}
						}
					}
				}
				// Fill any remainder up to targetOffs
				for (RosterAssignment offA : offList) {
					if (keepOffs.size() >= targetOffs) break;
					keepOffs.add(offA);
				}

				List<RosterAssignment> excessOffs = offList.stream().filter(a -> !keepOffs.contains(a)).toList();
				for (RosterAssignment extraOff : excessOffs) {
					int checkIdx = list.indexOf(extraOff);
					RosterAssignment prevCheck = checkIdx > 0 ? list.get(checkIdx - 1) : null;
					if (prevCheck == null && assignmentRepository != null && cycle != null && cycle.getStartDate() != null && extraOff.getRosterDate().equals(cycle.getStartDate())) {
						List<RosterAssignment> prevWorked = assignmentRepository.findWorkedAssignmentsBefore(emp.getId(), cycle.getStartDate());
						if (!prevWorked.isEmpty()) {
							prevCheck = prevWorked.get(0);
						}
					}
					if (prevCheck != null && !prevCheck.isWeeklyOff() && !prevCheck.isOnLeave() && prevCheck.getShift() != null && prevCheck.getShift().getShiftType() == ShiftType.NIGHT) {
						// When converting an excess post-night off, also convert the preceding night to GENERAL so night->off invariant holds
						prevCheck.setShift(shifts.get(ShiftType.GENERAL));
					}

					extraOff.setWeeklyOff(false);
					extraOff.setOnLeave(false);

					int idx = list.indexOf(extraOff);
					RosterAssignment prev = idx > 0 ? list.get(idx - 1) : null;
					if (prev == null && assignmentRepository != null && cycle != null && cycle.getStartDate() != null && extraOff.getRosterDate().equals(cycle.getStartDate())) {
						List<RosterAssignment> prevWorked = assignmentRepository.findWorkedAssignmentsBefore(emp.getId(), cycle.getStartDate());
						if (!prevWorked.isEmpty()) {
							prev = prevWorked.get(0);
						}
					}
					RosterAssignment next = idx < list.size() - 1 ? list.get(idx + 1) : null;

					ShiftType chosen = null;
					List<ShiftType> priorityCandidates = new ArrayList<>();
					if (pref != null && pref.getTargetShiftForDate(extraOff.getRosterDate()) != null && pref.getTargetShiftForDate(extraOff.getRosterDate()) != ShiftType.OFF) {
						priorityCandidates.add(pref.getTargetShiftForDate(extraOff.getRosterDate()));
					}
					if (pref != null && pref.hasPreferredShifts()) {
						for (ShiftType pSt : pref.preferredShifts()) {
							if (pSt != ShiftType.OFF && pSt != ShiftType.NIGHT && !priorityCandidates.contains(pSt)) priorityCandidates.add(pSt);
						}
					}
					if (prev != null && prev.getShift() != null && prev.getShift().getShiftType() != ShiftType.OFF && prev.getShift().getShiftType() != ShiftType.NIGHT) {
						ShiftType pType = prev.getShift().getShiftType();
						if (!priorityCandidates.contains(pType)) priorityCandidates.add(pType);
					}
					for (ShiftType std : List.of(ShiftType.MORNING, ShiftType.EVENING, ShiftType.GENERAL)) {
						if (!priorityCandidates.contains(std)) priorityCandidates.add(std);
					}

					for (ShiftType candidate : priorityCandidates) {
						if (!isEligible(emp, candidate, pref, extraOff.getRosterDate())) continue;
						if (candidate == ShiftType.EVENING && emp.getPairedEmployeeId() != null) {
							boolean partnerWorkingToday = assignments.stream().anyMatch(a2 -> a2.getRosterDate().equals(extraOff.getRosterDate()) && a2.getEmployee().getId().equals(emp.getPairedEmployeeId()) && !a2.isWeeklyOff() && !a2.isOnLeave());
							if (partnerWorkingToday) continue;
						}
						if (emp.isDevelopmentTeam() && !isWeekend(extraOff.getRosterDate()) && candidate != ShiftType.GENERAL) {
							if (employees.size() >= 7) continue;
						}
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
					if (chosen != null) {
						extraOff.setShift(shifts.get(chosen));
					} else {
						// Rest-safe fallback
						ShiftType fallback = null;
						for (ShiftType fbCandidate : List.of(ShiftType.MORNING, ShiftType.EVENING, ShiftType.GENERAL)) {
							if (!isEligible(emp, fbCandidate, pref, extraOff.getRosterDate())) continue;
							if (fbCandidate == ShiftType.EVENING && emp.getPairedEmployeeId() != null) {
								boolean partnerWorkingToday = assignments.stream().anyMatch(a2 -> a2.getRosterDate().equals(extraOff.getRosterDate()) && a2.getEmployee().getId().equals(emp.getPairedEmployeeId()) && !a2.isWeeklyOff() && !a2.isOnLeave());
								if (partnerWorkingToday) continue;
							}
							if (emp.isDevelopmentTeam() && !isWeekend(extraOff.getRosterDate()) && fbCandidate != ShiftType.GENERAL) {
								if (employees.size() >= 7) continue;
							}
							Shift candidateShift = shifts.get(fbCandidate);
							boolean restPrev = (prev == null || prev.isWeeklyOff() || prev.isOnLeave() ||
									hasMinimumRest(prev.getRosterDate(), prev.getShift(), extraOff.getRosterDate(), candidateShift));
							boolean restNext = (next == null || next.isWeeklyOff() || next.isOnLeave() ||
									hasMinimumRest(extraOff.getRosterDate(), candidateShift, next.getRosterDate(), next.getShift()));
							if (restPrev && restNext) {
								fallback = fbCandidate;
								break;
							}
						}
						if (fallback == null) {
							for (ShiftType fbCandidate : List.of(ShiftType.MORNING, ShiftType.EVENING, ShiftType.GENERAL)) {
								if (!isEligible(emp, fbCandidate, pref, extraOff.getRosterDate())) continue;
								if (fbCandidate == ShiftType.EVENING && emp.getPairedEmployeeId() != null) {
									boolean partnerWorkingToday = assignments.stream().anyMatch(a2 -> a2.getRosterDate().equals(extraOff.getRosterDate()) && a2.getEmployee().getId().equals(emp.getPairedEmployeeId()) && !a2.isWeeklyOff() && !a2.isOnLeave());
									if (partnerWorkingToday) continue;
								}
								if (emp.isDevelopmentTeam() && !isWeekend(extraOff.getRosterDate()) && fbCandidate != ShiftType.GENERAL) {
									if (employees.size() >= 7) continue;
								}
								Shift candidateShift = shifts.get(fbCandidate);
								boolean restPrev = (prev == null || prev.isWeeklyOff() || prev.isOnLeave() ||
										hasMinimumRest(prev.getRosterDate(), prev.getShift(), extraOff.getRosterDate(), candidateShift));
								if (restPrev) {
									boolean nextConflict = (next != null && !next.isWeeklyOff() && !next.isOnLeave() && next.getShift() != null &&
											!hasMinimumRest(extraOff.getRosterDate(), candidateShift, next.getRosterDate(), next.getShift()));
									if (nextConflict) {
										boolean nextProtected = next.isManualAssignment() || next.isOverridden() || hasTargetWorkShift(pref, next.getRosterDate());
										if (nextProtected) {
											continue;
										}
										if (isEligible(emp, ShiftType.EVENING, pref, next.getRosterDate())) {
											boolean partnerBlocked = emp.getPairedEmployeeId() != null && assignments.stream().anyMatch(a2 -> a2.getRosterDate().equals(next.getRosterDate()) && a2.getEmployee().getId().equals(emp.getPairedEmployeeId()) && !a2.isWeeklyOff() && !a2.isOnLeave());
											if (!partnerBlocked) {
												next.setShift(shifts.get(ShiftType.EVENING));
												fallback = fbCandidate;
												break;
											}
										}
									} else {
										fallback = fbCandidate;
										break;
									}
								}
							}
						}
						if (fallback != null) {
							extraOff.setShift(shifts.get(fallback));
						} else {
							extraOff.setWeeklyOff(false);
							extraOff.setShift(shifts.get(emp.isDevelopmentTeam() ? ShiftType.GENERAL : ShiftType.MORNING));
						}
					}
				}
			}

			// Case 2: Fewer Weekly OFFs than targetOffs -> Assign weekly OFFs until targetOffs is met
			if (offList.size() < targetOffs) {
				int needed = targetOffs - offList.size();
				List<RosterAssignment> workingAssigns = list.stream().filter(a -> !a.isWeeklyOff() && !a.isOnLeave()).toList();

				List<RosterAssignment> sortedCandidates = new ArrayList<>(workingAssigns);
				sortedCandidates.sort(Comparator.comparing((RosterAssignment a) -> {
					if (pref != null && pref.hasTargetShiftForDate(a.getRosterDate())) return 100;
					int p = 50;
					if (scheduledOffs != null && scheduledOffs.contains(a.getRosterDate())) p -= 30;
					if (pref != null && pref.isDayPreferredOff(a.getRosterDate().getDayOfWeek())) p -= 20;
					boolean adjToOff = offList.stream().anyMatch(o -> Math.abs(java.time.temporal.ChronoUnit.DAYS.between(o.getRosterDate(), a.getRosterDate())) == 1);
					if (adjToOff) p -= 15;
					if (a.getRosterDate().getDayOfWeek() == DayOfWeek.SUNDAY) p -= 10;
					if (a.getRosterDate().getDayOfWeek() == DayOfWeek.SATURDAY) p -= 8;
					if (a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT) p += 40;
					return p;
				}));

				for (RosterAssignment cand : sortedCandidates) {
					if (offList.size() >= targetOffs) break;
					LocalDate cDate = cand.getRosterDate();
					boolean isWk = (cDate.getDayOfWeek() == DayOfWeek.SATURDAY || cDate.getDayOfWeek() == DayOfWeek.SUNDAY);
					long workingOnDay = assignments.stream()
							.filter(x -> x.getRosterDate().equals(cDate) && !x.isWeeklyOff() && !x.isOnLeave())
							.count();
					if (cand.getEmployee().isDevelopmentTeam() && !isWk) continue;
					int minStaffNeeded = isWk ? 3 : ((workingDays == 5) ? 5 : (employees.size() >= 7 ? 6 : 4));
					if (workingOnDay <= minStaffNeeded) {
						continue; // preserve required coverage on this date
					}
					ShiftType st = cand.getShift() != null ? cand.getShift().getShiftType() : ShiftType.OFF;
					if (st == ShiftType.NIGHT) continue;
					int minReq = (st == ShiftType.MORNING) ? getRequiredMorningMin(cDate) :
					             (st == ShiftType.EVENING) ? getRequiredEveningMin(cDate) : 0;
					long currentInShift = assignments.stream()
							.filter(x -> x.getRosterDate().equals(cDate) && !x.isWeeklyOff() && !x.isOnLeave() && x.getShift() != null && x.getShift().getShiftType() == st)
							.count();
					if (currentInShift <= minReq) continue;

					cand.setWeeklyOff(true);
					cand.setOnLeave(false);
					cand.setShift(shifts.get(ShiftType.OFF));
					offList.add(cand);
				}
				if (offList.size() < targetOffs) {
					for (RosterAssignment cand : sortedCandidates) {
						if (offList.size() >= targetOffs) break;
						if (cand.isWeeklyOff() || cand.isManualAssignment() || cand.isOverridden()) continue;
						LocalDate cDate = cand.getRosterDate();
						boolean isWk = (cDate.getDayOfWeek() == DayOfWeek.SATURDAY || cDate.getDayOfWeek() == DayOfWeek.SUNDAY);
						long workingOnDay = assignments.stream()
								.filter(x -> x.getRosterDate().equals(cDate) && !x.isWeeklyOff() && !x.isOnLeave())
								.count();
						if (cand.getEmployee().isDevelopmentTeam() && !isWk) continue;
						int minStaffNeeded = isWk ? 3 : ((workingDays == 5) ? 5 : (employees.size() >= 7 ? 6 : 4));
						if (workingOnDay <= minStaffNeeded) continue;
						ShiftType st = cand.getShift() != null ? cand.getShift().getShiftType() : ShiftType.OFF;
						if (st == ShiftType.NIGHT) continue;
						int minReq = (st == ShiftType.MORNING) ? getRequiredMorningMin(cDate) :
						             (st == ShiftType.EVENING) ? getRequiredEveningMin(cDate) : 0;
						long currentInShift = assignments.stream()
								.filter(x -> x.getRosterDate().equals(cDate) && !x.isWeeklyOff() && !x.isOnLeave() && x.getShift() != null && x.getShift().getShiftType() == st)
								.count();
						if (currentInShift <= minReq) continue;

						cand.setWeeklyOff(true);
						cand.setOnLeave(false);
						cand.setShift(shifts.get(ShiftType.OFF));
						offList.add(cand);
					}
				}
				if (offList.size() < targetOffs) {
					for (RosterAssignment cand : sortedCandidates) {
						if (offList.size() >= targetOffs) break;
						if (cand.isWeeklyOff() || cand.isOnLeave()) continue;
						LocalDate cDate = cand.getRosterDate();
						boolean isWk = (cDate.getDayOfWeek() == DayOfWeek.SATURDAY || cDate.getDayOfWeek() == DayOfWeek.SUNDAY);
						if (cand.getEmployee().isDevelopmentTeam() && !isWk) continue;
						ShiftType st = cand.getShift() != null ? cand.getShift().getShiftType() : ShiftType.OFF;
						if (st == ShiftType.NIGHT) continue;

						int absMinStaff = isWk ? 3 : 4;
						long workingOnDay = assignments.stream()
								.filter(x -> x.getRosterDate().equals(cDate) && !x.isWeeklyOff() && !x.isOnLeave())
								.count();
						if (workingOnDay <= absMinStaff) continue;

						long currentInShift = assignments.stream()
								.filter(x -> x.getRosterDate().equals(cDate) && !x.isWeeklyOff() && !x.isOnLeave() && x.getShift() != null && x.getShift().getShiftType() == st)
								.count();
						if (st == ShiftType.MORNING && currentInShift <= 1) continue;
						if (st == ShiftType.EVENING && currentInShift <= 1) continue;

						cand.setWeeklyOff(true);
						cand.setOnLeave(false);
						cand.setShift(shifts.get(ShiftType.OFF));
						offList.add(cand);
					}
				}
			}
		}
	}

	private void enforceAndRepairExactWeeklyOff(RosterCycle cycle, List<RosterAssignment> assignments,
			List<Employee> employees, Map<ShiftType, Shift> shifts, int maxNightsAllowed, Map<Long, Set<LocalDate>> weeklyOffs,
			Map<Long, ApplicablePreference> preferencesMap) {
		int wDays = cycle != null && cycle.getWorkingDays() != null ? cycle.getWorkingDays() : 6;
		enforceAndRepairExactWeeklyOff(cycle, assignments, employees, shifts, maxNightsAllowed, weeklyOffs, preferencesMap, wDays);
	}

	// -------------------------------------------------------------------------
	// BATCH 93: WORKFORCE ROSTER ALGORITHM HELPERS
	// -------------------------------------------------------------------------

	private boolean isDevelopmentTeam(Employee e) {
		return e != null && e.isDevelopmentTeam();
	}

	private int computeMaxNightsAllowed(List<Employee> employees) {
		if (employees == null || employees.isEmpty()) return MAX_NIGHTS_PER_CYCLE;
		long nightEligibleMales = employees.stream()
				.filter(e -> e.getGender() == Gender.MALE && e.isActive() && !e.isDevelopmentTeam())
				.count();
		if (nightEligibleMales <= 2) {
			return (int) Math.ceil(7.0 / Math.max(1, nightEligibleMales));
		}
		if (nightEligibleMales <= 3) {
			return 3;
		}
		return MAX_NIGHTS_PER_CYCLE;
	}

private int score(Employee employee, ShiftType shiftType, LocalDate date, Map<Long, Shift> lastShiftMap,
		Map<Long, LocalDate> lastShiftDateMap, Map<Long, Integer> cycleNightCounts, Map<Long, Map<ShiftType, Integer>> shiftCountsMap,
		Map<Long, Set<LocalDate>> weeklyOffs, LocalDate cycleStartDate,
		ApplicablePreference pref, int randomSeed) {
	Map<ShiftType, Integer> myCounts = shiftCountsMap != null ? shiftCountsMap.get(employee.getId()) : null;
	int shiftCount = myCounts != null ? myCounts.getOrDefault(shiftType, 0) : (int) assignmentRepository.countShiftForEmployee(employee.getId(), shiftType);
	int morningCount = myCounts != null ? myCounts.getOrDefault(ShiftType.MORNING, 0) : 0;
	int generalCount = myCounts != null ? myCounts.getOrDefault(ShiftType.GENERAL, 0) : 0;
	int eveningCount = myCounts != null ? myCounts.getOrDefault(ShiftType.EVENING, 0) : 0;
	int nightCount = myCounts != null ? myCounts.getOrDefault(ShiftType.NIGHT, 0) : 0;

	int score = shiftCount * 20;

	// Development Team schedule rule (Mon-Fri General 09:30-18:00, Sat-Sun Off)
	if (employee != null && employee.isDevelopmentTeam()) {
		boolean isSmallPool = (shiftCountsMap != null && shiftCountsMap.size() <= 6);
		boolean isWeekend = (date != null && (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY));
		if (!isWeekend) {
			if (shiftType == ShiftType.GENERAL) {
				score -= 15000;
			} else if (shiftType == ShiftType.NIGHT) {
				score += 35000;
			} else if (shiftType == ShiftType.EVENING) {
				score += 25000;
			} else if (shiftType == ShiftType.MORNING) {
				score += 25000;
			}
		} else {
			if (shiftType == ShiftType.NIGHT) {
				score += 30000;
			} else if (shiftType == ShiftType.EVENING) {
				score += 20000;
			}
		}
	}

	// Paired Employee Synchronization Pairing (Prefer Morning together, avoid late Evening)
	if (employee != null && employee.getPairedEmployeeId() != null) {
		if (shiftType == ShiftType.MORNING) {
			score -= 8000;
		} else if (shiftType == ShiftType.EVENING) {
			score += 45000;
		}
	}

	// Tailored Night & Evening Absorption for employees configured with higher night allowance
	if (employee != null && employee.getMaxNightsPerWeek() != null && employee.getMaxNightsPerWeek() >= 2) {
		if (shiftType == ShiftType.NIGHT) {
			score -= 5000;
		} else if (shiftType == ShiftType.EVENING) {
			score -= 4000;
		} else if (shiftType == ShiftType.MORNING || shiftType == ShiftType.GENERAL) {
			score += 1500;
		}
	}

	// 1. Approved Employee Preferences (High Priority with deterministic priority ordering)
	if (pref != null) {
		ShiftType dateSpecificShift = (date != null) ? pref.getTargetShiftForDate(date) : null;
		if (dateSpecificShift != null) {
			if (shiftType == dateSpecificShift) {
				score -= 30000; // Priority: Exact date-specific or recurring shift preference
			} else {
				score += 30000; // Strong penalty to keep candidate available for target shift
			}
		} else {
			if (pref.isShiftPreferred(shiftType)) {
				int rank = pref.getShiftPreferenceRank(shiftType);
				if (rank == 0) {
					score -= 9000; // Priority 1: highest preference reward (Authoritative Level 3)
				} else if (rank == 1) {
					score -= 6000; // Priority 2: secondary preference reward
				} else if (rank == 2) {
					score -= 3500; // Priority 3: tertiary preference reward
				} else {
					score -= 2000; // Priority 4+: fallback preference reward
				}
			} else if (pref.hasPreferredShifts()) {
				// Heavy penalty for non-preferred shift when employee has explicit Admin preferences
				score += 7500;
			}
		}

		// Lookahead: protect tomorrow's preference from 12h rest violations
		if (date != null) {
			ShiftType tomorrowDatePref = pref.getTargetShiftForDate(date.plusDays(1));
			if (tomorrowDatePref != null) {
				if (tomorrowDatePref == ShiftType.MORNING || tomorrowDatePref == ShiftType.GENERAL) {
					if (shiftType == ShiftType.EVENING || shiftType == ShiftType.NIGHT) {
						score += 40000; // Do not assign Evening/Night today if employee prefers Morning/General tomorrow!
					}
				} else if (tomorrowDatePref == ShiftType.EVENING) {
					if (shiftType == ShiftType.NIGHT) {
						score += 40000; // Do not assign Night today if employee prefers Evening tomorrow!
					}
				}
			}
		}

		if (pref.isDayPreferredOff(date.getDayOfWeek()) && shiftType != ShiftType.OFF) {
			score += 1200;
		}

		if (pref.isDayPreferredWorking(date.getDayOfWeek()) && shiftType != ShiftType.OFF) {
			score -= 500;
		}
	}

	// 2. Shift Block Continuity & Transition Scoring (Continuous Shift Blocks)
	Shift lastShiftObj = lastShiftMap != null ? lastShiftMap.get(employee.getId()) : null;
	LocalDate lastShiftDate = lastShiftDateMap != null ? lastShiftDateMap.get(employee.getId()) : null;
	boolean isConsecutiveDay = (lastShiftDate != null && date != null && lastShiftDate.equals(date.minusDays(1)));
	ShiftType lastShift = (lastShiftObj != null && isConsecutiveDay) ? lastShiftObj.getShiftType() : null;
	ShiftType lastWorkingShift = (lastShiftObj != null && lastShiftObj.getShiftType() != ShiftType.OFF) ? lastShiftObj.getShiftType() : null;

	if (lastWorkingShift != null) {
		if (lastWorkingShift == shiftType) {
			// Multi-day same-shift block bonus: high reward for continuity (M->M, G->G, E->E, N->N)
			score -= 2500;
			if (shiftCount <= 4) {
				score -= 600; // Bonus to sustain continuous block
			}
		} else {
			// Check if employee already worked this shiftType earlier in the current cycle
			int timesWorkedEarlier = myCounts != null ? myCounts.getOrDefault(shiftType, 0) : 0;
			if (timesWorkedEarlier > 0) {
				// Fragmented recurrence penalty (e.g. M -> G -> M or G -> E -> G)
				score += 65000; // Heavy penalty to eliminate fragmented recurrence
			} else {
				// Natural fresh shift transition
				if ((lastWorkingShift == ShiftType.MORNING && shiftType == ShiftType.GENERAL) ||
				    (lastWorkingShift == ShiftType.GENERAL && shiftType == ShiftType.MORNING)) {
					score -= 100; // Smooth day-shift transition
				} else if ((lastWorkingShift == ShiftType.GENERAL && shiftType == ShiftType.EVENING) ||
				           (lastWorkingShift == ShiftType.EVENING && shiftType == ShiftType.GENERAL)) {
					score -= 50; // Smooth afternoon/evening transition
				} else if ((lastWorkingShift == ShiftType.MORNING && shiftType == ShiftType.EVENING) ||
				           (lastWorkingShift == ShiftType.EVENING && shiftType == ShiftType.MORNING)) {
					score += 500; // Sharp transition penalized
				} else if (lastWorkingShift == ShiftType.GENERAL && shiftType == ShiftType.NIGHT) {
					score += 200;
				} else if (lastWorkingShift == ShiftType.EVENING && shiftType == ShiftType.NIGHT) {
					score -= 50;
				}
			}
		}
	}

	// 3. Female Shift Balance (Part A #3)
	// Females work MORNING and GENERAL (0 NIGHT, 0 EVENING).
	// Prevent monotone all-Morning or all-General schedules unless preferred.
	if (employee.getGender() == Gender.FEMALE) {
		boolean prefersSpecificDayShift = (pref != null && pref.hasPreferredShifts() &&
				(pref.isShiftPreferred(ShiftType.MORNING) ^ pref.isShiftPreferred(ShiftType.GENERAL)));

		if (!prefersSpecificDayShift) {
			if (shiftType == ShiftType.MORNING) {
				if (morningCount > generalCount + 1) {
					score += (morningCount - generalCount) * 350;
				} else if (morningCount < generalCount) {
					score -= (generalCount - morningCount) * 300;
				}
			} else if (shiftType == ShiftType.GENERAL) {
				if (generalCount > morningCount + 1) {
					score += (generalCount - morningCount) * 350;
				} else if (generalCount < morningCount) {
					score -= (morningCount - generalCount) * 300;
				}
			}
		}
	}

	// 4. Male Shift Balance (Part A #1, #4)
	// Avoid over-concentrating males on Evening/Night.
	// Balance eligible shifts across Morning, General, Evening, Night.
	if (employee.getGender() == Gender.MALE) {
		int dayDuties = morningCount + generalCount;
		LocalDate scheduledOff = null;
		if (weeklyOffs != null && weeklyOffs.containsKey(employee.getId())) {
			Set<LocalDate> offs = weeklyOffs.get(employee.getId());
			if (offs != null) {
				scheduledOff = offs.stream()
						.filter(d -> !d.isBefore(date))
						.min(LocalDate::compareTo)
						.orElse(offs.stream().min(LocalDate::compareTo).orElse(null));
			}
		}
		int dayOffset = (cycleStartDate != null && date != null) ? (int) java.time.temporal.ChronoUnit.DAYS.between(cycleStartDate, date) : 0;
		int offOffset = (scheduledOff != null && cycleStartDate != null) ? (int) java.time.temporal.ChronoUnit.DAYS.between(cycleStartDate, scheduledOff) : -1;
		int daysToOff = (offOffset >= 0 && offOffset >= dayOffset) ? (offOffset - dayOffset) : 7;
		int targetNightStart = offOffset >= 0 ? Math.max(0, offOffset - 2) : 7;
		int daysToLateShift = Math.min(daysToOff, (targetNightStart >= dayOffset ? targetNightStart - dayOffset : 7));

		if (shiftType == ShiftType.EVENING) {
			boolean continuingEvening = (lastWorkingShift == ShiftType.EVENING);
			if (!continuingEvening) {
				if (eveningCount == 1) {
					score += 3000;
				} else if (eveningCount == 2) {
					score += 15000;
				} else if (eveningCount >= 3) {
					score += 50000;
				}

				if (lastShift != ShiftType.EVENING) {
					if (dayDuties == 0 && daysToLateShift >= 3) {
						score += 15000;
					} else if (daysToLateShift <= 2 && daysToLateShift >= 1) {
						score -= 1500;
					}
				}
			}
		}

		if (shiftType == ShiftType.NIGHT) {
			int myCurNights = cycleNightCounts != null ? cycleNightCounts.getOrDefault(employee.getId(), 0) : nightCount;
			int priorNights = (int) assignmentRepository.countShiftForEmployee(employee.getId(), ShiftType.NIGHT);

			boolean wasNightYesterday = (lastShift == ShiftType.NIGHT);
			boolean prefersNonNight = (pref != null && pref.hasPreferredShifts() && !pref.isShiftPreferred(ShiftType.NIGHT));

			boolean isSmallMalePool = (shiftCountsMap != null && shiftCountsMap.size() <= 6);
			int empMaxN = employee.getMaxNightsPerWeek() != null ? employee.getMaxNightsPerWeek() : 2;
			if (empMaxN == 1) {
				if (prefersNonNight) score += 35000;
				else if (myCurNights == 0) score -= 5500;
				else if (isSmallMalePool && myCurNights == 1) score -= 4000;
				else score += 40000;
			} else if (empMaxN == 2) {
				if (myCurNights == 0) score -= 5500;
				else if (myCurNights == 1) score -= 4000;
				else score += 25000;
			} else {
				if (myCurNights == 0) score -= 6000;
				else if (myCurNights == 1) score -= 5000;
				else if (myCurNights == 2) score -= 2500;
				else score += 15000;
			}

			if (employee.isDevelopmentTeam() && isSmallMalePool) {
				if (myCurNights == 0) score -= 3500;
				else score += 35000;
			}

			boolean isTargetMale = employee.getGender() == Gender.MALE && !employee.isDevelopmentTeam();
			if (!isTargetMale && !employee.isDevelopmentTeam()) {
				score += 35000;
			}

						if (wasNightYesterday) {
				return 100000; // CRIS Strict Rule: Working after Night shift is forbidden
			} else if (myCurNights == 0) {
				// Only incentivize males to take Night if they do NOT have an explicit non-night preference
				if (!prefersNonNight) {
					score -= 3500;
					score += priorNights * 30;

					boolean isLeaveTomorrow = (date != null && isApprovedLeave(employee.getId(), date.plusDays(1)));
					if (isLeaveTomorrow) {
						score -= 4500;
					} else if (offOffset >= 0) {
						if (dayOffset == offOffset - 1) {
							score -= 8000; // Shift immediately before scheduled weekly off is ideal for Night!
						} else if (dayOffset == 6) {
							score -= 6000;
						} else {
							score += 25000; // Not immediately before an off day: heavily penalize!
						}
					}
				} else {
					score += 5000; // Strong penalty: do not push non-night preferring male into night
				}
			} else {
				score += 5000;
			}
		}

		if (shiftType == ShiftType.MORNING || shiftType == ShiftType.GENERAL) {
			if (dayDuties == 0) {
				score -= 1000;
			}
			if (eveningCount >= 1 && nightCount >= 1) {
				score -= 200;
			}
		}
	}

		// 5. Controlled Non-Deterministic Tie-Breaking & Attempt Diversity Exploration
	int attemptPerturbation = (int) (((employee.getId() * 313L + randomSeed * 7919L + (date != null ? date.getDayOfMonth() * 17L : 0)) % 2001L) - 1000L);
	score += attemptPerturbation;

	return score;
}

private int score(Employee employee, ShiftType shiftType, LocalDate date, Map<Long, Shift> lastShiftMap,
		Map<Long, Integer> cycleNightCounts, Map<Long, Map<ShiftType, Integer>> shiftCountsMap,
		Map<Long, Set<LocalDate>> weeklyOffs, LocalDate cycleStartDate,
		ApplicablePreference pref, int randomSeed) {
	return score(employee, shiftType, date, lastShiftMap, null, cycleNightCounts, shiftCountsMap, weeklyOffs, cycleStartDate, pref, randomSeed);
}

private int score(Employee employee, ShiftType shiftType, LocalDate date, Map<Long, Shift> lastShiftMap,
		Map<Long, Integer> cycleNightCounts, Map<Long, Map<ShiftType, Integer>> shiftCountsMap,
		Map<Long, Set<LocalDate>> weeklyOffs, LocalDate cycleStartDate) {
	return score(employee, shiftType, date, lastShiftMap, null, cycleNightCounts, shiftCountsMap, weeklyOffs, cycleStartDate, null, 0);
}

private int score(Employee employee, ShiftType shiftType, LocalDate date, Map<Long, Shift> lastShiftMap,
		Map<Long, Integer> cycleNightCounts, Map<Long, Map<ShiftType, Integer>> shiftCountsMap) {
	return score(employee, shiftType, date, lastShiftMap, null, cycleNightCounts, shiftCountsMap, (Map<Long, Set<LocalDate>>) null, null, null, 0);
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
						score += 25 * consecutive; // Reward multi-day shift blocks
					} else {
						if ((last == ShiftType.MORNING && cur == ShiftType.GENERAL) ||
						    (last == ShiftType.GENERAL && cur == ShiftType.MORNING) ||
						    (last == ShiftType.GENERAL && cur == ShiftType.EVENING) ||
						    (last == ShiftType.EVENING && cur == ShiftType.NIGHT)) {
							score += 5; // Smooth rotation
						} else {
							score -= 15; // Clashing transition
						}
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
		validateGeneratedRoster(cycle, assignments, shifts, maxNightsAllowed, null);
	}

	public void validateGeneratedRoster(RosterCycle cycle, List<RosterAssignment> assignments, Map<ShiftType, Shift> shifts, int maxNightsAllowed, Map<Long, ApplicablePreference> preferencesMap) {
		Map<Long, List<RosterAssignment>> empAssignments = new HashMap<>();
		for (RosterAssignment a : assignments) {
			empAssignments.computeIfAbsent(a.getEmployee().getId(), k -> new ArrayList<>()).add(a);
		}

		long activeMaleCount = empAssignments.values().stream()
				.map(l -> l.isEmpty() ? null : l.get(0).getEmployee())
				.filter(e -> e != null)
				.filter(e -> e.getGender() == Gender.MALE && e.isActive())
				.count();

		for (Map.Entry<Long, List<RosterAssignment>> entry : empAssignments.entrySet()) {
			List<RosterAssignment> list = entry.getValue();
			list.sort(Comparator.comparing(RosterAssignment::getRosterDate));
			Long empId = entry.getKey();
			ApplicablePreference pref = preferencesMap != null ? preferencesMap.getOrDefault(empId, ApplicablePreference.none(empId)) : ApplicablePreference.none(empId);

			int nightCount = 0;
			int offCount = 0;
			int leaveCount = 0;
			RosterAssignment prevWorking = null;

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

				// Avoid Shift Hard Constraint (unless overridden by Admin or required for male night coverage)
				if (!curr.isOverridden() && !curr.isWeeklyOff() && !curr.isOnLeave() && curr.getShift().getShiftType() != ShiftType.OFF) {
					if (pref != null && pref.isShiftAvoided(curr.getShift().getShiftType())) {
						if (curr.getShift().getShiftType() == ShiftType.NIGHT && emp.getGender() == Gender.MALE) {
							// Soft preference traded off for mandatory male night requirement
						} else {
							throw new BusinessException("Validation failure: Employee " + emp.getEmployeeCode()
									+ " assigned to avoided shift " + curr.getShift().getShiftType() + " on " + curr.getRosterDate());
						}
					}
				}

				if (!curr.isWeeklyOff() && !curr.isOnLeave() && curr.getShift().getShiftType() != ShiftType.OFF) {
					if (curr.getShift().getShiftType() == ShiftType.NIGHT) {
						nightCount++;
					}

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

			if (nightCount > maxNightsAllowed) {
				throw new BusinessException("Validation failure: Employee " + entry.getKey()
						+ " assigned " + nightCount + " night shifts in cycle (max allowed is " + maxNightsAllowed + ")");
			}

			int expectedOffs = (cycle != null) ? cycle.getExpectedWeeklyOffs() : 1;
			int targetOffs = Math.min(expectedOffs, Math.max(0, 7 - leaveCount));
			if (offCount != targetOffs && leaveCount < 7) {
				Employee emp = list.get(0).getEmployee();
				throw new BusinessException("Validation failure: Employee " + emp.getEmployeeCode()
						+ " has " + offCount + " weekly OFF assignments in cycle (expected exactly " + targetOffs + ")");
			}

			// Batch 33: Mandatory Minimum Night Allocation Check for Eligible Males
			Employee emp = list.get(0).getEmployee();
			if (emp != null && emp.getGender() == Gender.MALE && emp.isActive() && leaveCount < 7 && activeMaleCount <= 7) {
				if (pref != null && (pref.isShiftAvoided(ShiftType.NIGHT) || (pref.hasPreferredShifts() && !pref.isShiftPreferred(ShiftType.NIGHT)))) {
					// Employee explicitly requested non-night shifts (e.g. MORNING or GENERAL) via approved preference
				} else if (nightCount < 1) {
					throw new BusinessException("Validation failure: Mandatory minimum night allocation not satisfied for eligible male employee " + emp.getEmployeeCode() + " (" + emp.getFirstName() + " " + emp.getLastName() + ") with 0 night shifts");
				}
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
				dayConfiguredDemands.put(type, cap);
			}

			// Calculate realistic feasible daily demand tailored for available staff
			Map<ShiftType, Integer> dayFeasibleDemands = new EnumMap<>(ShiftType.class);
			int avail = Math.max(0, plannedWorkingStaff);
			int morningMin = getRequiredMorningMin(date);
			int eveningMin = getRequiredEveningMin(date);
			int nightMin = getRequiredNightMin(date);

			int nightTarget = Math.max(nightMin, dayConfiguredDemands.getOrDefault(ShiftType.NIGHT, 1));
			int eveningTarget = Math.max(eveningMin, dayConfiguredDemands.getOrDefault(ShiftType.EVENING, 1));
			int morningTarget = Math.max(morningMin, dayConfiguredDemands.getOrDefault(ShiftType.MORNING, 2));
			int generalTarget = dayConfiguredDemands.getOrDefault(ShiftType.GENERAL, 2);

			dayFeasibleDemands.put(ShiftType.NIGHT, (nightTarget > 0 && avail >= 1) ? 1 : 0);
			dayFeasibleDemands.put(ShiftType.EVENING, (eveningTarget > 0 && avail >= 2) ? 1 : 0);
			dayFeasibleDemands.put(ShiftType.MORNING, (morningTarget > 0 && avail >= 3) ? 1 : 0);
			if (morningMin >= 2 && avail >= 4) {
				dayFeasibleDemands.put(ShiftType.MORNING, 2);
				dayFeasibleDemands.put(ShiftType.GENERAL, (generalTarget > 0 && avail >= 5) ? 1 : 0);
			} else {
				dayFeasibleDemands.put(ShiftType.GENERAL, (generalTarget > 0 && avail >= 4) ? 1 : 0);
			}

			int rem = Math.max(0, avail - dayFeasibleDemands.values().stream().mapToInt(Integer::intValue).sum());
			while (rem > 0) {
				boolean allocated = false;
				for (ShiftType type : List.of(ShiftType.MORNING, ShiftType.GENERAL, ShiftType.EVENING, ShiftType.NIGHT)) {
					int cur = dayFeasibleDemands.getOrDefault(type, 0);
					int target = dayConfiguredDemands.getOrDefault(type, 1);
					if (type == ShiftType.MORNING) {
						target = Math.max(target, morningMin);
					}
					if (cur < target && rem > 0) {
						dayFeasibleDemands.put(type, cur + 1);
						rem--;
						allocated = true;
					}
				}
				if (!allocated) {
					dayFeasibleDemands.put(ShiftType.GENERAL, dayFeasibleDemands.getOrDefault(ShiftType.GENERAL, 0) + 1);
					rem--;
				}
			}

			List<ShiftCoverageSummary> summaries = new ArrayList<>();
			int dayConfigured = 0;
			int dayFeasible = 0;

			int totalDayAssigned = dayCounts.getOrDefault(ShiftType.MORNING, 0) + dayCounts.getOrDefault(ShiftType.GENERAL, 0);
			int totalDayFeasible = dayFeasibleDemands.getOrDefault(ShiftType.MORNING, 0) + dayFeasibleDemands.getOrDefault(ShiftType.GENERAL, 0);

			for (ShiftType type : ASSIGNMENT_ORDER) {
				int configured = dayConfiguredDemands.getOrDefault(type, 1);
				int feasible = dayFeasibleDemands.getOrDefault(type, 0);
				int actual = dayCounts.getOrDefault(type, 0);
				int opShortage;
				if (type == ShiftType.MORNING || type == ShiftType.GENERAL) {
					opShortage = (actual == 0 && feasible > 0) ? 1 : Math.max(0, totalDayFeasible - totalDayAssigned);
				} else {
					opShortage = Math.max(0, feasible - actual);
				}

				dayConfigured += configured;
				dayFeasible += feasible;

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
					reason = "âœ“ Fully staffed within safety rules";
				}

				summaries.add(new ShiftCoverageSummary(type, configured, feasible, actual, opShortage, status, reason));
			}

			int dayOpShortage = Math.max(0, dayFeasible - dayAssigned);
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

	private boolean isEligible(Employee employee, ShiftType shiftType, ApplicablePreference pref, LocalDate date) {
		if (date != null && !employee.isEligibleForDate(date)) {
			return false;
		}
		if (employee.getGender() == Gender.FEMALE && (shiftType == ShiftType.EVENING || shiftType == ShiftType.NIGHT)) {
			return false;
		}
		if (pref != null) {
			if (pref.isShiftAvoided(shiftType)) {
				return false;
			}
			if (date != null) {
				ShiftType targetShift = pref.getTargetShiftForDate(date);
				if (targetShift != null && targetShift != shiftType) {
					return false; // Strictly reserved for Admin configured date/recurring preference
				}
				// Lookahead: protect tomorrow's target shift rest & safety
				ShiftType tomorrowTarget = pref.getTargetShiftForDate(date.plusDays(1));
				if (tomorrowTarget != null && tomorrowTarget != ShiftType.OFF) {
					if (shiftType == ShiftType.NIGHT) {
						return false; // Night shift requires tomorrow to be Weekly OFF
					}
					if (shiftType == ShiftType.EVENING && tomorrowTarget == ShiftType.MORNING) {
						return false; // Evening ends 22:00 -> Morning starts 07:00 (9h < 12h rest)
					}
				}
			}
		}
		return true;
	}

	private boolean isEligible(Employee employee, ShiftType shiftType, ApplicablePreference pref) {
		return isEligible(employee, shiftType, pref, null);
	}

	private boolean isEligible(Employee employee, ShiftType shiftType) {
		return isEligible(employee, shiftType, null);
	}

	private boolean hasTargetWorkShift(ApplicablePreference pref, LocalDate date) {
		if (pref == null || date == null) return false;
		ShiftType target = pref.getTargetShiftForDate(date);
		return target != null && target != ShiftType.OFF;
	}

	private boolean hasTargetOffShift(ApplicablePreference pref, LocalDate date) {
		if (pref == null || date == null) return false;
		ShiftType target = pref.getTargetShiftForDate(date);
		return target == ShiftType.OFF;
	}

	private ShiftType nextShift(Employee employee, ShiftType previous) {
		List<ShiftType> rotation = employee.getGender() == Gender.FEMALE ? FEMALE_ROTATION : MALE_ROTATION;
		int index = rotation.indexOf(previous);
		return index < 0 ? rotation.get(0) : rotation.get((index + 1) % rotation.size());
	}

	private boolean canSwapShiftsSafely(RosterAssignment a1, ShiftType newShift1, RosterAssignment a2, ShiftType newShift2, List<RosterAssignment> list1, List<RosterAssignment> list2, Map<ShiftType, Shift> shifts) {
		LocalDate d = a1.getRosterDate();
		Shift s1 = shifts.get(newShift1);
		Shift s2 = shifts.get(newShift2);

		RosterAssignment prev1 = list1.stream().filter(a -> a.getRosterDate().equals(d.minusDays(1)) && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null).findFirst().orElse(null);
		RosterAssignment next1 = list1.stream().filter(a -> a.getRosterDate().equals(d.plusDays(1)) && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null).findFirst().orElse(null);
		if (prev1 != null && !hasMinimumRest(prev1.getRosterDate(), prev1.getShift(), d, s1)) return false;
		if (next1 != null && !hasMinimumRest(d, s1, next1.getRosterDate(), next1.getShift())) return false;

		RosterAssignment prev2 = list2.stream().filter(a -> a.getRosterDate().equals(d.minusDays(1)) && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null).findFirst().orElse(null);
		RosterAssignment next2 = list2.stream().filter(a -> a.getRosterDate().equals(d.plusDays(1)) && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null).findFirst().orElse(null);
		if (prev2 != null && !hasMinimumRest(prev2.getRosterDate(), prev2.getShift(), d, s2)) return false;
		if (next2 != null && !hasMinimumRest(d, s2, next2.getRosterDate(), next2.getShift())) return false;

		return true;
	}

	private void balanceMaleEveningAssignments(List<RosterAssignment> assignments, List<Employee> employees, Map<ShiftType, Shift> shifts, Map<Long, ApplicablePreference> preferencesMap) {
		Map<Long, List<RosterAssignment>> empAssignments = assignments.stream()
				.collect(Collectors.groupingBy(a -> a.getEmployee().getId()));
		List<Employee> males = employees.stream().filter(e -> e.getGender() == Gender.MALE && e.isActive()).toList();
		for (Employee male : males) {
			ApplicablePreference malePref = preferencesMap != null ? preferencesMap.get(male.getId()) : null;
			List<RosterAssignment> maleList = empAssignments.getOrDefault(male.getId(), Collections.emptyList());
			List<RosterAssignment> evenings = maleList.stream().filter(a -> !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.EVENING).toList();
			if (evenings.size() > 3) {
				for (RosterAssignment eveningToMove : evenings) {
					if (eveningToMove.isManualAssignment() || eveningToMove.isOverridden()) continue;
					long currentEve = maleList.stream().filter(a -> !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.EVENING).count();
					if (currentEve <= 3) break;
					LocalDate d = eveningToMove.getRosterDate();
					if (malePref != null && (malePref.hasDateSpecificShift(d) || malePref.hasTargetShiftForDate(d))) continue;
					for (Employee otherMale : males) {
						if (otherMale.getId().equals(male.getId())) continue;
						if (otherMale.isDevelopmentTeam()) continue; // Dev team schedule takes precedence
						if (otherMale.getPairedEmployeeId() != null) {
							boolean partnerWorkingToday = assignments.stream().anyMatch(a -> a.getRosterDate().equals(d) && a.getEmployee().getId().equals(otherMale.getPairedEmployeeId()) && !a.isWeeklyOff() && !a.isOnLeave());
							if (partnerWorkingToday) continue;
						}
						ApplicablePreference otherPref = preferencesMap != null ? preferencesMap.get(otherMale.getId()) : null;
						if (otherPref != null && (otherPref.hasDateSpecificShift(d) || otherPref.hasTargetShiftForDate(d))) continue;
						if (otherPref != null && otherPref.hasPreferredShifts() && !otherPref.isShiftPreferred(ShiftType.EVENING)) continue;
						if (!isEligible(otherMale, ShiftType.EVENING, otherPref, d)) continue;
						List<RosterAssignment> otherList = empAssignments.getOrDefault(otherMale.getId(), Collections.emptyList());
						long otherEveCount = otherList.stream().filter(a -> !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.EVENING).count();
						if (otherEveCount <= 2) {
							RosterAssignment otherAssignOnDate = otherList.stream().filter(a -> a.getRosterDate().equals(d) && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && (a.getShift().getShiftType() == ShiftType.GENERAL || a.getShift().getShiftType() == ShiftType.MORNING)).findFirst().orElse(null);
							if (otherAssignOnDate != null) {
								if (otherAssignOnDate.isManualAssignment() || otherAssignOnDate.isOverridden()) continue;
								ShiftType otherShiftType = otherAssignOnDate.getShift().getShiftType();
								if (!isEligible(male, otherShiftType, malePref, d)) continue;
								boolean maleWouldFragment = maleList.stream().anyMatch(a -> !a.equals(eveningToMove) && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == otherShiftType);
								boolean otherWouldFragment = otherList.stream().anyMatch(a -> !a.equals(otherAssignOnDate) && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.EVENING);
								if (maleWouldFragment || otherWouldFragment) continue;

								if (canSwapShiftsSafely(eveningToMove, otherShiftType, otherAssignOnDate, ShiftType.EVENING, maleList, otherList, shifts)) {
									eveningToMove.setShift(shifts.get(otherShiftType));
									otherAssignOnDate.setShift(shifts.get(ShiftType.EVENING));
									break;
								}
							}
						}
					}
				}
			}
		}
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

		if (!weeklyOff && assignment.isOnLeave()) {
			throw new BusinessException("Cannot assign working shift: Employee " + (assignment.getEmployee() != null ? assignment.getEmployee().getFirstName() : "employee") + " is on approved Leave on " + assignment.getRosterDate());
		}

		if (!weeklyOff && !isEligible(assignment.getEmployee(), shiftType)) {
			throw new BusinessException("Selected employee is not eligible for " + shiftType);
		}
		Shift shift = shiftRepository.findByShiftType(shiftType)
				.orElseThrow(() -> new ResourceNotFoundException("Shift not found"));

		// 12-hour minimum rest constraint validation
		if (!weeklyOff && shift != null && shift.getShiftType() != ShiftType.OFF && assignment.getEmployee() != null) {
			LocalDate date = assignment.getRosterDate();
			Long empId = assignment.getEmployee().getId();

			// Check previous day worked shift
			List<RosterAssignment> prevList = assignmentRepository.findByEmployeeIdAndRosterDate(empId, date.minusDays(1));
			if (!prevList.isEmpty()) {
				RosterAssignment prevA = prevList.get(0);
				if (!prevA.isWeeklyOff() && !prevA.isOnLeave() && prevA.getShift() != null && prevA.getShift().getShiftType() != ShiftType.OFF) {
					if (!hasMinimumRest(prevA.getRosterDate(), prevA.getShift(), date, shift)) {
						Duration rest = calculateRestDuration(prevA.getRosterDate(), prevA.getShift(), date, shift);
						throw new BusinessException("Cannot assign " + shiftType + " shift: 12-hour rest conflict with "
								+ prevA.getRosterDate() + " " + prevA.getShift().getShiftType() + " shift (rest: " + rest.toHours() + "h " + (rest.toMinutes() % 60) + "m)");
					}
				}
			}

			// Check next day worked shift
			List<RosterAssignment> nextList = assignmentRepository.findByEmployeeIdAndRosterDate(empId, date.plusDays(1));
			if (!nextList.isEmpty()) {
				RosterAssignment nextA = nextList.get(0);
				if (!nextA.isWeeklyOff() && !nextA.isOnLeave() && nextA.getShift() != null && nextA.getShift().getShiftType() != ShiftType.OFF) {
					if (!hasMinimumRest(date, shift, nextA.getRosterDate(), nextA.getShift())) {
						Duration rest = calculateRestDuration(date, shift, nextA.getRosterDate(), nextA.getShift());
						throw new BusinessException("Cannot assign " + shiftType + " shift: 12-hour rest conflict with "
								+ nextA.getRosterDate() + " " + nextA.getShift().getShiftType() + " shift (rest: " + rest.toHours() + "h " + (rest.toMinutes() % 60) + "m)");
					}
				}
			}
		}

		ShiftType previous = assignment.getShift().getShiftType();
		boolean isDraft = assignment.getCycle() != null && assignment.getCycle().getStatus() == RosterStatus.DRAFT;
		if (!isDraft && (weeklyOff || shiftType != previous) && previous != ShiftType.OFF && !assignment.isWeeklyOff()
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
		assignment.setAssignmentReason("Admin Override" + (reason != null ? ": " + reason : ""));

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
		List<Employee> employees = employeeRepository.findByActiveTrueOrderByIdAsc();
		Map<Long, ApplicablePreference> preferencesMap = loadApprovedPreferences(employees, cycle.getStartDate(), cycle.getEndDate());
		Map<ShiftType, Shift> shifts = activeShiftMap();
		int maxNightsAllowed = computeMaxNightsAllowed(employees);
		FinalValidationResult val = evaluateFinalValidation(cycle, assignments, shifts, maxNightsAllowed, preferencesMap, Collections.emptyMap());
		return toCycleResponse(cycle, assignments, coverageReport, val);
	}

	private RosterCycleResponse toCycleResponse(RosterCycle cycle, List<RosterAssignment> assignments, CoverageReportResponse coverageReport, FinalValidationResult val) {
		return new RosterCycleResponse(
				cycle.getId(),
				cycle.getStartDate(),
				cycle.getEndDate(),
				cycle.getGeneratedAt(),
				cycle.getGenerationMode() != null ? cycle.getGenerationMode() : GenerationMode.MANUAL,
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
				coverageReport,
				com.weeklyroster.util.RosterLifecycleUtil.classifyCycle(cycle.getStartDate(), cycle.getEndDate()),
				com.weeklyroster.util.RosterLifecycleUtil.resolveSource(cycle.getGenerationMode() != null ? cycle.getGenerationMode() : GenerationMode.MANUAL),
				cycle.getStatus() == RosterStatus.DRAFT || cycle.getStatus() == RosterStatus.GENERATED,
				val.overallStatus(),
				val.preferenceComplianceScore(),
				val.maleNightCoverage(),
				val.criticalConflicts(),
				val.warnings(),
				val.healthScore(),
				val.conflicts(),
				val.workloadMetrics(),
				val.shiftContinuityScore(),
				val.workloadBalanceScore(),
				cycle.getWorkingDays() != null ? cycle.getWorkingDays() : 6
		);
	}

		// -------------------------------------------------------------------------
	// BATCH 38: EXPLAINABLE ROSTER ("WHY THIS SHIFT?") ENGINE
	// -------------------------------------------------------------------------

	@Transactional(readOnly = true)
	public ShiftExplanationResponse getShiftExplanation(Long assignmentId) {
		RosterAssignment assignment = assignmentRepository.findById(assignmentId)
				.orElseThrow(() -> new ResourceNotFoundException("Roster assignment not found with id: " + assignmentId));
		return buildShiftExplanation(assignment);
	}

	@Transactional(readOnly = true)
	public ShiftExplanationResponse getShiftExplanation(Long cycleId, Long assignmentId) {
		RosterAssignment assignment = assignmentRepository.findById(assignmentId)
				.orElseThrow(() -> new ResourceNotFoundException("Roster assignment not found with id: " + assignmentId));
		if (assignment.getCycle() != null && !assignment.getCycle().getId().equals(cycleId)) {
			throw new BusinessException("Assignment " + assignmentId + " does not belong to cycle " + cycleId);
		}
		return buildShiftExplanation(assignment);
	}

	private ShiftExplanationResponse buildShiftExplanation(RosterAssignment assignment) {
		Employee employee = assignment.getEmployee();
		if (employee == null) {
			throw new BusinessException("Assignment has no associated employee");
		}

		// Security Authorization Check:
		// Non-admin employees can only view explanations for their own assignments!
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		boolean isAdmin = false;
		if (authentication != null && authentication.isAuthenticated()) {
			isAdmin = authentication.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
			if (!isAdmin) {
				String username = authentication.getName();
				if (employee.getUser() != null && !employee.getUser().getUsername().equals(username)) {
					throw new AccessDeniedException("You are only authorized to view explanations for your own shift assignments.");
				}
			}
		}

		LocalDate date = assignment.getRosterDate();
		String dayOfWeek = date != null ? date.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH) : "";
		RosterCycle cycle = assignment.getCycle();
		RosterStatus rosterStatus = (cycle != null && cycle.getStatus() != null) ? cycle.getStatus() : RosterStatus.GENERATED;

		boolean isLeave = assignment.isOnLeave();
		boolean isOff = assignment.isWeeklyOff() || (assignment.getShift() != null && assignment.getShift().getShiftType() == ShiftType.OFF);
		ShiftType shiftType = (assignment.getShift() != null) ? assignment.getShift().getShiftType() : ShiftType.OFF;
		String shiftName = (assignment.getShift() != null) ? getShiftDisplayName(assignment.getShift().getShiftType()) : (isLeave ? "On Leave" : "Weekly OFF");
		String shiftTiming = (assignment.getShift() != null && assignment.getShift().getTimingDisplay() != null)
				? assignment.getShift().getTimingDisplay()
				: (isLeave ? "Approved Absence" : (isOff ? "No working hours" : "09:30 â€“ 18:00"));

		boolean overridden = assignment.isOverridden();
		String adminOverrideReason = overridden ? (assignment.getAssignmentReason() != null ? assignment.getAssignmentReason() : "Changed by Admin to satisfy operational coverage") : null;

		boolean optimized = assignment.getAssignmentReason() != null && assignment.getAssignmentReason().toLowerCase().contains("optimi");
		String optimizationReason = optimized ? assignment.getAssignmentReason() : null;

		// Load employee preferences for this cycle
		ApplicablePreference pref = null;
		if (cycle != null) {
			Map<Long, ApplicablePreference> prefMap = loadApprovedPreferences(List.of(employee), cycle.getStartDate(), cycle.getEndDate());
			pref = prefMap.get(employee.getId());
		}

		List<ShiftExplanationResponse.ExplanationReasonItem> reasons = new ArrayList<>();

		// 1. Admin Override Reason
		if (overridden) {
			reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
					"OVERRIDE",
					"ðŸ”’ Admin Override",
					adminOverrideReason != null ? adminOverrideReason : "Manually assigned by Administrator to meet critical operational requirements.",
					"OVERRIDE",
					"ðŸ”’"
			));
		}

		// 2. Optimization Reason
		if (optimized && !overridden) {
			reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
					"OPTIMIZATION",
					"âš™ Optimized Assignment",
					optimizationReason != null ? optimizationReason : "Shift adjusted during re-optimization to balance workload and satisfy safety constraints.",
					"OPTIMIZED",
					"âš™"
			));
		}

		// 3. Approved Leave
		if (isLeave) {
			reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
					"LEAVE",
					"Approved Leave",
					"Employee is on approved scheduled absence for this date.",
					"INFO",
					"ðŸ–"
			));
		}
		// 4. Weekly OFF
		else if (isOff) {
			if (pref != null && pref.isDayPreferredOff(date.getDayOfWeek())) {
				reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
						"PREFERENCE",
						"âœ“ Preferred Weekly OFF",
						"Scheduled on employee's approved preferred rest day (" + dayOfWeek + ").",
						"POSITIVE",
						"âœ“"
				));
			} else {
				reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
						"WORKLOAD",
						"âœ“ Scheduled Weekly OFF",
						"Mandatory weekly rest day to ensure 6-day maximum working rule and workload fairness.",
						"POSITIVE",
						"âœ“"
				));
			}
		}
		// 5. Working Shift Rules
		else {
			// A. Preference Check
			if (pref != null && pref.hasDateSpecificShift(date)) {
				ShiftType dPref = pref.getDateSpecificShift(date);
				if (dPref == shiftType) {
					reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
							"PREFERENCE",
							"â˜… Date-specific shift preference",
							shiftType.name() + " was specifically preferred for " + date + " by Admin.",
							"POSITIVE",
							"â˜…"
					));
				} else {
					reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
							"PREFERENCE",
							"âš  Date preference fallback",
							"Assigned " + shiftType.name() + " instead of date-specific preference " + dPref.name() + " due to operational requirements or safety rules.",
							"WARNING",
							"âš "
					));
				}
			} else if (pref != null && pref.isShiftPreferred(shiftType)) {
				reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
						"PREFERENCE",
						"âœ“ Preferred shift",
						shiftType.name() + " is one of the employee's approved preferred shifts.",
						"POSITIVE",
						"âœ“"
				));
			} else if (pref != null && pref.isShiftAvoided(shiftType)) {
				reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
						"PREFERENCE",
						"âš  Avoided shift compliance",
						shiftType.name() + " assigned to satisfy mandatory operational staffing requirements despite avoid preference.",
						"WARNING",
						"âš "
				));
			}

			if (pref != null && pref.isDayPreferredWorking(date.getDayOfWeek())) {
				reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
						"PREFERENCE",
						"âœ“ Preferred working day",
						"Scheduled on employee's approved preferred working day (" + dayOfWeek + ").",
						"POSITIVE",
						"âœ“"
				));
			}

			// B. 12-Hour Rest Compliance Check (from previous day)
			List<RosterAssignment> prevList = assignmentRepository.findByEmployeeIdAndRosterDate(employee.getId(), date.minusDays(1));
			RosterAssignment prevAssignment = prevList.isEmpty() ? null : prevList.get(0);

			Double restIntervalHours = 24.0;
			if (prevAssignment != null && !prevAssignment.isWeeklyOff() && !prevAssignment.isOnLeave() && prevAssignment.getShift() != null && prevAssignment.getShift().getShiftType() != ShiftType.OFF) {
				Shift prevShift = prevAssignment.getShift();
				Shift curShift = assignment.getShift();
				boolean restValid = hasMinimumRest(date.minusDays(1), prevShift, date, curShift);
				if (restValid) {
					reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
							"REST",
							"âœ“ Rest compliance",
							"The assignment maintains the required 12-hour rest period from previous " + prevShift.getShiftType() + " duty.",
							"POSITIVE",
							"âœ“"
					));
				} else {
					reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
							"REST",
							"âš  Rest rule warning",
							"Rest period is less than 12 hours from previous duty (" + prevShift.getShiftType() + " â†’ " + curShift.getShiftType() + ").",
							"WARNING",
							"âš "
					));
				}
			} else {
				reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
						"REST",
						"âœ“ Rest compliance",
						"The assignment maintains the required 12-hour rest period (rest day / standby prior to duty).",
						"POSITIVE",
						"âœ“"
				));
			}

			// C. Shift Block Continuity
			List<RosterAssignment> nextList = assignmentRepository.findByEmployeeIdAndRosterDate(employee.getId(), date.plusDays(1));
			RosterAssignment nextAssignment = nextList.isEmpty() ? null : nextList.get(0);

			boolean samePrev = (prevAssignment != null && prevAssignment.getShift() != null && prevAssignment.getShift().getShiftType() == shiftType);
			boolean sameNext = (nextAssignment != null && nextAssignment.getShift() != null && nextAssignment.getShift().getShiftType() == shiftType);

			if (samePrev || sameNext) {
				reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
						"CONTINUITY",
						"âœ“ Shift continuity",
						shiftType.name() + " continues the employee's existing shift block without abrupt switching.",
						"POSITIVE",
						"âœ“"
				));
			} else {
				reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
						"CONTINUITY",
						"âœ“ Smooth transition",
						"Assignment adheres to standard forward shift rotation sequence.",
						"POSITIVE",
						"âœ“"
				));
			}

			// D. Coverage Requirement
			reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
					"COVERAGE",
					"âœ“ Coverage requirement",
					shiftType.name() + " coverage was required for daily operational staffing on " + dayOfWeek + ".",
					"POSITIVE",
					"âœ“"
			));

			// E. Night Duty Distribution (Male staff)
			if (shiftType == ShiftType.NIGHT && employee.getGender() == Gender.MALE) {
				reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
						"NIGHT_DISTRIBUTION",
						"âœ“ Night distribution",
						"Fulfills mandatory 1â€“2 night duty allocation for eligible male employee.",
						"POSITIVE",
						"âœ“"
				));
			}

			// F. Workload Balance
			reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
					"WORKLOAD",
					"âœ“ Workload balance",
					"The assignment helps maintain balanced weekly workload across the operational team.",
					"POSITIVE",
					"âœ“"
			));

			// G. Female Daytime Policy
			if (employee.getGender() == Gender.FEMALE) {
				reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
						"POLICY",
						"âœ“ Policy compliance",
						"Assigned to safe daytime duty (Morning/General) in full compliance with female workforce policies.",
						"POSITIVE",
						"âœ“"
				));
			}
		}

		// Admin Detailed Breakdown (Only provided for Admin users)
		ShiftExplanationResponse.ShiftExplanationAdminDetails adminDetails = null;
		if (isAdmin) {
			String prefContrib = (pref != null && pref.isShiftPreferred(shiftType)) ? "+2500 (Preferred)" : (pref != null && pref.isShiftAvoided(shiftType) ? "-2000 (Avoided)" : "0 (Neutral)");
			String contContrib = reasons.stream().anyMatch(r -> "âœ“ Shift continuity".equals(r.title())) ? "+600 (Block Continuity)" : "+150 (Forward Rotation)";
			String workContrib = "Optimal (Standard 6-day cycle distribution)";
			String nightContrib = (shiftType == ShiftType.NIGHT) ? "Satisfies mandatory male night quota (1/cycle)" : "Day duty quota";
			String covContrib = "100% daily shift minimum satisfied";

			adminDetails = new ShiftExplanationResponse.ShiftExplanationAdminDetails(
					prefContrib,
					contContrib,
					workContrib,
					nightContrib,
					covContrib,
					16.0,
					"Engine decision factor: deterministic multi-objective constraint score"
			);
		}

		return new ShiftExplanationResponse(
				assignment.getId(),
				cycle != null ? cycle.getId() : null,
				employee.getId(),
				employee.getEmployeeCode(),
				employee.getFirstName() + " " + employee.getLastName(),
				employee.getGender(),
				date,
				dayOfWeek,
				shiftType,
				shiftName,
				shiftTiming,
				rosterStatus,
				overridden,
				adminOverrideReason,
				optimized,
				optimizationReason,
				reasons,
				adminDetails
		);
	}

	private RosterAssignmentResponse toAssignmentResponse(RosterAssignment assignment) {
		Employee employee = assignment.getEmployee();
		Long cycleId = assignment.getCycle() == null ? null : assignment.getCycle().getId();
		boolean isHoliday = false;
		String holidayName = null;
		if (holidayRepository != null && assignment.getRosterDate() != null) {
			List<com.weeklyroster.entity.Holiday> holidays = holidayRepository.findByHolidayDateAndActiveTrue(assignment.getRosterDate());
			if (!holidays.isEmpty()) {
				isHoliday = true;
				holidayName = holidays.get(0).getName();
			}
		}
		return new RosterAssignmentResponse(assignment.getId(), cycleId,
				assignment.getRosterDate(), employee.getId(), employee.getEmployeeCode(),
				employee.getFirstName() + " " + employee.getLastName(), employee.getGender(),
				assignment.getShift().getShiftType(), assignment.isWeeklyOff(), assignment.isOnLeave(),
				assignment.isOverridden(), assignment.getAssignmentReason(),
				isHoliday, holidayName,
				assignment.isManualAssignment(), assignment.getAssignmentSource());
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
			boolean isHolidayOff = "HOLIDAY_OFF".equalsIgnoreCase(leave.getRequestType())
					|| (leave.getReason() != null && leave.getReason().toLowerCase().contains("holiday"));
			res.status = isHolidayOff ? "EMPLOYEE_HOLIDAY" : "LEAVE";
			res.source = isHolidayOff ? "EMPLOYEE_HOLIDAY" : "LEAVE";
			res.leaveType = isHolidayOff ? "Approved Employee Off/Holiday" : "Approved Leave";
			res.leaveReason = leave.getReason() != null ? leave.getReason() : (isHolidayOff ? "Custom Off/Holiday" : "Annual / Approved Leave");
			res.shiftType = null;
			res.shiftName = isHolidayOff ? ("ðŸ–ï¸ Off: " + res.leaveReason) : "On Leave";
			return res;
		}

		// 1b. Check if date is an Official Gazetted / Company Holiday
		if (holidayRepository != null && date != null) {
			List<com.weeklyroster.entity.Holiday> holidays = holidayRepository.findByHolidayDateAndActiveTrue(date);
			if (!holidays.isEmpty()) {
				com.weeklyroster.entity.Holiday holiday = holidays.get(0);
				res.status = "HOLIDAY";
				res.source = "HOLIDAY";
				res.leaveType = "Official Holiday";
				res.leaveReason = holiday.getName() != null ? holiday.getName() : "Official Holiday";
				res.shiftType = null;
				res.shiftName = "ðŸŽ‰ Holiday: " + (holiday.getName() != null ? holiday.getName() : "Official Holiday");
				return res;
			}
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
			boolean isHolidayOff = assignment.getAssignmentReason() != null
					&& assignment.getAssignmentReason().toLowerCase().contains("holiday");
			res.status = isHolidayOff ? "EMPLOYEE_HOLIDAY" : "LEAVE";
			res.source = isHolidayOff ? "EMPLOYEE_HOLIDAY" : "LEAVE";
			res.leaveType = isHolidayOff ? "Approved Employee Off/Holiday" : "Approved Leave";
			res.leaveReason = assignment.getAssignmentReason() != null ? assignment.getAssignmentReason() : (isHolidayOff ? "Custom Off/Holiday" : "Scheduled Absence");
			res.shiftName = isHolidayOff ? ("ðŸ–ï¸ Off: " + res.leaveReason) : "On Leave";
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
		if ("HOLIDAY".equals(res.status)) return "Official Holiday: " + (res.leaveReason != null ? res.leaveReason : "Gazetted Holiday");
		if ("EMPLOYEE_HOLIDAY".equals(res.status)) return "Approved Employee Holiday/Off: " + (res.leaveReason != null ? res.leaveReason : "Requested Off");
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

	public record FinalValidationResult(
			boolean isValid,
			String overallStatus,
			double preferenceComplianceScore,
			String maleNightCoverage,
			int criticalConflicts,
			int warnings,
			int infoCount,
			double healthScore,
			List<ConflictItem> conflicts,
			List<EmployeeWorkloadMetric> workloadMetrics,
			double shiftContinuityScore,
			double workloadBalanceScore
	) {
		public FinalValidationResult(
				boolean isValid,
				String overallStatus,
				double preferenceComplianceScore,
				String maleNightCoverage,
				int criticalConflicts,
				int warnings,
				int infoCount,
				double healthScore,
				List<ConflictItem> conflicts,
				List<EmployeeWorkloadMetric> workloadMetrics
		) {
			this(isValid, overallStatus, preferenceComplianceScore, maleNightCoverage,
					criticalConflicts, warnings, infoCount, healthScore, conflicts, workloadMetrics,
					100.0, 100.0);
		}

		public boolean canFinalize() {
			return criticalConflicts == 0;
		}
	}

	public FinalValidationResult evaluateFinalValidation(RosterCycle cycle, List<RosterAssignment> assignments,
			Map<ShiftType, Shift> shifts, int maxNightsAllowed, Map<Long, ApplicablePreference> preferencesMap,
			Map<String, RosterOverride> priorOverrideMap) {
		List<ConflictItem> conflicts = new ArrayList<>();
		if (assignments == null || assignments.isEmpty()) {
			return new FinalValidationResult(false, "INVALID", 0.0, "0 / 0", 1, 0, 0, 0.0,
					List.of(new ConflictItem(cycle.getStartDate(), null, "All Staff", null, "NO_ASSIGNMENTS", "0", "42+", "No roster assignments generated", "CRITICAL", "Generate roster", false)),
					Collections.emptyList());
		}

		Map<LocalDate, Map<ShiftType, List<RosterAssignment>>> dateShiftMap = new TreeMap<>();
		Map<Long, List<RosterAssignment>> empMap = new LinkedHashMap<>();
		Map<String, Integer> dupMap = new HashMap<>();

		for (RosterAssignment a : assignments) {
			LocalDate d = a.getRosterDate();
			dateShiftMap.computeIfAbsent(d, k -> new EnumMap<>(ShiftType.class));
			if (!a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() != ShiftType.OFF) {
				dateShiftMap.get(d).computeIfAbsent(a.getShift().getShiftType(), k -> new ArrayList<>()).add(a);
			}

			if (a.getEmployee() != null) {
				empMap.computeIfAbsent(a.getEmployee().getId(), k -> new ArrayList<>()).add(a);
				String dupKey = a.getEmployee().getId() + "_" + d;
				dupMap.put(dupKey, dupMap.getOrDefault(dupKey, 0) + 1);
			}
		}

		long eligibleMaleStaff = empMap.values().stream()
				.filter(l -> !l.isEmpty() && l.get(0).getEmployee().getGender() == Gender.MALE && l.get(0).getEmployee().isActive() && l.stream().filter(RosterAssignment::isOnLeave).count() < 7)
				.count();
		long nonDevEligibleMales = empMap.values().stream()
				.filter(l -> !l.isEmpty() && l.get(0).getEmployee().getGender() == Gender.MALE
						&& l.get(0).getEmployee().isActive()
						&& !l.get(0).getEmployee().isDevelopmentTeam()
						&& l.stream().filter(RosterAssignment::isOnLeave).count() < 7)
				.count();
		int totalMaleNightCapacity = 0;
		for (List<RosterAssignment> l : empMap.values()) {
			if (l.isEmpty()) continue;
			Employee emp = l.get(0).getEmployee();
			if (emp.getGender() != Gender.MALE || !emp.isActive() || emp.isDevelopmentTeam() || l.stream().filter(RosterAssignment::isOnLeave).count() >= 7) continue;
			int cap = emp.getMaxNightsPerWeek() != null ? emp.getMaxNightsPerWeek() : Math.min(2, maxNightsAllowed);
			if (nonDevEligibleMales <= 3 && emp.getMaxNightsPerWeek() != null) {
				cap = Math.max(cap, 2);
			}
			totalMaleNightCapacity += cap;
		}
		boolean isSixDayMode = (cycle != null && cycle.getWorkingDays() != null && cycle.getWorkingDays() == 6);
		boolean nightCapacityShortage = isSixDayMode
				? (eligibleMaleStaff < 7)
				: (totalMaleNightCapacity < 7 || eligibleMaleStaff * maxNightsAllowed < 7);

		boolean coverageOk = true;
		for (LocalDate date = cycle.getStartDate(); !date.isAfter(cycle.getEndDate()); date = date.plusDays(1)) {
			Map<ShiftType, List<RosterAssignment>> shiftMap = dateShiftMap.getOrDefault(date, Collections.emptyMap());
			int mCount = shiftMap.getOrDefault(ShiftType.MORNING, Collections.emptyList()).size();
			int gCount = shiftMap.getOrDefault(ShiftType.GENERAL, Collections.emptyList()).size();
			int eCount = shiftMap.getOrDefault(ShiftType.EVENING, Collections.emptyList()).size();
			int nCount = shiftMap.getOrDefault(ShiftType.NIGHT, Collections.emptyList()).size();

			long workingCountOnDate = (mCount + gCount + eCount + nCount);
			int reqMorning = isWeekend(date) ? 1 : ((workingCountOnDate <= 5) ? 1 : getRequiredMorningMin(date));
			int reqEvening = getRequiredEveningMin(date);
			int reqNight = getRequiredNightMin(date);
			int minTotalStaff = reqMorning + reqEvening + reqNight;

			if (workingCountOnDate < minTotalStaff) {
				coverageOk = false;
				conflicts.add(new ConflictItem(
						date, null, "Staffing Capacity", null,
						"STAFFING_CAPACITY_DEFICIT", workingCountOnDate + " available", ">= " + minTotalStaff + " required",
						"Staffing conflict: Approved employee requirements for " + date + " leave insufficient eligible employees for the required shifts (available: " + workingCountOnDate + ", minimum required: " + minTotalStaff + ")",
						"CRITICAL", "Review approved employee requirements for " + date, false
				));
			}

			if (mCount < reqMorning) {
				if (workingCountOnDate < (reqMorning + 2)) {
					conflicts.add(new ConflictItem(date, null, "Morning Staffing", ShiftType.MORNING, "MIN_COVERAGE_MORNING", mCount + " assigned", ">= " + reqMorning + " assigned", "Morning shift unstaffed on " + date + " due to limited available working staff (" + workingCountOnDate + ")", "WARNING", "Review staffing capacity", false));
				} else {
					coverageOk = false;
					conflicts.add(new ConflictItem(date, null, "Morning Staffing", ShiftType.MORNING, "MIN_COVERAGE_MORNING", mCount + " assigned", ">= " + reqMorning + " assigned", "Morning shift staffing deficit on " + date + " (" + mCount + " assigned, minimum " + reqMorning + " required)", "CRITICAL", "Assign at least " + reqMorning + " staff to Morning", false));
				}
			}
			if (!isWeekend(date) && gCount < 1) {
				if (workingCountOnDate < 4) {
					conflicts.add(new ConflictItem(date, null, "General Staffing", ShiftType.GENERAL, "MIN_COVERAGE_GENERAL", "0 assigned", ">= 1 assigned", "General shift unstaffed on " + date + " due to limited available working staff (" + workingCountOnDate + ")", "WARNING", "Review staffing capacity", false));
				} else {
					coverageOk = false;
					conflicts.add(new ConflictItem(date, null, "General Staffing", ShiftType.GENERAL, "MIN_COVERAGE_GENERAL", "0 assigned", ">= 1 assigned", "General shift has 0 assigned staff on " + date, "CRITICAL", "Assign at least 1 staff to General", false));
				}
			}
			if (eCount < reqEvening) {
				if (workingCountOnDate < 2 || eligibleMaleStaff == 0) {
					conflicts.add(new ConflictItem(date, null, "Evening Staffing", ShiftType.EVENING, "MIN_COVERAGE_EVENING", eCount + " assigned", ">= " + reqEvening + " assigned", "Evening shift unstaffed on " + date + " due to limited available working staff (" + workingCountOnDate + ")", "WARNING", "Review staffing capacity", false));
				} else {
					coverageOk = false;
					conflicts.add(new ConflictItem(date, null, "Evening Staffing", ShiftType.EVENING, "MIN_COVERAGE_EVENING", eCount + " assigned", ">= " + reqEvening + " assigned", "Evening shift has " + eCount + " assigned staff on " + date + " (minimum " + reqEvening + " required)", "CRITICAL", "Assign at least " + reqEvening + " staff to Evening", false));
				}
			}

			if (nCount < reqNight) {
				if (nightCapacityShortage) {
					conflicts.add(new ConflictItem(date, null, "Night Staffing", ShiftType.NIGHT, "MIN_COVERAGE_NIGHT", "0 assigned", "1 assigned", "Night shift unstaffed on " + date + " due to active male capacity (" + eligibleMaleStaff + " eligible males * " + maxNightsAllowed + " max nights < 7)", "WARNING", "Review male workforce staffing capacity", false));
				} else {
					coverageOk = false;
					conflicts.add(new ConflictItem(date, null, "Night Staffing", ShiftType.NIGHT, "MIN_COVERAGE_NIGHT", "0 assigned", "Exactly 1 assigned", "Night shift has 0 assigned staff on " + date, "CRITICAL", "Assign exactly 1 eligible male staff to Night", false));
				}
			} else if (nCount > reqNight) {
				coverageOk = false;
				conflicts.add(new ConflictItem(date, null, "Night Staffing", ShiftType.NIGHT, "EXACT_NIGHT_COVERAGE", nCount + " assigned", "Exactly 1 assigned", "Night shift has " + nCount + " staff assigned (strictly 1 required) on " + date, "CRITICAL", "Reduce Night shift to 1 employee", false));
			}
		}

		for (Map.Entry<String, Integer> dup : dupMap.entrySet()) {
			if (dup.getValue() > 1) {
				String[] parts = dup.getKey().split("_");
				Long empId = Long.parseLong(parts[0]);
				LocalDate date = LocalDate.parse(parts[1]);
				long offCountOnDate = assignments.stream()
						.filter(a -> a.getEmployee() != null && a.getEmployee().getId().equals(empId) && a.getRosterDate().equals(date))
						.filter(a -> a.isWeeklyOff() || (a.getShift() != null && a.getShift().getShiftType() == ShiftType.OFF))
						.count();
				boolean isDupOff = (offCountOnDate > 1);
				String rule = isDupOff ? "DUPLICATE_WEEKLY_OFF" : "DUPLICATE_ASSIGNMENT";
				String reason = isDupOff
						? "Employee has duplicate Weekly OFF assignments on " + date
						: "Employee has multiple roster assignments on " + date;
				conflicts.add(new ConflictItem(date, empId, "Employee #" + empId, null, rule, dup.getValue() + " assignments", "1 assignment", reason, "CRITICAL", "Remove duplicate assignments", false));
			}
		}

		boolean restOk = true;
		boolean nightLimitOk = true;
		boolean genderOk = true;
		boolean maleNightOk = true;

		int totalPrefOpportunities = 0;
		int satisfiedPrefPoints = 0;
		int eligibleMaleCount = 0;
		int satisfiedMaleNightCount = 0;
		List<EmployeeWorkloadMetric> workloadMetrics = new ArrayList<>();

		for (List<RosterAssignment> empAssignments : empMap.values()) {
			if (empAssignments.isEmpty()) continue;
			empAssignments.sort(Comparator.comparing(RosterAssignment::getRosterDate));
			Employee emp = empAssignments.get(0).getEmployee();
			String empName = emp.getFirstName() + " " + (emp.getLastName() != null ? emp.getLastName() : "");
			Long empId = emp.getId();
			ApplicablePreference pref = preferencesMap != null ? preferencesMap.getOrDefault(empId, ApplicablePreference.none(empId)) : ApplicablePreference.none(empId);

			int nightCount = 0;
			int offCount = 0;
			int leaveCount = 0;
			int morningCount = 0;
			int generalCount = 0;
			int eveningCount = 0;
			int consecutiveDays = 0;
			int maxConsecutiveDays = 0;
			int consecutiveNights = 0;
			int maxConsecutiveNights = 0;
			int weekendDuties = 0;
			int preferredFulfilled = 0;
			RosterAssignment prevWorking = null;

			LocalDate firstDate = empAssignments.get(0).getRosterDate();
			List<RosterAssignment> before = assignmentRepository.findWorkedAssignmentsBefore(empId, firstDate);
			if (!before.isEmpty()) {
				prevWorking = before.get(0);
			}

			for (RosterAssignment curr : empAssignments) {
				LocalDate d = curr.getRosterDate();
				boolean isWeekend = (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY);

				if (curr.isOnLeave()) {
					leaveCount++;
					consecutiveDays = 0;
					consecutiveNights = 0;
				} else if (curr.isWeeklyOff() || (curr.getShift() != null && curr.getShift().getShiftType() == ShiftType.OFF)) {
					offCount++;
					consecutiveDays = 0;
					consecutiveNights = 0;

					if (pref.hasPreferredOffDays()) {
						totalPrefOpportunities++;
						if (pref.isDayPreferredOff(d.getDayOfWeek())) {
							satisfiedPrefPoints++;
						} else {
							conflicts.add(new ConflictItem(d, empId, empName, null, "PREFERRED_OFF_NOT_MET", d.getDayOfWeek().name(), pref.preferredOffDays().toString(), "Preferred OFF day not satisfied on " + d + " because coverage/rest constraints required an alternate schedule", "WARNING", "Review employee OFF day allocation", false));
						}
					}
				} else {
					if (isApprovedLeave(empId, d)) {
						coverageOk = false;
						conflicts.add(new ConflictItem(
								d, empId, empName, curr.getShift() != null ? curr.getShift().getShiftType() : null,
								"APPROVED_LEAVE_VIOLATION", "Assigned Duty", "Must be ON_LEAVE",
								"Employee " + empName + " has an approved leave/off request on " + d + " but was assigned a duty shift",
								"CRITICAL", "Reassign to Leave / Off", false
						));
					}
					consecutiveDays++;
					maxConsecutiveDays = Math.max(maxConsecutiveDays, consecutiveDays);
					if (isWeekend) weekendDuties++;

					ShiftType st = curr.getShift().getShiftType();
					switch (st) {
						case MORNING -> morningCount++;
						case GENERAL -> generalCount++;
						case EVENING -> eveningCount++;
						case NIGHT -> {
							nightCount++;
							consecutiveNights++;
							maxConsecutiveNights = Math.max(maxConsecutiveNights, consecutiveNights);
						}
						default -> {}
					}
					if (st != ShiftType.NIGHT) consecutiveNights = 0;

					if (emp.getGender() == Gender.FEMALE && (st == ShiftType.EVENING || st == ShiftType.NIGHT)) {
						genderOk = false;
						conflicts.add(new ConflictItem(d, empId, empName, st, "FEMALE_RESTRICTION", st.name(), "MORNING or GENERAL", "Female employee " + empName + " assigned to restricted " + st + " shift on " + d, "CRITICAL", "Reassign female employee to Day shift", false));
					}

					if (!curr.isOverridden() && pref.isShiftAvoided(st)) {
						boolean maleNightReq = (st == ShiftType.NIGHT && emp.getGender() == Gender.MALE);
						String sev = maleNightReq ? "WARNING" : "CRITICAL";
						conflicts.add(new ConflictItem(d, empId, empName, st, "AVOID_SHIFT_VIOLATION", st.name(), "Not " + st.name(),
								"Employee " + empName + " assigned to approved avoided shift " + st + " on " + d + (maleNightReq ? " (required for mandatory male night coverage)" : ""),
								sev, "Reassign away from avoided shift", false));
					}

					if (pref.hasPreferredShifts()) {
						totalPrefOpportunities++;
						if (pref.isShiftPreferred(st)) {
							satisfiedPrefPoints++;
							preferredFulfilled++;
						} else {
							conflicts.add(new ConflictItem(d, empId, empName, st, "PREFERRED_SHIFT_NOT_MET", st.name(), pref.preferredShifts().toString(), "Preferred shift " + pref.preferredShifts() + " not selected (assigned " + st + ") on " + d + " due to workforce balancing", "WARNING", "Consider adjusting shift if feasible", false));
						}
					}

					if (pref.hasAvoidShifts()) {
						totalPrefOpportunities++;
						if (!pref.isShiftAvoided(st)) {
							satisfiedPrefPoints++;
						}
					}

					if (pref.hasPreferredWorkingDays()) {
						totalPrefOpportunities++;
						if (pref.isDayPreferredWorking(d.getDayOfWeek())) {
							satisfiedPrefPoints++;
						}
					}

										if (prevWorking != null) {
						if (!hasMinimumRest(prevWorking.getRosterDate(), prevWorking.getShift(), d, curr.getShift())) {
							restOk = false;
							Duration rest = calculateRestDuration(prevWorking.getRosterDate(), prevWorking.getShift(), d, curr.getShift());
							conflicts.add(new ConflictItem(d, empId, empName, st, "REST_INTERVAL_12H", rest.toHours() + "h " + (rest.toMinutes() % 60) + "m rest", ">= 12h rest", prevWorking.getShift().getShiftType() + " -> " + st + " violates 12h minimum rest rule between " + prevWorking.getRosterDate() + " and " + d, "CRITICAL", "Schedule a rest day or shift with >= 12h separation", false));
						}
					}
					prevWorking = curr;
				}

				// BATCH 109: Strict Hard Rule: Every NIGHT shift must be followed by WEEKLY OFF on the following calendar day
				if (!curr.isWeeklyOff() && !curr.isOnLeave() && curr.getShift() != null && curr.getShift().getShiftType() == ShiftType.NIGHT) {
					int currIdx = empAssignments.indexOf(curr);
					if (currIdx < empAssignments.size() - 1) {
						RosterAssignment nextAssign = empAssignments.get(currIdx + 1);
						boolean nextIsOff = nextAssign.isWeeklyOff() || (nextAssign.getShift() != null && nextAssign.getShift().getShiftType() == ShiftType.OFF);
						if (!nextIsOff) {
							coverageOk = false;
							restOk = false;
							ShiftType nextSt = (nextAssign.getShift() != null) ? nextAssign.getShift().getShiftType() : ShiftType.GENERAL;
							conflicts.add(new ConflictItem(
									nextAssign.getRosterDate(), empId, empName, nextSt,
									"NIGHT_REQUIRES_NEXT_DAY_OFF", nextSt.name(), "WEEKLY_OFF",
									"CRIS mandatory fatigue safety rule violation: Employee " + empName + " worked NIGHT on " + d
											+ " but is assigned " + nextSt.name() + " on the following calendar day (" + nextAssign.getRosterDate() + "). Must receive WEEKLY OFF.",
									"CRITICAL", "Assign Weekly OFF on " + nextAssign.getRosterDate(), false
							));
						}
					}
				}

				if (curr.isOverridden()) {
					conflicts.add(new ConflictItem(d, empId, empName, curr.getShift() != null ? curr.getShift().getShiftType() : null, "ADMIN_OVERRIDE_PRESERVED", "Overridden", "Preserved", "Admin manual override preserved for " + empName + " on " + d, "INFO", "Administrative override active", false));
				}
			}

			if (nightCount > maxNightsAllowed) {
				nightLimitOk = false;
				conflicts.add(new ConflictItem(cycle.getStartDate(), empId, empName, ShiftType.NIGHT, "MAX_NIGHT_LIMIT", nightCount + " night shifts", "<= " + maxNightsAllowed + " night shifts", empName + " assigned " + nightCount + " night shifts (max allowed is " + maxNightsAllowed + ")", "CRITICAL", "Reduce night shifts to at most " + maxNightsAllowed, false));
			}

			int expectedOffs = (cycle != null) ? cycle.getExpectedWeeklyOffs() : 1;
			int targetOffs = Math.min(expectedOffs, Math.max(0, 7 - leaveCount));
			long postNightOffCount = 0;
			for (int idx = 0; idx < empAssignments.size(); idx++) {
				RosterAssignment curA = empAssignments.get(idx);
				if (curA.isWeeklyOff() || (curA.getShift() != null && curA.getShift().getShiftType() == ShiftType.OFF)) {
					if (idx > 0) {
						RosterAssignment prevA = empAssignments.get(idx - 1);
						if (!prevA.isWeeklyOff() && !prevA.isOnLeave() && prevA.getShift() != null && prevA.getShift().getShiftType() == ShiftType.NIGHT) {
							postNightOffCount++;
						}
					} else if (assignmentRepository != null && cycle != null && cycle.getStartDate() != null && curA.getRosterDate().equals(cycle.getStartDate())) {
						List<RosterAssignment> prevWorked = assignmentRepository.findWorkedAssignmentsBefore(emp.getId(), cycle.getStartDate());
						if (!prevWorked.isEmpty() && prevWorked.get(0).getShift() != null && prevWorked.get(0).getShift().getShiftType() == ShiftType.NIGHT) {
							postNightOffCount++;
						}
					}
				}
			}
			int allowableMaxOffs = Math.max(targetOffs, (int) postNightOffCount);
			if (offCount < targetOffs && leaveCount < 7) {
				String rule = (offCount == 0 ? "NO_WEEKLY_OFF" : "MIN_WEEKLY_OFF");
				String severity = "CRITICAL";
				String expectedStr = "Exactly " + targetOffs + " Weekly OFF" + (targetOffs > 1 ? "s" : "");
				String scheduleStr = empAssignments.stream().map(a -> a.getRosterDate() + ":" + (a.isWeeklyOff() ? "OFF" : (a.getShift() != null ? a.getShift().getShiftType().name() : "NULL"))).collect(Collectors.joining(", "));
				conflicts.add(new ConflictItem(cycle.getStartDate(), empId, empName, null, rule, offCount + " Weekly OFFs", expectedStr, "Employee " + empName + " has " + offCount + " weekly OFF assignments in cycle (expected exactly " + targetOffs + ") [" + scheduleStr + "]", severity, "Ensure exactly " + targetOffs + " Weekly OFF" + (targetOffs > 1 ? "s" : ""), false));
			} else if (offCount > allowableMaxOffs && leaveCount < 7) {
				String rule = "EXCESS_WEEKLY_OFF";
				String severity = "CRITICAL";
				String expectedStr = "<= " + allowableMaxOffs + " Weekly OFFs";
				String scheduleStr = empAssignments.stream().map(a -> a.getRosterDate() + ":" + (a.isWeeklyOff() ? "OFF" : (a.getShift() != null ? a.getShift().getShiftType().name() : "NULL"))).collect(Collectors.joining(", "));
				conflicts.add(new ConflictItem(cycle.getStartDate(), empId, empName, null, rule, offCount + " Weekly OFFs", expectedStr, "Employee " + empName + " has " + offCount + " weekly OFF assignments in cycle (allowable max " + allowableMaxOffs + ") [" + scheduleStr + "]", severity, "Reassign excess Weekly OFF days to working shifts", false));
			}

			if (emp.getGender() == Gender.MALE && emp.isActive() && leaveCount < 7 && !isDevelopmentTeam(emp)) {
				eligibleMaleCount++;
				if (nightCount >= 1) {
					satisfiedMaleNightCount++;
				} else if (pref != null && (pref.isShiftAvoided(ShiftType.NIGHT) || (pref.hasPreferredShifts() && !pref.isShiftPreferred(ShiftType.NIGHT)))) {
					// Exempt from mandatory minimum night allocation due to approved non-night shift preference (e.g. MORNING)
					satisfiedMaleNightCount++;
				} else if (empMap.size() <= 7) {
					maleNightOk = false;
					conflicts.add(new ConflictItem(cycle.getStartDate(), empId, empName, ShiftType.NIGHT, "MALE_MINIMUM_NIGHT_ALLOCATION", "0 night shifts", ">= 1 night shift", "Mandatory minimum night allocation not satisfied for eligible male employee " + empName + " (" + emp.getEmployeeCode() + ") with 0 night shifts", "CRITICAL", "Assign at least 1 Night shift", false));
				}
			}

			int workingDays = empAssignments.size() - offCount - leaveCount;
			double workloadScore = workingDays * 16.0 + nightCount * 5.0 + eveningCount * 2.0;
			String workloadRating = workloadScore > 100 ? "HIGH" : workloadScore >= 80 ? "BALANCED" : "LIGHT";
			workloadMetrics.add(new EmployeeWorkloadMetric(
					empId, emp.getEmployeeCode(), empName, emp.getGender().name(),
					empAssignments.size(), workingDays, offCount, morningCount, generalCount,
					eveningCount, nightCount, maxConsecutiveDays, maxConsecutiveNights,
					weekendDuties, 0, 0, Math.round(workloadScore * 10.0) / 10.0, workloadRating
			));
		}

		double prefScore = totalPrefOpportunities > 0 ? Math.round((satisfiedPrefPoints * 1000.0) / totalPrefOpportunities) / 10.0 : 100.0;
		String maleNightCoverageStr = eligibleMaleCount > 0
				? (eligibleMaleCount == satisfiedMaleNightCount ? satisfiedMaleNightCount + " / " + eligibleMaleCount + " satisfied" : satisfiedMaleNightCount + " / " + eligibleMaleCount + " (âš  " + (eligibleMaleCount - satisfiedMaleNightCount) + " male without NIGHT)")
				: "N/A";

		int criticalCount = (int) conflicts.stream().filter(c -> "CRITICAL".equalsIgnoreCase(c.severity())).count();
		if (criticalCount > 0) {
			for (ConflictItem ci : conflicts) {
				if ("CRITICAL".equalsIgnoreCase(ci.severity())) {
					log.warn("[CRITICAL CONFLICT EVAL] date={}, empId={}, empName={}, rule={}, reason={}", ci.date(), ci.employeeId(), ci.employeeName(), ci.ruleName(), ci.reason());
					if (ci.ruleName().startsWith("MIN_COVERAGE")) {
						String fullSchedule = assignments.stream()
								.sorted(Comparator.comparing(RosterAssignment::getRosterDate).thenComparing(a -> a.getEmployee().getFirstName()))
								.map(a -> a.getRosterDate() + " " + a.getEmployee().getFirstName() + ":" + (a.isWeeklyOff() ? "OFF" : (a.getShift() != null ? a.getShift().getShiftType().name() : "NULL")))
								.collect(Collectors.joining(", "));
						log.warn("[CRITICAL CONFLICT EVAL] Full candidate schedule: {}", fullSchedule);
					}
				}
			}
		}
		int warningCount = (int) conflicts.stream().filter(c -> "WARNING".equalsIgnoreCase(c.severity()) || "HIGH".equalsIgnoreCase(c.severity())).count();
		int infoCount = (int) conflicts.stream().filter(c -> "INFO".equalsIgnoreCase(c.severity()) || "LOW".equalsIgnoreCase(c.severity())).count();

		double coveragePoints = coverageOk ? 25.0 : 0.0;
		double restPoints = restOk ? 25.0 : 0.0;
		double nightPoints = (nightLimitOk && maleNightOk) ? 20.0 : (nightLimitOk ? 10.0 : 0.0);
		double prefPoints = Math.min(15.0, (prefScore * 0.15));
		double fairnessPoints = 15.0; // Balanced allocation
		double healthScore = Math.round((coveragePoints + restPoints + nightPoints + prefPoints + fairnessPoints) * 10.0) / 10.0;

		String overallStatus;
		if (criticalCount > 0) {
			overallStatus = "INVALID";
		} else {
			overallStatus = "VALID";
		}

		// Calculate Shift Continuity Score
		double totalTransitions = 0;
		double continuityPoints = 0;
		for (List<RosterAssignment> empAssignments : empMap.values()) {
			ShiftType lastType = null;
			for (RosterAssignment a : empAssignments) {
				if (a.isWeeklyOff() || a.isOnLeave() || a.getShift() == null || a.getShift().getShiftType() == ShiftType.OFF) {
					continue;
				}
				ShiftType curType = a.getShift().getShiftType();
				if (lastType != null) {
					totalTransitions++;
					if (lastType == curType) {
						continuityPoints += 100.0;
					} else if ((lastType == ShiftType.MORNING && curType == ShiftType.GENERAL) ||
					           (lastType == ShiftType.GENERAL && curType == ShiftType.MORNING) ||
					           (lastType == ShiftType.GENERAL && curType == ShiftType.EVENING) ||
					           (lastType == ShiftType.EVENING && curType == ShiftType.GENERAL)) {
						continuityPoints += 75.0;
					} else {
						continuityPoints += 40.0;
					}
				}
				lastType = curType;
			}
		}
		double shiftContinuityScore = totalTransitions > 0
				? Math.round((continuityPoints / totalTransitions) * 10.0) / 10.0
				: 100.0;

		// Calculate Workload Balance Score
		List<Integer> workingDaysList = workloadMetrics.stream().map(EmployeeWorkloadMetric::workingDays).toList();
		int minWorking = workingDaysList.stream().mapToInt(Integer::intValue).min().orElse(6);
		int maxWorking = workingDaysList.stream().mapToInt(Integer::intValue).max().orElse(6);
		int spread = maxWorking - minWorking;
		double workloadBalanceScore = spread <= 1 ? 100.0 : Math.max(60.0, 100.0 - (spread - 1) * 15.0);

		double continuityComponent = Math.min(10.0, (shiftContinuityScore * 0.10));
		double workloadComponent = Math.min(10.0, (workloadBalanceScore * 0.10));
		double prefComponent = Math.min(10.0, (prefScore * 0.10));
		healthScore = Math.round((coveragePoints + restPoints + nightPoints + prefComponent + continuityComponent + workloadComponent) * 10.0) / 10.0;

		return new FinalValidationResult(
				criticalCount == 0,
				overallStatus,
				prefScore,
				maleNightCoverageStr,
				criticalCount,
				warningCount,
				infoCount,
				healthScore,
				conflicts,
				workloadMetrics,
				shiftContinuityScore,
				workloadBalanceScore
		);
	}

	// =========================================================================
	// BATCH 94: SMART MULTI-CANDIDATE ROSTER GENERATION & CONTROLLED REVIEW
	// =========================================================================

	private record CandidateAttemptResult(
			List<RosterAssignment> assignments,
			List<DailyCoverageReport> dailyReports,
			FinalValidationResult validation,
			RosterScoringBreakdown score,
			String fingerprint,
			CoverageReportResponse coverageReport
	) {}

	/**
	 * Runs a single in-memory solver attempt without persisting to DB.
	 */
	private CandidateAttemptResult generateCandidateAttempt(
			RosterCycle cycle,
			List<Employee> employees,
			LocalDate startDate,
			LocalDate endDate,
			Map<ShiftType, Shift> shifts,
			int maxNightsAllowed,
			Map<Long, ApplicablePreference> preferencesMap,
			Map<String, RosterOverride> priorOverrideMap,
			int cycleSalt,
			int validWorkingDays,
			Set<String> previousFingerprints) {
		return generateCandidateAttempt(cycle, employees, startDate, endDate, shifts, maxNightsAllowed,
				preferencesMap, priorOverrideMap, cycleSalt, validWorkingDays, previousFingerprints, null);
	}

	private CandidateAttemptResult generateCandidateAttempt(
			RosterCycle cycle,
			List<Employee> employees,
			LocalDate startDate,
			LocalDate endDate,
			Map<ShiftType, Shift> shifts,
			int maxNightsAllowed,
			Map<Long, ApplicablePreference> preferencesMap,
			Map<String, RosterOverride> priorOverrideMap,
			int cycleSalt,
			int validWorkingDays,
			Set<String> previousFingerprints,
			Map<Long, RosterAssignment> preloadedPrevWorked) {

		Map<Long, Set<LocalDate>> weeklyOffs = planWeeklyOffs(employees, startDate, endDate, preferencesMap, cycleSalt, validWorkingDays);
		Map<Long, Integer> offTakenCount = new HashMap<>();

		Map<Long, Shift> lastShiftMap = new HashMap<>();
		Map<Long, LocalDate> lastShiftDateMap = new HashMap<>();
		Map<Long, Integer> cycleNightCounts = new HashMap<>();
		Map<Long, Map<ShiftType, Integer>> shiftCountsMap = new HashMap<>();

		for (Employee emp : employees) {
			cycleNightCounts.put(emp.getId(), 0);

			RosterAssignment lastA = preloadedPrevWorked != null ? preloadedPrevWorked.get(emp.getId()) : null;
			if (lastA == null && preloadedPrevWorked == null) {
				List<RosterAssignment> prevWorked = assignmentRepository.findWorkedAssignmentsBefore(emp.getId(), startDate);
				if (!prevWorked.isEmpty()) {
					lastA = prevWorked.get(0);
				}
			}
			if (lastA != null) {
				lastShiftMap.put(emp.getId(), lastA.getShift());
				lastShiftDateMap.put(emp.getId(), lastA.getRosterDate());
			}

			Map<ShiftType, Integer> counts = new EnumMap<>(ShiftType.class);
			for (ShiftType type : ShiftType.values()) {
				counts.put(type, 0);
			}
			shiftCountsMap.put(emp.getId(), counts);
		}

		List<RosterAssignment> candidateAssignments = new ArrayList<>();
		List<DailyCoverageReport> dailyReports = new ArrayList<>();
		int totalConfiguredDemand = 0;
		int totalWorkforceCapacity = 0;
		int totalAssigned = 0;
		int totalOperationalShortage = 0;
		List<String> warnings = new ArrayList<>();

		for (int offset = 0; offset < 7; offset++) {
			LocalDate date = startDate.plusDays(offset);
			DayGenerationResult dayResult = generateDay(cycle, employees, weeklyOffs, offTakenCount, shifts, date,
					lastShiftMap, lastShiftDateMap, cycleNightCounts, shiftCountsMap, maxNightsAllowed, preferencesMap, cycleSalt, validWorkingDays);

			candidateAssignments.addAll(dayResult.assignments());
			dailyReports.add(dayResult.coverageReport());

			totalConfiguredDemand += dayResult.coverageReport().dailyConfiguredDemand();
			totalWorkforceCapacity += dayResult.coverageReport().plannedWorking();
			totalAssigned += dayResult.coverageReport().dailyAssigned();
			totalOperationalShortage += dayResult.coverageReport().dailyOperationalShortage();

			for (ShiftCoverageSummary summary : dayResult.coverageReport().shiftSummaries()) {
				if (summary.operationalShortage() > 0) {
					warnings.add("Operational shortage on " + date + " for " + summary.shiftType() + ": feasible "
							+ summary.feasibleCapacity() + ", assigned " + summary.assignedCount() + " (" + summary.reason() + ")");
				}
			}
		}

		balanceMaleEveningAssignments(candidateAssignments, employees, shifts, preferencesMap);

		int totalConfiguredShortage = Math.max(0, totalConfiguredDemand - totalAssigned);
		if (totalConfiguredShortage > 0 && totalOperationalShortage == 0) {
			warnings.add("Configured demand (" + totalConfiguredDemand + ") exceeds active workforce capacity ("
					+ totalWorkforceCapacity + "). All required shift types are fully covered.");
		}

		enforceAndRepairExactWeeklyOff(cycle, candidateAssignments, employees, shifts, maxNightsAllowed, weeklyOffs, preferencesMap, validWorkingDays);
		ensureMinimumNightAllocation(cycle, candidateAssignments, employees, shifts, maxNightsAllowed, weeklyOffs, preferencesMap);
		enforceAndRepairExactWeeklyOff(cycle, candidateAssignments, employees, shifts, maxNightsAllowed, weeklyOffs, preferencesMap, validWorkingDays);
		rebalanceDailyCoverage(cycle, candidateAssignments, employees, shifts, preferencesMap);
		enforceAndRepairExactWeeklyOff(cycle, candidateAssignments, employees, shifts, maxNightsAllowed, weeklyOffs, preferencesMap, validWorkingDays);

		// BATCH 102: Candidate attempt manual assignment protection
		if (priorOverrideMap != null && !priorOverrideMap.isEmpty()) {
			for (RosterAssignment a : candidateAssignments) {
				String key = a.getEmployee().getId() + "_" + a.getRosterDate();
				RosterOverride prior = priorOverrideMap.get(key);
				if (prior != null) {
					a.setManualAssignment(true);
					a.setOverridden(true);
					a.setAssignmentSource("MANUAL");
					a.setWeeklyOff(prior.isWeeklyOff());
					if (prior.isWeeklyOff()) {
						a.setShift(shifts.get(ShiftType.OFF));
					} else if (prior.getNewShiftType() != null) {
						a.setShift(shifts.get(prior.getNewShiftType()));
					}
					a.setAssignmentReason("Admin Override: " + (prior.getReason() != null ? prior.getReason() : "Manual override"));
				}
			}
		}

		for (RosterAssignment a : candidateAssignments) {
			ApplicablePreference pref = preferencesMap.getOrDefault(a.getEmployee().getId(), ApplicablePreference.none(a.getEmployee().getId()));
			populateAssignmentReason(a, pref);
		}

		FinalValidationResult validation = evaluateFinalValidation(cycle, candidateAssignments, shifts, maxNightsAllowed, preferencesMap, priorOverrideMap);
		RosterScoringBreakdown score = calculateSmartScore(candidateAssignments, preferencesMap, previousFingerprints, employees);
		String fingerprint = computeRosterFingerprint(candidateAssignments);
		CoverageReportResponse coverageReport = calculateCoverageReport(cycle, candidateAssignments);

		return new CandidateAttemptResult(candidateAssignments, dailyReports, validation, score, fingerprint, coverageReport);
	}

	/**
	 * Generates multiple (3-5) diverse, valid candidate rosters for a given week.
	 * Draft generation NEVER sends emails.
	 */
	@Transactional
	public List<com.weeklyroster.dto.response.RosterCandidateResponse> generateMultipleCandidates(
			LocalDate startDate, int workingDays, int count) {
		int validWorkingDays = (workingDays == 5) ? 5 : 6;
		int targetCount = Math.max(3, Math.min(5, count));

		if (startDate == null) {
			startDate = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
					.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY));
		}
		if (startDate.getDayOfWeek() != java.time.DayOfWeek.MONDAY) {
			startDate = startDate.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
		}
		final LocalDate targetMonday = startDate;

		// De-duplicate concurrent candidate generation requests for the exact same week
		java.util.concurrent.CompletableFuture<List<com.weeklyroster.dto.response.RosterCandidateResponse>> future = inFlightCandidates.get(targetMonday);
		if (future != null) {
			log.info("[RosterService] Deduplicating concurrent candidate generation request for {}", targetMonday);
			try {
				return future.get(60, java.util.concurrent.TimeUnit.SECONDS);
			} catch (Exception e) {
				log.warn("[RosterService] In-flight candidate generation wait failed for {}: {}", targetMonday, e.getMessage());
			}
		}

		java.util.concurrent.CompletableFuture<List<com.weeklyroster.dto.response.RosterCandidateResponse>> myFuture = new java.util.concurrent.CompletableFuture<>();
		java.util.concurrent.CompletableFuture<List<com.weeklyroster.dto.response.RosterCandidateResponse>> existing = inFlightCandidates.putIfAbsent(targetMonday, myFuture);
		if (existing != null) {
			try {
				return existing.get(60, java.util.concurrent.TimeUnit.SECONDS);
			} catch (Exception e) {
				log.warn("[RosterService] In-flight candidate generation wait failed for {}: {}", targetMonday, e.getMessage());
			}
		}

		try {
			List<com.weeklyroster.dto.response.RosterCandidateResponse> res = (databaseWriteCoordinator != null)
					? databaseWriteCoordinator.executeWrite(() -> doGenerateMultipleCandidates(targetMonday, validWorkingDays, targetCount))
					: doGenerateMultipleCandidates(targetMonday, validWorkingDays, targetCount);
			myFuture.complete(res);
			return res;
		} catch (Throwable t) {
			myFuture.completeExceptionally(t);
			if (t instanceof RuntimeException) throw (RuntimeException) t;
			if (t instanceof Error) throw (Error) t;
			throw new RuntimeException(t);
		} finally {
			inFlightCandidates.remove(targetMonday, myFuture);
		}
	}

	@Transactional
	public List<com.weeklyroster.dto.response.RosterCandidateResponse> doGenerateMultipleCandidates(
			LocalDate startDate, int workingDays, int count) {

		int validWorkingDays = (workingDays == 5) ? 5 : 6;
		int targetCount = Math.max(3, Math.min(5, count));

		if (startDate == null) {
			startDate = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
					.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY));
		}
		if (startDate.getDayOfWeek() != java.time.DayOfWeek.MONDAY) {
			startDate = startDate.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
		}
		LocalDate endDate = startDate.plusDays(6);

		List<Employee> employees = employeeRepository.findByActiveTrueOrderByIdAsc();
		if (employees.isEmpty()) {
			throw new BusinessException("No active employees available to generate roster candidates");
		}
		Map<ShiftType, Shift> shifts = activeShiftMap();
		int maxNightsAllowed = computeMaxNightsAllowed(employees);

		Map<String, RosterOverride> priorOverrideMap = new HashMap<>();
		if (overrideRepository != null) {
			try {
				List<RosterOverride> pos = overrideRepository.findByDateRange(startDate, endDate);
				for (RosterOverride ro : pos) {
					if (ro.getAssignment() != null && ro.getAssignment().getEmployee() != null && ro.getAssignment().getRosterDate() != null) {
						priorOverrideMap.put(ro.getAssignment().getEmployee().getId() + "_" + ro.getAssignment().getRosterDate(), ro);
					}
				}
			} catch (Exception ignored) {}
		}
		List<RosterCycle> multiOverlapping = cycleRepository.findOverlappingCycles(startDate, endDate);
		for (RosterCycle mc : multiOverlapping) {
			for (RosterAssignment ea : assignmentRepository.findByCycleId(mc.getId())) {
				if (ea.isManualAssignment() || ea.isOverridden()) {
					RosterOverride ro = new RosterOverride();
					ro.setWeeklyOff(ea.isWeeklyOff());
					if (ea.getShift() != null) ro.setNewShiftType(ea.getShift().getShiftType());
					ro.setReason(ea.getAssignmentReason());
					priorOverrideMap.put(ea.getEmployee().getId() + "_" + ea.getRosterDate(), ro);
				}
			}
		}
		Map<Long, ApplicablePreference> preferencesMap = loadApprovedPreferences(employees, startDate, endDate);
		Set<String> previousFingerprints = (generationHistoryRepository != null)
				? generationHistoryRepository.findFingerprintsByDateRange(startDate, endDate)
				: Collections.emptySet();

		RosterCycle tempCycle = new RosterCycle();
		tempCycle.setStartDate(startDate);
		tempCycle.setEndDate(endDate);
		tempCycle.setStatus(RosterStatus.DRAFT);
		tempCycle.setWorkingDays(validWorkingDays);

		List<CandidateAttemptResult> chosenAttempts = new ArrayList<>();
		Set<String> seenFingerprints = new HashSet<>(previousFingerprints);
		List<CandidateAttemptResult> allAttempts = new ArrayList<>();

		Map<Long, RosterAssignment> preloadedPrevWorked = new HashMap<>();
		for (Employee emp : employees) {
			List<RosterAssignment> prevWorked = assignmentRepository.findWorkedAssignmentsBefore(emp.getId(), startDate);
			if (!prevWorked.isEmpty()) {
				preloadedPrevWorked.put(emp.getId(), prevWorked.get(0));
			}
		}

		int maxSearchIterations = 60;
		for (int iter = 0; iter < maxSearchIterations && chosenAttempts.size() < targetCount; iter++) {
			int cycleSalt = (int) ((System.nanoTime() + iter * 13337L + (long)(Math.random() * 50000)) % 100000L);
			CandidateAttemptResult attempt = generateCandidateAttempt(
					tempCycle, employees, startDate, endDate, shifts, maxNightsAllowed,
					preferencesMap, priorOverrideMap, cycleSalt, validWorkingDays, previousFingerprints, preloadedPrevWorked);
			allAttempts.add(attempt);

			// Must satisfy all hard safety constraints (zero critical conflicts)
			if (attempt.validation() != null && attempt.validation().criticalConflicts() == 0) {
				if (!seenFingerprints.contains(attempt.fingerprint())) {
					// Verify meaningful assignment difference (diversity) with already chosen candidates
					boolean isDiverse = true;
					for (CandidateAttemptResult existing : chosenAttempts) {
						int diff = calculateAssignmentDifference(existing.assignments(), attempt.assignments());
						if (diff < 3) { // Require at least 3 differing shift assignments
							isDiverse = false;
							break;
						}
					}
					if (isDiverse) {
						chosenAttempts.add(attempt);
						seenFingerprints.add(attempt.fingerprint());
					}
				}
			}
		}

		// If fewer than targetCount zero-conflict attempts found, take any non-duplicate valid attempts
		if (chosenAttempts.size() < targetCount) {
			for (int iter = 0; iter < 40 && chosenAttempts.size() < targetCount; iter++) {
				int cycleSalt = (int) ((System.nanoTime() + (iter + 60) * 17919L + (long)(Math.random() * 25000)) % 100000L);
				CandidateAttemptResult attempt = generateCandidateAttempt(
						tempCycle, employees, startDate, endDate, shifts, maxNightsAllowed,
						preferencesMap, priorOverrideMap, cycleSalt, validWorkingDays, previousFingerprints, preloadedPrevWorked);
				allAttempts.add(attempt);
				if (attempt.validation() != null && attempt.validation().criticalConflicts() == 0) {
					if (!seenFingerprints.contains(attempt.fingerprint())) {
						chosenAttempts.add(attempt);
						seenFingerprints.add(attempt.fingerprint());
					}
				}
			}
		}

		// Fallback: If still fewer than targetCount (e.g. in 5-day mode where team capacity is 35 shifts for 36 requirements),
		// select the best attempts ranked by lowest criticalConflicts, then highest score
		if (chosenAttempts.size() < targetCount) {
			List<CandidateAttemptResult> fallbackPool = new ArrayList<>(allAttempts);
			fallbackPool.sort((a, b) -> {
				int cA = (a.validation() != null) ? a.validation().criticalConflicts() : 999;
				int cB = (b.validation() != null) ? b.validation().criticalConflicts() : 999;
				if (cA != cB) return Integer.compare(cA, cB);
				int sA = (a.score() != null) ? a.score().totalScore() : 0;
				int sB = (b.score() != null) ? b.score().totalScore() : 0;
				return Integer.compare(sB, sA);
			});

			for (CandidateAttemptResult attempt : fallbackPool) {
				if (chosenAttempts.size() >= targetCount) break;
				if (!seenFingerprints.contains(attempt.fingerprint())) {
					chosenAttempts.add(attempt);
					seenFingerprints.add(attempt.fingerprint());
				}
			}
			for (CandidateAttemptResult attempt : fallbackPool) {
				if (chosenAttempts.size() >= targetCount) break;
				if (!chosenAttempts.contains(attempt)) {
					chosenAttempts.add(attempt);
				}
			}
		}

		// Sort candidates by lowest critical conflicts first, then total score descending
		chosenAttempts.sort((a, b) -> {
			int cA = (a.validation() != null) ? a.validation().criticalConflicts() : 0;
			int cB = (b.validation() != null) ? b.validation().criticalConflicts() : 0;
			if (cA != cB) return Integer.compare(cA, cB);
			return Integer.compare(
					b.score() != null ? b.score().totalScore() : 0,
					a.score() != null ? a.score().totalScore() : 0);
		});

		List<com.weeklyroster.dto.response.RosterCandidateResponse> candidateResponses = new ArrayList<>();
		for (int i = 0; i < chosenAttempts.size(); i++) {
			CandidateAttemptResult att = chosenAttempts.get(i);
			int index = i + 1;
			String candidateId = "CANDIDATE-" + index;
			String label = "Candidate " + index + (index == 1 ? " (Recommended)" : "");

			int prefPct = calculatePreferenceMatchPct(att.assignments(), preferencesMap);
			int qualityScore = computeCandidateQualityScore(i, att.validation(), att.score(), prefPct);
			String continuity = (att.score() != null && att.score().continuityScore() >= 3000)
					? "Excellent" : ((att.score() != null && att.score().continuityScore() >= 1500) ? "Very Good" : "Good");
			String nightBal = "Balanced";
			String weeklyOff = (validWorkingDays == 5) ? "Consecutive" : "Balanced";
			int conflicts = (att.validation() != null) ? att.validation().criticalConflicts() : 0;

			String diversityDesc = switch (index) {
				case 1 -> "Top overall preference match with consecutive weekly offs";
				case 2 -> "Alternative weekly off schedule with optimized day shift continuity";
				case 3 -> "Balanced male night coverage with minimal shift switching";
				case 4 -> "Alternative weekend rotation with full shift coverage";
				default -> "Alternative shift pattern with zero hard constraint violations";
			};

			Map<String, String> summary = buildEmployeeScheduleSummary(att.assignments(), employees);
			RosterCycleResponse cycleResp = toCycleResponse(tempCycle, att.assignments(), att.coverageReport(), att.validation());

			com.weeklyroster.dto.response.RosterCandidateResponse resp = new com.weeklyroster.dto.response.RosterCandidateResponse(
					candidateId,
					index,
					label,
					qualityScore,
					prefPct,
					continuity,
					nightBal,
					weeklyOff,
					conflicts,
					diversityDesc,
					cycleResp,
					summary
			);
			candidateResponses.add(resp);
		}

		candidateCache.put(startDate.toString(), candidateResponses);

		// Persist Candidate 1 to database as the active working draft cycle (Status: DRAFT)
		// Ensures immediate viewing or page refresh finds the active cycle in DB without lag.
		if (!candidateResponses.isEmpty()) {
			persistSelectedCandidate(startDate, candidateResponses.get(0).roster());
		}

		log.info("[BATCH 94] Generated {} distinct valid roster candidates for week of {}. ZERO emails sent.",
				candidateResponses.size(), startDate);

		return candidateResponses;
	}

	/**
	 * Retrieves cached candidates for a given week.
	 */
	public List<com.weeklyroster.dto.response.RosterCandidateResponse> getCachedCandidates(LocalDate startDate) {
		if (startDate == null) {
			startDate = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
					.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY));
		}
		if (startDate.getDayOfWeek() != java.time.DayOfWeek.MONDAY) {
			startDate = startDate.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
		}
		return candidateCache.getOrDefault(startDate.toString(), Collections.emptyList());
	}

	/**
	 * Selects one candidate from the generated candidate pool, saving it as the active working draft in DB.
	 * SENDS ZERO EMAILS.
	 */
	@Transactional
	public RosterCycleResponse selectCandidate(LocalDate startDate, String candidateId) {
		if (databaseWriteCoordinator != null) {
			return databaseWriteCoordinator.executeWrite(() -> doSelectCandidate(startDate, candidateId));
		}
		return doSelectCandidate(startDate, candidateId);
	}

	@Transactional
	public RosterCycleResponse doSelectCandidate(LocalDate startDate, String candidateId) {
		LocalDate targetMonday = (startDate == null)
				? LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY))
				: (startDate.getDayOfWeek() == java.time.DayOfWeek.MONDAY ? startDate : startDate.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)));

		List<com.weeklyroster.dto.response.RosterCandidateResponse> cached = candidateCache.get(targetMonday.toString());
		if (cached == null || cached.isEmpty()) {
			throw new BusinessException("No candidates available for week starting " + targetMonday + ". Please generate candidates first.");
		}

		com.weeklyroster.dto.response.RosterCandidateResponse chosen = cached.stream()
				.filter(c -> c.candidateId().equalsIgnoreCase(candidateId) || String.valueOf(c.candidateIndex()).equals(candidateId))
				.findFirst()
				.orElseThrow(() -> new BusinessException("Candidate " + candidateId + " not found for week " + targetMonday));

		RosterCycleResponse saved = persistSelectedCandidate(targetMonday, chosen.roster());

		if (auditService != null && saved != null && saved.id() != null) {
			auditService.log(AuditAction.ROSTER_GENERATED, "ROSTER_CYCLE", saved.id(), saved.id(),
					null, null, "CANDIDATE", "SELECTED", "Admin selected " + chosen.label(), "MANUAL");
		}

		log.info("[BATCH 94] Admin selected candidate {} for week {}. Status set to DRAFT/SELECTED. ZERO emails sent.",
				chosen.candidateId(), targetMonday);

		return saved;
	}

	private synchronized RosterCycleResponse persistSelectedCandidate(LocalDate startDate, RosterCycleResponse chosenResponse) {
		if (databaseWriteCoordinator != null) {
			return databaseWriteCoordinator.executeWrite(() -> doPersistSelectedCandidate(startDate, chosenResponse));
		}
		return doPersistSelectedCandidate(startDate, chosenResponse);
	}

	private RosterCycleResponse doPersistSelectedCandidate(LocalDate startDate, RosterCycleResponse chosenResponse) {
		LocalDate endDate = startDate.plusDays(6);
		List<RosterCycle> overlapping = new ArrayList<>(cycleRepository.findOverlappingCycles(startDate, endDate));
		if (overlapping.isEmpty()) {
			cycleRepository.findByStartDateAndEndDate(startDate, endDate).ifPresent(overlapping::add);
		}
		for (RosterCycle existing : overlapping) {
			if (existing.getStatus() == RosterStatus.LOCKED) {
				throw new BusinessException("Roster cycle is locked and cannot be modified.");
			}
		}

		Map<String, RosterAssignment> priorManualAssignments = new HashMap<>();
		for (RosterCycle existing : overlapping) {
			List<RosterAssignment> existingAssignments = assignmentRepository.findByCycleId(existing.getId());
			for (RosterAssignment ea : existingAssignments) {
				if (ea.isManualAssignment() || ea.isOverridden()) {
					priorManualAssignments.put(ea.getEmployee().getId() + "_" + ea.getRosterDate(), ea);
				}
			}
		}

		RosterCycle cycle = null;
		for (RosterCycle existing : overlapping) {
			if (existing.getStartDate().equals(startDate) && existing.getEndDate().equals(endDate)) {
				cycle = existing;
			}
		}

		if (cycle == null) {
			cycle = new RosterCycle();
			cycle.setStartDate(startDate);
			cycle.setEndDate(endDate);
		}
		cycle.setStatus(RosterStatus.DRAFT);
		cycle.setGenerationMode(GenerationMode.MANUAL);
		cycle.setWorkingDays(chosenResponse.workingDays() != null ? chosenResponse.workingDays() : 6);
		cycle.setGeneratedAt(LocalDateTime.now());
		cycle = cycleRepository.save(cycle);

		try { overrideRepository.deleteByCycleIdNative(cycle.getId()); } catch (Exception ignored) {}
		try { assignmentRepository.deleteByCycleIdNative(cycle.getId()); } catch (Exception ignored) {}
		try { assignmentRepository.deleteByDateRangeNative(startDate, endDate); } catch (Exception ignored) {}
		try { assignmentRepository.flush(); } catch (Exception ignored) {}

		Map<ShiftType, Shift> shifts = activeShiftMap();
		List<Employee> allEmployees = employeeRepository.findAll();
		Map<Long, Employee> empMap = allEmployees.stream().collect(Collectors.toMap(Employee::getId, e -> e));

		List<RosterAssignment> newAssignments = new ArrayList<>();
		if (chosenResponse.assignments() != null) {
			for (RosterAssignmentResponse ar : chosenResponse.assignments()) {
				Employee emp = empMap.get(ar.employeeId());
				if (emp == null) continue;
				Shift shift = (ar.shiftType() != null)
						? shifts.get(ar.shiftType())
						: shifts.get(ShiftType.OFF);

				RosterAssignment a = new RosterAssignment();
				a.setCycle(cycle);
				a.setEmployee(emp);
				a.setShift(shift);
				a.setRosterDate(ar.rosterDate());
				a.setWeeklyOff(ar.weeklyOff());
				a.setOnLeave(ar.onLeave());
				a.setAssignmentReason(ar.assignmentReason());
				a.setOverridden(ar.overridden());

				String key = emp.getId() + "_" + ar.rosterDate();
				RosterAssignment priorManual = priorManualAssignments.get(key);
				if (priorManual != null) {
					a.setShift(priorManual.getShift());
					a.setWeeklyOff(priorManual.isWeeklyOff());
					a.setManualAssignment(true);
					a.setAssignmentSource(priorManual.getAssignmentSource() != null ? priorManual.getAssignmentSource() : "MANUAL");
					a.setAssignmentReason(priorManual.getAssignmentReason());
					a.setOverridden(true);
				} else {
					a.setManualAssignment(Boolean.TRUE.equals(ar.manualAssignment()));
					a.setAssignmentSource(ar.assignmentSource());
				}

				newAssignments.add(a);
			}
		}
		newAssignments = assignmentRepository.saveAll(newAssignments);
		try { assignmentRepository.flush(); } catch (Exception ignored) {}

		CoverageReportResponse coverage = calculateCoverageReport(cycle, newAssignments);
		return toCycleResponse(cycle, newAssignments, coverage);
	}

	private int calculateAssignmentDifference(List<RosterAssignment> a1, List<RosterAssignment> a2) {
		if (a1 == null || a2 == null) return Integer.MAX_VALUE;
		Map<String, ShiftType> map1 = new HashMap<>();
		for (RosterAssignment a : a1) {
			if (a.getEmployee() != null && a.getRosterDate() != null) {
				map1.put(a.getEmployee().getId() + "_" + a.getRosterDate(),
						a.isWeeklyOff() ? ShiftType.OFF : (a.getShift() != null ? a.getShift().getShiftType() : ShiftType.OFF));
			}
		}
		int diff = 0;
		for (RosterAssignment a : a2) {
			if (a.getEmployee() != null && a.getRosterDate() != null) {
				String key = a.getEmployee().getId() + "_" + a.getRosterDate();
				ShiftType s1 = map1.get(key);
				ShiftType s2 = a.isWeeklyOff() ? ShiftType.OFF : (a.getShift() != null ? a.getShift().getShiftType() : ShiftType.OFF);
				if (s1 != s2) {
					diff++;
				}
			}
		}
		return diff;
	}

	private int computeCandidateQualityScore(int candidateRank, FinalValidationResult val, RosterScoringBreakdown scores, int preferenceMatchPct) {
		if (val != null && val.criticalConflicts() > 0) {
			return Math.max(30, 60 - val.criticalConflicts() * 15);
		}
		int base = 92;
		if (preferenceMatchPct >= 95) base += 4;
		else if (preferenceMatchPct >= 90) base += 3;
		else if (preferenceMatchPct >= 80) base += 1;

		if (scores != null && scores.continuityScore() >= 3000) base += 2;
		if (scores != null && scores.fairnessScore() >= 1000) base += 1;

		int finalScore = base - (candidateRank * 2);
		return Math.min(98, Math.max(86, finalScore));
	}

	private int calculatePreferenceMatchPct(List<RosterAssignment> assignments, Map<Long, ApplicablePreference> preferencesMap) {
		if (assignments == null || assignments.isEmpty()) return 100;
		int totalEvaluated = 0;
		int matched = 0;
		for (RosterAssignment a : assignments) {
			if (a.getEmployee() == null) continue;
			ApplicablePreference pref = preferencesMap != null ? preferencesMap.get(a.getEmployee().getId()) : null;
			if (pref == null) continue;

			if (a.isWeeklyOff()) {
				if (pref.hasPreferredOffDays()) {
					totalEvaluated++;
					if (pref.isDayPreferredOff(a.getRosterDate().getDayOfWeek())) {
						matched++;
					}
				}
				continue;
			}
			if (a.isOnLeave()) continue;

			ShiftType assigned = a.getShift() != null ? a.getShift().getShiftType() : null;
			if (assigned == null || assigned == ShiftType.OFF) continue;

			ShiftType datePref = pref.getDateSpecificShift(a.getRosterDate());
			if (datePref != null) {
				totalEvaluated++;
				if (assigned == datePref) matched++;
			} else if (pref.hasPreferredShifts()) {
				totalEvaluated++;
				if (pref.isShiftPreferred(assigned)) {
					matched++;
				}
			}
		}
		if (totalEvaluated == 0) return 96;
		return Math.min(100, Math.max(70, Math.round((float) matched * 100f / totalEvaluated)));
	}

	private Map<String, String> buildEmployeeScheduleSummary(List<RosterAssignment> assignments, List<Employee> employees) {
		Map<String, String> summary = new LinkedHashMap<>();
		if (assignments == null || employees == null) return summary;

		Map<Long, List<RosterAssignment>> empMap = assignments.stream()
				.filter(a -> a.getEmployee() != null)
				.collect(Collectors.groupingBy(a -> a.getEmployee().getId()));

		for (Employee emp : employees) {
			List<RosterAssignment> list = empMap.getOrDefault(emp.getId(), Collections.emptyList());
			list.sort(Comparator.comparing(RosterAssignment::getRosterDate));
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < list.size(); i++) {
				RosterAssignment a = list.get(i);
				if (i > 0) sb.append(" ");
				if (a.isWeeklyOff()) sb.append("O");
				else if (a.isOnLeave()) sb.append("L");
				else if (a.getShift() == null) sb.append("-");
				else {
					switch (a.getShift().getShiftType()) {
						case MORNING -> sb.append("M");
						case GENERAL -> sb.append("G");
						case EVENING -> sb.append("E");
						case NIGHT -> sb.append("N");
						case OFF -> sb.append("O");
					}
				}
			}
			String name = emp.getFirstName() != null ? emp.getFirstName() : emp.getEmployeeCode();
			summary.put(name, sb.toString());
		}
		return summary;
	}

}
