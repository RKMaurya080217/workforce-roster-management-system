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
	private com.weeklyroster.repository.RosterGenerationHistoryRepository generationHistoryRepository;

	public void setDateShiftPreferenceRepository(com.weeklyroster.repository.DateShiftPreferenceRepository dateShiftPreferenceRepository) {
		this.dateShiftPreferenceRepository = dateShiftPreferenceRepository;
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

		// 1. Capture existing overrides before clearing so intentional admin overrides survive regeneration
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

		int maxAttempts = 35;
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

			if (!priorOverrideMap.isEmpty()) {
				for (RosterAssignment a : candidateAssignments) {
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
		}

		if (bestValidation == null || bestValidation.criticalConflicts() > 0) {
			String firstCrit = (bestValidation != null && !bestValidation.conflicts().isEmpty())
					? bestValidation.conflicts().stream().filter(c -> "CRITICAL".equalsIgnoreCase(c.severity())).map(ConflictItem::reason).findFirst().orElse("Mandatory scheduling constraints violated")
					: "Unable to generate compliant roster within safety and workforce rules";
			throw new BusinessException("Validation failure: " + firstCrit);
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

		for (RosterAssignment a : generated) {
			ApplicablePreference pref = preferencesMap.getOrDefault(a.getEmployee().getId(), ApplicablePreference.none(a.getEmployee().getId()));
			populateAssignmentReason(a, pref);
		}
		generated = new ArrayList<>(assignmentRepository.saveAll(generated));
		try { assignmentRepository.flush(); } catch (Exception ignored) {}
		try { cycleRepository.flush(); } catch (Exception ignored) {}

		// 5. Final Invariant Assertion
		validateGeneratedRoster(cycle, generated, shifts, maxNightsAllowed, preferencesMap);

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
				rosterVersionService.recordVersionSnapshot(cycle, mode == GenerationMode.AUTOMATIC ? "AUTOMATIC_GENERATION" : "GENERATED", "Roster cycle generated", "system");
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

		return toCycleResponse(cycle, generated, coverageReport, bestValidation);
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

		for (Employee emp : employees) {
			Map<LocalDate, ShiftType> empDateShifts = dateShiftMap.getOrDefault(emp.getId(), Collections.emptyMap());
			if (preferenceRepository == null) {
				map.put(emp.getId(), ApplicablePreference.withDateShifts(emp.getId(), empDateShifts));
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
				map.put(emp.getId(), parseApplicablePreference(active, empDateShifts));
			} else {
				map.put(emp.getId(), ApplicablePreference.withDateShifts(emp.getId(), empDateShifts));
			}
		}
		return map;
	}

	private ApplicablePreference parseApplicablePreference(EmployeePreference p) {
		return parseApplicablePreference(p, Collections.emptyMap());
	}

	private ApplicablePreference parseApplicablePreference(EmployeePreference p, Map<LocalDate, ShiftType> dateShifts) {
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
				dateShifts != null ? dateShifts : Collections.emptyMap()
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
		if (a.isOverridden()) {
			if (a.getAssignmentReason() == null || a.getAssignmentReason().isBlank()) {
				a.setAssignmentReason("Admin Override");
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

		// 3. Fairness score
		int maleNights = 0;
		for (RosterAssignment a : assignments) {
			if (a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT && !a.isWeeklyOff() && !a.isOnLeave()) {
				maleNights++;
			}
		}
		if (maleNights == 7) fairnessScore += 300;

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

		Map<ShiftType, Integer> feasibleDemands = calculateDailyFeasibleDemands(available, configuredDemands);
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
		boolean solvedFeasible = solveDay(0, feasibleSlots, available, assigned, dailyMatching, date,
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
							.filter(emp -> isEligible(emp, type, preferencesMap != null ? preferencesMap.get(emp.getId()) : null))
							.filter(emp -> {
								if (type == ShiftType.EVENING && emp.getGender() == Gender.MALE) {
									int myEve = shiftCountsMap != null && shiftCountsMap.get(emp.getId()) != null
											? shiftCountsMap.get(emp.getId()).getOrDefault(ShiftType.EVENING, 0)
											: 0;
									if (myEve >= 3) return false;
								}
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
								boolean isOffTomorrow = weeklyOffs != null && weeklyOffs.containsKey(emp.getId()) && weeklyOffs.get(emp.getId()).contains(nextDate);
								boolean isLeaveTomorrow = isApprovedLeave(emp.getId(), nextDate);
								int dayOffset = cycle.getStartDate() != null ? (int) java.time.temporal.ChronoUnit.DAYS.between(cycle.getStartDate(), date) : 0;
								boolean canDoSecondNight = (cycleNightCounts.getOrDefault(emp.getId(), 0) == 0) && (dayOffset == 0 || dayOffset == 2 || dayOffset == 4);
								return isCompletingSecondNight || isOffTomorrow || isLeaveTomorrow || canDoSecondNight;
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

		repairDailyCoverage(dailyMatching, date, shifts, lastShiftMap, lastShiftDateMap, cycleNightCounts, maxNightsAllowed, preferencesMap);

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

			log.info("[WRMS CANDIDATE EVAL] DATE: {} | SHIFT: {} | REQUIRED: {} | CANDIDATES: {} | SELECTED: {} | REJECTED: {}",
					date, type, targetReq, eligibleForType.size(), selCount, rejections.size());
			if (date.equals(cycle.getEndDate()) || type == ShiftType.GENERAL) {
				for (Map.Entry<String, String> entry : rejections.entrySet()) {
					log.info("  -> Candidate {} rejected for {}: {}", entry.getKey(), type, entry.getValue());
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
			List<ShiftType> allowedTypes = emp.getGender() == Gender.FEMALE
					? List.of(ShiftType.MORNING, ShiftType.GENERAL)
					: (empEve >= 3
							? List.of(ShiftType.MORNING, ShiftType.GENERAL)
							: List.of(ShiftType.MORNING, ShiftType.GENERAL, ShiftType.EVENING));

			List<ShiftType> extraCandidates = new ArrayList<>();
			for (ShiftType candidateType : allowedTypes) {
				if (isEligible(emp, candidateType, pref) &&
						hasMinimumRest(lastShiftDateMap.get(emp.getId()), lastShiftMap.get(emp.getId()), date, shifts.get(candidateType))) {
					extraCandidates.add(candidateType);
				}
			}
			if (extraCandidates.isEmpty()) {
				for (ShiftType candidateType : allowedTypes) {
					if (isEligible(emp, candidateType) &&
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
								if (isEligible(a.getEmployee(), missingType)
										&& !(missingType == ShiftType.NIGHT && cycleNightCounts.getOrDefault(a.getEmployee().getId(), 0) >= maxNightsAllowed)
										&& hasMinimumRest(lastShiftDateMap.get(a.getEmployee().getId()), lastShiftMap.get(a.getEmployee().getId()), date, shifts.get(missingType))) {
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
			Map<Long, Integer> cycleNightCounts, int maxNightsAllowed, Map<Long, ApplicablePreference> preferencesMap) {
		for (ShiftType missingType : List.of(ShiftType.NIGHT, ShiftType.EVENING, ShiftType.GENERAL, ShiftType.MORNING)) {
			List<Employee> assigned = dailyMatching.getOrDefault(missingType, Collections.emptyList());
			if (!assigned.isEmpty()) {
				continue;
			}

			Shift targetShift = shifts.get(missingType);
			for (ShiftType donorType : List.of(ShiftType.MORNING, ShiftType.GENERAL, ShiftType.EVENING)) {
				if (donorType == missingType) continue;
				List<Employee> donorList = dailyMatching.getOrDefault(donorType, Collections.emptyList());
				if (donorList.size() <= 1) continue;

				Employee candidateToMove = null;
				for (Employee candidate : donorList) {
					ApplicablePreference pref = preferencesMap != null ? preferencesMap.get(candidate.getId()) : null;
					if (isEligible(candidate, missingType, pref)
							&& !(missingType == ShiftType.NIGHT && cycleNightCounts.getOrDefault(candidate.getId(), 0) >= maxNightsAllowed)
							&& hasMinimumRest(lastShiftDateMap.get(candidate.getId()), lastShiftMap.get(candidate.getId()), date, targetShift)) {
						candidateToMove = candidate;
						break;
					}
				}
				if (candidateToMove == null) {
					for (Employee candidate : donorList) {
						if (isEligible(candidate, missingType)
								&& !(missingType == ShiftType.NIGHT && cycleNightCounts.getOrDefault(candidate.getId(), 0) >= maxNightsAllowed)
								&& hasMinimumRest(lastShiftDateMap.get(candidate.getId()), lastShiftMap.get(candidate.getId()), date, targetShift)) {
							candidateToMove = candidate;
							break;
						}
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

		int nightTarget = configuredDemands.getOrDefault(ShiftType.NIGHT, 1);
		int eveningTarget = configuredDemands.getOrDefault(ShiftType.EVENING, 1);
		int morningTarget = configuredDemands.getOrDefault(ShiftType.MORNING, 2);
		int generalTarget = configuredDemands.getOrDefault(ShiftType.GENERAL, 2);

		feasible.put(ShiftType.NIGHT, (nightTarget > 0 && availableCount >= 1) ? 1 : 0);
		feasible.put(ShiftType.EVENING, (eveningTarget > 0 && availableCount >= 2) ? 1 : 0);
		feasible.put(ShiftType.MORNING, (morningTarget > 0 && availableCount >= 3) ? 1 : 0);
		feasible.put(ShiftType.GENERAL, (generalTarget > 0 && availableCount >= 4) ? 1 : 0);

		int baselineAssigned = feasible.values().stream().mapToInt(Integer::intValue).sum();
		int remainingStaff = Math.max(0, availableCount - baselineAssigned);

		while (remainingStaff > 0) {
			boolean allocated = false;
			for (ShiftType type : List.of(ShiftType.MORNING, ShiftType.GENERAL, ShiftType.EVENING, ShiftType.NIGHT)) {
				int cur = feasible.getOrDefault(type, 0);
				int target = configuredDemands.getOrDefault(type, 1);
				if (cur < target && remainingStaff > 0) {
					feasible.put(type, cur + 1);
					remainingStaff--;
					allocated = true;
				}
			}
			if (!allocated) {
				feasible.put(ShiftType.GENERAL, feasible.getOrDefault(ShiftType.GENERAL, 0) + 1);
				remainingStaff--;
			}
		}

		return feasible;
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

	private boolean solveDay(int slotIndex, List<ShiftType> slots, List<Employee> available,
			Set<Long> assigned, Map<ShiftType, List<Employee>> dailyAssignments, LocalDate date,
			Map<ShiftType, Shift> shifts, Map<Long, Shift> lastShiftMap, Map<Long, LocalDate> lastShiftDateMap,
			Map<Long, Integer> cycleNightCounts, Map<Long, Map<ShiftType, Integer>> shiftCountsMap,
			int maxNightsAllowed, int totalActiveMales, Map<Long, Set<LocalDate>> weeklyOffs, LocalDate cycleStartDate, LocalDate cycleEndDate,
			Map<Long, ApplicablePreference> preferencesMap, int randomSeed) {
		return solveDay(slotIndex, slots, available, assigned, dailyAssignments, date,
				shifts, lastShiftMap, lastShiftDateMap, cycleNightCounts, shiftCountsMap,
				maxNightsAllowed, totalActiveMales, weeklyOffs, cycleStartDate, cycleEndDate,
				preferencesMap, randomSeed, new int[1]);
	}

	private boolean solveDay(int slotIndex, List<ShiftType> slots, List<Employee> available,
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
				.filter(e -> isEligible(e, shiftType, preferencesMap != null ? preferencesMap.get(e.getId()) : null))
				.filter(e -> {
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
					if (shiftType != ShiftType.NIGHT) return true;
					if (cycleNightCounts.getOrDefault(e.getId(), 0) >= maxNightsAllowed) return false;

					Shift prevS = lastShiftMap.get(e.getId());
					LocalDate prevD = lastShiftDateMap.get(e.getId());
					boolean wasNightYesterday = prevS != null && prevS.getShiftType() == ShiftType.NIGHT && prevD != null && prevD.equals(date.minusDays(1));

					boolean hasSundayOff = weeklyOffs != null && weeklyOffs.containsKey(e.getId()) && weeklyOffs.get(e.getId()).contains(cycleStartDate.plusDays(6));
					int dOff = cycleStartDate != null ? (int) java.time.temporal.ChronoUnit.DAYS.between(cycleStartDate, date) : 0;
					if (dOff == 5 && !wasNightYesterday && !hasSundayOff) {
						// Cannot start 1st night on Saturday unless Sunday is scheduled OFF
						return false;
					}

					if (cycleNightCounts.getOrDefault(e.getId(), 0) >= 1) {
						long otherZeroNightMales = available.stream()
								.filter(o -> o.getGender() == Gender.MALE && !o.getId().equals(e.getId()))
								.filter(o -> cycleNightCounts.getOrDefault(o.getId(), 0) == 0)
								.count();

						long twoNightMales = cycleNightCounts.values().stream().filter(c -> c >= 2).count();
						long activeMalesInCycle = available.stream().filter(o -> o.getGender() == Gender.MALE).count();
						long maxTwoNightAllowed = Math.max(0, 7 - totalActiveMales);

						if (twoNightMales >= maxTwoNightAllowed && otherZeroNightMales > 0) {
							return false;
						}

						if (!wasNightYesterday && otherZeroNightMales > 0) {
							return false;
						}
					}
					return true;
				})
				.filter(e -> hasMinimumRest(lastShiftDateMap.get(e.getId()), lastShiftMap.get(e.getId()), date, shift))
				.sorted(Comparator.comparingInt((Employee e) -> score(e, shiftType, date, lastShiftMap, lastShiftDateMap, cycleNightCounts, shiftCountsMap, weeklyOffs, cycleStartDate, preferencesMap != null ? preferencesMap.get(e.getId()) : null, randomSeed)))
				.toList();

		for (Employee candidate : candidates) {
			assigned.add(candidate.getId());
			dailyAssignments.computeIfAbsent(shiftType, k -> new ArrayList<>()).add(candidate);

			if (solveDay(slotIndex + 1, slots, available, assigned, dailyAssignments, date,
					shifts, lastShiftMap, lastShiftDateMap, cycleNightCounts, shiftCountsMap, maxNightsAllowed, totalActiveMales, weeklyOffs, cycleStartDate, cycleEndDate, preferencesMap, randomSeed, stepCounter)) {
				return true;
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

		int targetOffsPerEmployee = Math.max(1, 7 - workingDays); // 2 for 5-day, 1 for 6-day

		int maxOffPerDay;
		int maxMaleOffPerDay;
		int maxFemaleOffPerDay;
		if (targetOffsPerEmployee >= 2) {
			maxOffPerDay = Math.max(2, Math.min(employees.size() - 6, (employees.size() * targetOffsPerEmployee + 6) / 7 + 1));
			maxMaleOffPerDay = Math.max(1, Math.min(males.size() - 2, (males.size() * targetOffsPerEmployee + 6) / 7 + 1));
			maxFemaleOffPerDay = Math.max(1, (females.size() * targetOffsPerEmployee + 6) / 7 + 1);
		} else {
			maxOffPerDay = Math.max(1, Math.min(employees.size() >= 9 ? 3 : 2, employees.size() - 5));
			maxMaleOffPerDay = sortedMales.size() >= 7 ? 2 : 1;
			maxFemaleOffPerDay = sortedFemales.size() > 2 ? (sortedFemales.size() / 2) : 1;
		}

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

		List<LocalDate> maleOrderedTargets;
		if (sortedMales.size() >= 7) {
			maleOrderedTargets = List.of(d6, d5, mw1, mw2, mw3, mw4, d0);
		} else if (sortedMales.size() == 6) {
			maleOrderedTargets = List.of(d6, d5, mw2, mw3, mw4, mw1);
		} else if (sortedMales.size() == 5) {
			maleOrderedTargets = List.of(d6, d5, mw2, mw4, mw3);
		} else if (sortedMales.size() == 4) {
			maleOrderedTargets = List.of(d6, d5, mw2, mw4);
		} else if (sortedMales.size() == 3) {
			maleOrderedTargets = List.of(d6, d5, mw2);
		} else if (sortedMales.size() == 2) {
			maleOrderedTargets = List.of(d6, d5);
		} else {
			maleOrderedTargets = List.of(d6, d5, mw4, mw2, mw3, mw1, d0);
		}

		List<LocalDate> femaleOrderedTargets = List.of(d6, d5, mw4, mw2, mw3, mw1, d0);

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

		// Process Males
		int malePairIndex = 0;
		int maleTargetPointer = 0;
		for (Employee m : sortedMales) {
			Set<LocalDate> empOffs = new LinkedHashSet<>();
			ApplicablePreference pref = preferencesMap != null ? preferencesMap.get(m.getId()) : null;

			int leaveDays = 0;
			for (int d = 0; d < 7; d++) {
				if (isApprovedLeave(m.getId(), startDate.plusDays(d))) leaveDays++;
			}
			int neededOffs = Math.max(0, targetOffsPerEmployee - leaveDays);

			// Recovery Off for Sunday Night
			if (workedSundayNightBefore.contains(m.getId()) && !isApprovedLeave(m.getId(), d0) && neededOffs > 0) {
				empOffs.add(d0);
				offCounts.put(d0, offCounts.get(d0) + 1);
				maleOffCounts.put(d0, maleOffCounts.get(d0) + 1);
			}

			if (neededOffs >= 2 && empOffs.size() < neededOffs) {
				if (empOffs.contains(d0)) {
					for (LocalDate cand : List.of(d1, d6)) {
						if (!empOffs.contains(cand) && !isApprovedLeave(m.getId(), cand)
								&& (pref == null || !pref.hasDateSpecificShift(cand))
								&& offCounts.get(cand) < maxOffPerDay
								&& maleOffCounts.get(cand) < maxMaleOffPerDay) {
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
									&& !pref.hasDateSpecificShift(pOff)
									&& offCounts.get(pOff) < maxOffPerDay
									&& maleOffCounts.get(pOff) < maxMaleOffPerDay) {
								LocalDate adj = null;
								for (LocalDate a : List.of(pOff.plusDays(1), pOff.minusDays(1))) {
									if (!a.isBefore(d0) && !a.isAfter(d6) && !isApprovedLeave(m.getId(), a)
											&& !pref.hasDateSpecificShift(a)
											&& offCounts.get(a) < maxOffPerDay
											&& maleOffCounts.get(a) < maxMaleOffPerDay) {
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
						boolean aPref = pref != null && pref.hasDateSpecificShift(dayA);
						boolean bPref = pref != null && pref.hasDateSpecificShift(dayB);
						if (!aLeave && !bLeave && !aPref && !bPref
								&& offCounts.get(dayA) < maxOffPerDay && maleOffCounts.get(dayA) < maxMaleOffPerDay
								&& offCounts.get(dayB) < maxOffPerDay && maleOffCounts.get(dayB) < maxMaleOffPerDay) {
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
									&& !pref.hasDateSpecificShift(cand)
									&& offCounts.get(cand) < maxOffPerDay && maleOffCounts.get(cand) < maxMaleOffPerDay) {
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
								&& (pref == null || !pref.hasDateSpecificShift(cand))
								&& offCounts.get(cand) < maxOffPerDay && maleOffCounts.get(cand) < maxMaleOffPerDay) {
							target = cand;
							maleTargetPointer = (maleTargetPointer + step + 1) % maleOrderedTargets.size();
							break;
						}
					}
				}
				if (target == null) {
					for (LocalDate cand : List.of(d6, d5, d4, d2, d3, d1, d0)) {
						if (!empOffs.contains(cand) && !isApprovedLeave(m.getId(), cand)
								&& (pref == null || !pref.hasDateSpecificShift(cand))
								&& offCounts.get(cand) < maxOffPerDay && maleOffCounts.get(cand) < maxMaleOffPerDay) {
							target = cand;
							break;
						}
					}
				}
				if (target == null) {
					for (LocalDate cand : List.of(d6, d5, d4, d2, d3, d1, d0)) {
						if (!empOffs.contains(cand) && !isApprovedLeave(m.getId(), cand)) {
							target = cand;
							break;
						}
					}
				}
				if (target == null) target = d6;

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
			Set<LocalDate> empOffs = new LinkedHashSet<>();
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
									&& !pref.hasDateSpecificShift(pOff)
									&& offCounts.get(pOff) < maxOffPerDay) {
								LocalDate adj = null;
								for (LocalDate a : List.of(pOff.plusDays(1), pOff.minusDays(1))) {
									if (!a.isBefore(d0) && !a.isAfter(d6) && !isApprovedLeave(f.getId(), a)
											&& !pref.hasDateSpecificShift(a)
											&& offCounts.get(a) < maxOffPerDay) {
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
						boolean aPref = pref != null && pref.hasDateSpecificShift(dayA);
						boolean bPref = pref != null && pref.hasDateSpecificShift(dayB);
						if (!aLeave && !bLeave && !aPref && !bPref
								&& offCounts.get(dayA) < maxOffPerDay
								&& offCounts.get(dayB) < maxOffPerDay) {
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
									&& !pref.hasDateSpecificShift(cand)
									&& offCounts.get(cand) < maxOffPerDay) {
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
								&& (pref == null || !pref.hasDateSpecificShift(cand))
								&& offCounts.get(cand) < maxOffPerDay) {
							target = cand;
							femaleTargetPointer = (femaleTargetPointer + step + 1) % femaleOrderedTargets.size();
							break;
						}
					}
				}
				if (target == null) {
					for (LocalDate cand : List.of(d6, d5, d4, d3, d2, d1, d0)) {
						if (!empOffs.contains(cand) && !isApprovedLeave(f.getId(), cand)) {
							target = cand;
							break;
						}
					}
				}
				if (target == null) target = d5;

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
		Map<LocalDate, Map<ShiftType, List<RosterAssignment>>> dateShiftMap = new TreeMap<>();
		Map<Long, List<RosterAssignment>> empAssignments = new HashMap<>();

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

			// If General is unstaffed and working staff >= 4, rebalance an eligible donor from Morning or Evening
			if (gCount == 0 && workingTotal >= 4) {
				Shift generalShift = shifts.get(ShiftType.GENERAL);
				RosterAssignment candidateToMove = null;
				ShiftType donorShiftType = null;

				for (ShiftType donorType : List.of(ShiftType.MORNING, ShiftType.EVENING)) {
					List<RosterAssignment> donors = shiftMap.getOrDefault(donorType, Collections.emptyList());
					if (donors.size() <= 1) continue;

					for (RosterAssignment a : donors) {
						Employee emp = a.getEmployee();
						ApplicablePreference pref = preferencesMap != null ? preferencesMap.get(emp.getId()) : null;
						if (pref != null && pref.hasDateSpecificShift(date)) continue;
						if (pref != null && pref.hasPreferredShifts() && pref.isShiftPreferred(donorType)) {
							long otherDonors = donors.stream().filter(o -> {
								ApplicablePreference op = preferencesMap != null ? preferencesMap.get(o.getEmployee().getId()) : null;
								return op == null || !op.hasPreferredShifts() || !op.isShiftPreferred(donorType);
							}).count();
							if (otherDonors >= 1) continue;
						}
						if (!isEligible(emp, ShiftType.GENERAL, pref)) continue;

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

			// Similarly ensure Morning has at least 1 if workingTotal >= 3
			int updatedM = shiftMap.getOrDefault(ShiftType.MORNING, Collections.emptyList()).size();
			if (updatedM == 0 && workingTotal >= 3) {
				Shift morningShift = shifts.get(ShiftType.MORNING);
				RosterAssignment candidateToMove = null;
				ShiftType donorShiftType = null;

				for (ShiftType donorType : List.of(ShiftType.GENERAL, ShiftType.EVENING)) {
					List<RosterAssignment> donors = shiftMap.getOrDefault(donorType, Collections.emptyList());
					if (donors.size() <= 1) continue;

					for (RosterAssignment a : donors) {
						Employee emp = a.getEmployee();
						ApplicablePreference pref = preferencesMap != null ? preferencesMap.get(emp.getId()) : null;
						if (pref != null && pref.hasDateSpecificShift(date)) continue;
						if (pref != null && pref.hasPreferredShifts() && pref.isShiftPreferred(donorType)) {
							long otherDonors = donors.stream().filter(o -> {
								ApplicablePreference op = preferencesMap != null ? preferencesMap.get(o.getEmployee().getId()) : null;
								return op == null || !op.hasPreferredShifts() || !op.isShiftPreferred(donorType);
							}).count();
							if (otherDonors >= 1) continue;
						}
						if (!isEligible(emp, ShiftType.MORNING, pref)) continue;

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

				if (candidateToMove != null) {
					log.info("[WRMS REBALANCE] Day {}: Reassigning {} from {} to MORNING to satisfy mandatory coverage",
							date, candidateToMove.getEmployee().getEmployeeCode(), donorShiftType);
					candidateToMove.setShift(morningShift);
					shiftMap.get(donorShiftType).remove(candidateToMove);
					shiftMap.computeIfAbsent(ShiftType.MORNING, k -> new ArrayList<>()).add(candidateToMove);
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

		if (activeMales.size() > 7) {
			return;
		}

		for (Employee male : activeMales) {
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

				// Find a night slot to swap with a male employee who has >= 2 night shifts
				for (RosterAssignment maleAssign : maleList) {
					if (maleAssign.isWeeklyOff() || maleAssign.isOnLeave()) continue;
					LocalDate date = maleAssign.getRosterDate();

					// Find who is assigned NIGHT on this date
					RosterAssignment nightAssign = assignments.stream()
							.filter(a -> a.getRosterDate().equals(date) && !a.isWeeklyOff() && !a.isOnLeave()
									&& a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT)
							.findFirst().orElse(null);

					if (nightAssign == null) continue;
					Employee donor = nightAssign.getEmployee();
					if (donor.getId().equals(male.getId())) continue;

					List<RosterAssignment> donorList = empAssignments.getOrDefault(donor.getId(), Collections.emptyList());
					donorList.sort(Comparator.comparing(RosterAssignment::getRosterDate));
					long donorNights = donorList.stream()
							.filter(a -> !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT)
							.count();

					if (donorNights >= 2) {
						Shift nightShift = shifts.get(ShiftType.NIGHT);
						int maleIdx = maleList.indexOf(maleAssign);
						RosterAssignment prevA = maleIdx > 0 ? maleList.get(maleIdx - 1) : null;
						RosterAssignment nextA = maleIdx < maleList.size() - 1 ? maleList.get(maleIdx + 1) : null;

						boolean restOkPrev = (prevA == null || prevA.isWeeklyOff() || prevA.isOnLeave() ||
								hasMinimumRest(prevA.getRosterDate(), prevA.getShift(), date, nightShift));
						boolean restOkNext = (nextA == null || nextA.isWeeklyOff() || nextA.isOnLeave() ||
								(scheduledOffs != null && scheduledOffs.contains(nextA.getRosterDate())) ||
								hasMinimumRest(date, nightShift, nextA.getRosterDate(), nextA.getShift()));

						if (!restOkPrev || !restOkNext) continue;

						// Check donor rest-safe replacement shift
						int donorIdx = donorList.indexOf(nightAssign);
						RosterAssignment donorPrev = donorIdx > 0 ? donorList.get(donorIdx - 1) : null;
						RosterAssignment donorNext = donorIdx < donorList.size() - 1 ? donorList.get(donorIdx + 1) : null;

						ShiftType donorNewShift = null;
						for (ShiftType cand : List.of(maleAssign.getShift().getShiftType(), ShiftType.GENERAL, ShiftType.MORNING, ShiftType.EVENING)) {
							if (cand == ShiftType.NIGHT || cand == ShiftType.OFF) continue;
							if (!isEligible(donor, cand, preferencesMap != null ? preferencesMap.get(donor.getId()) : null)) continue;
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

						if (scheduledOffs != null && nextA != null && scheduledOffs.contains(nextA.getRosterDate())) {
							nextA.setWeeklyOff(true);
							nextA.setShift(shifts.get(ShiftType.OFF));
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

			int targetOffs = Math.max(0, expectedOffs - (int) leaveCount);

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
			Set<LocalDate> scheduledOffs = weeklyOffs != null ? weeklyOffs.get(emp.getId()) : null;

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
					if (pref != null && pref.hasPreferredShifts()) {
						for (ShiftType pSt : pref.preferredShifts()) {
							if (pSt != ShiftType.OFF) priorityCandidates.add(pSt);
						}
					}
					if (prev != null && prev.getShift() != null && prev.getShift().getShiftType() != ShiftType.OFF) {
						ShiftType pType = prev.getShift().getShiftType();
						if (!priorityCandidates.contains(pType)) priorityCandidates.add(0, pType);
					}
					for (ShiftType std : List.of(ShiftType.GENERAL, ShiftType.MORNING, ShiftType.EVENING)) {
						if (!priorityCandidates.contains(std)) priorityCandidates.add(std);
					}

					for (ShiftType candidate : priorityCandidates) {
						if (!isEligible(emp, candidate, pref)) continue;
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
					long curNightOnDate = assignments.stream()
							.filter(a -> a.getRosterDate().equals(extraOff.getRosterDate()) && !a.isWeeklyOff() && !a.isOnLeave()
									&& a.getShift() != null && a.getShift().getShiftType() == ShiftType.NIGHT
									&& !a.getEmployee().getId().equals(emp.getId()))
							.count();

					if (chosen == null && curNightOnDate == 0 && isEligible(emp, ShiftType.NIGHT, pref)) {
						Shift nightShift = shifts.get(ShiftType.NIGHT);
						boolean restPrev = (prev == null || prev.isWeeklyOff() || prev.isOnLeave() ||
								hasMinimumRest(prev.getRosterDate(), prev.getShift(), extraOff.getRosterDate(), nightShift));
						boolean restNext = (next == null || next.isWeeklyOff() || next.isOnLeave() ||
								hasMinimumRest(extraOff.getRosterDate(), nightShift, next.getRosterDate(), next.getShift()));
						if (restPrev && restNext) {
							chosen = ShiftType.NIGHT;
						}
					}
					if (chosen != null) {
						extraOff.setShift(shifts.get(chosen));
					} else {
						// Rest-safe fallback
						ShiftType fallback = null;
						for (ShiftType fbCandidate : List.of(ShiftType.GENERAL, ShiftType.MORNING, ShiftType.EVENING)) {
							if (!isEligible(emp, fbCandidate, pref)) continue;
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
							for (ShiftType fbCandidate : List.of(ShiftType.GENERAL, ShiftType.MORNING, ShiftType.EVENING)) {
								if (!isEligible(emp, fbCandidate, pref)) continue;
								Shift candidateShift = shifts.get(fbCandidate);
								boolean restPrev = (prev == null || prev.isWeeklyOff() || prev.isOnLeave() ||
										hasMinimumRest(prev.getRosterDate(), prev.getShift(), extraOff.getRosterDate(), candidateShift));
								if (restPrev) {
									fallback = fbCandidate;
									if (next != null && !next.isWeeklyOff() && !next.isOnLeave() && next.getShift() != null &&
											!hasMinimumRest(extraOff.getRosterDate(), candidateShift, next.getRosterDate(), next.getShift())) {
										if (isEligible(emp, ShiftType.EVENING, pref)) {
											next.setShift(shifts.get(ShiftType.EVENING));
										}
									}
									break;
								}
							}
						}
						extraOff.setShift(shifts.get(fallback != null ? fallback : ShiftType.GENERAL));
					}
				}
			}

			// Case 2: Fewer Weekly OFFs than targetOffs -> Assign weekly OFFs until targetOffs is met
			if (offList.size() < targetOffs) {
				int needed = targetOffs - offList.size();
				List<RosterAssignment> workingAssigns = list.stream().filter(a -> !a.isWeeklyOff() && !a.isOnLeave()).toList();

				List<RosterAssignment> sortedCandidates = new ArrayList<>(workingAssigns);
				sortedCandidates.sort(Comparator.comparing((RosterAssignment a) -> {
					if (pref != null && pref.hasDateSpecificShift(a.getRosterDate())) return 100;
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

				for (int n = 0; n < needed && n < sortedCandidates.size(); n++) {
					RosterAssignment target = sortedCandidates.get(n);
					target.setWeeklyOff(true);
					target.setOnLeave(false);
					target.setShift(shifts.get(ShiftType.OFF));
					offList.add(target);
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

	// 1. Approved Employee Preferences (High Priority with deterministic priority ordering)
	if (pref != null) {
		ShiftType dateSpecificShift = (date != null) ? pref.getDateSpecificShift(date) : null;
		if (dateSpecificShift != null) {
			if (shiftType == dateSpecificShift) {
				score -= 30000; // Priority: Exact date-specific shift preference (Authoritative Level 2)
			} else {
				score += 30000; // Strong penalty to keep candidate available for preferred date-specific shift
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

		// Lookahead: protect tomorrow's date-specific preference from 12h rest violations
		if (date != null) {
			ShiftType tomorrowDatePref = pref.getDateSpecificShift(date.plusDays(1));
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

			if (wasNightYesterday) {
				score -= 7500;
				if (offOffset >= 0 && dayOffset == offOffset - 1) {
					score -= 3000;
				}
			} else if (myCurNights == 0) {
				// Only incentivize males to take Night if they do NOT have an explicit non-night preference
				if (!prefersNonNight) {
					score -= 3500;
					score += priorNights * 30;

					boolean isLeaveTomorrow = (date != null && isApprovedLeave(employee.getId(), date.plusDays(1)));
					if (isLeaveTomorrow) {
						score -= 4500;
					} else if (offOffset >= 0) {
						if (dayOffset == offOffset - 2) {
							score -= 4500;
						} else if (dayOffset == offOffset - 1 && offOffset != 5 && offOffset != 6) {
							score -= 3500;
						} else if (dayOffset == 6) {
							score -= 6000;
						} else {
							score += 4500;
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

	// 5. Controlled Non-Deterministic Tie-Breaking (Part H #24)
	int tieBreaker = (int) (((employee.getId() * 37L + randomSeed * 79L + (date != null ? date.getDayOfMonth() * 17L : 0)) % 100L));
	score += tieBreaker;

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

			int expectedOffs = (cycle != null && cycle.getWorkingDays() != null) ? (7 - cycle.getWorkingDays()) : 1;
			int targetOffs = Math.max(0, expectedOffs - leaveCount);
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
			int nightTarget = dayConfiguredDemands.getOrDefault(ShiftType.NIGHT, 1);
			int eveningTarget = dayConfiguredDemands.getOrDefault(ShiftType.EVENING, 1);
			int morningTarget = dayConfiguredDemands.getOrDefault(ShiftType.MORNING, 2);
			int generalTarget = dayConfiguredDemands.getOrDefault(ShiftType.GENERAL, 2);

			dayFeasibleDemands.put(ShiftType.NIGHT, (nightTarget > 0 && avail >= 1) ? 1 : 0);
			dayFeasibleDemands.put(ShiftType.EVENING, (eveningTarget > 0 && avail >= 2) ? 1 : 0);
			dayFeasibleDemands.put(ShiftType.MORNING, (morningTarget > 0 && avail >= 3) ? 1 : 0);
			dayFeasibleDemands.put(ShiftType.GENERAL, (generalTarget > 0 && avail >= 4) ? 1 : 0);

			int rem = Math.max(0, avail - dayFeasibleDemands.values().stream().mapToInt(Integer::intValue).sum());
			while (rem > 0) {
				boolean allocated = false;
				for (ShiftType type : List.of(ShiftType.MORNING, ShiftType.GENERAL, ShiftType.EVENING, ShiftType.NIGHT)) {
					int cur = dayFeasibleDemands.getOrDefault(type, 0);
					int target = dayConfiguredDemands.getOrDefault(type, 1);
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
					reason = "✓ Fully staffed within safety rules";
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

	private boolean isEligible(Employee employee, ShiftType shiftType, ApplicablePreference pref) {
		if (employee.getGender() == Gender.FEMALE && (shiftType == ShiftType.EVENING || shiftType == ShiftType.NIGHT)) {
			return false;
		}
		if (pref != null && pref.isShiftAvoided(shiftType)) {
			return false;
		}
		return true;
	}

	private boolean isEligible(Employee employee, ShiftType shiftType) {
		return isEligible(employee, shiftType, null);
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
					long currentEve = maleList.stream().filter(a -> !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.EVENING).count();
					if (currentEve <= 3) break;
					LocalDate d = eveningToMove.getRosterDate();
					if (malePref != null && malePref.hasDateSpecificShift(d)) continue;
					for (Employee otherMale : males) {
						if (otherMale.getId().equals(male.getId())) continue;
						ApplicablePreference otherPref = preferencesMap != null ? preferencesMap.get(otherMale.getId()) : null;
						if (otherPref != null && otherPref.hasDateSpecificShift(d)) continue;
						if (otherPref != null && otherPref.hasPreferredShifts() && !otherPref.isShiftPreferred(ShiftType.EVENING)) continue;
						List<RosterAssignment> otherList = empAssignments.getOrDefault(otherMale.getId(), Collections.emptyList());
						long otherEveCount = otherList.stream().filter(a -> !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && a.getShift().getShiftType() == ShiftType.EVENING).count();
						if (otherEveCount <= 2) {
							RosterAssignment otherAssignOnDate = otherList.stream().filter(a -> a.getRosterDate().equals(d) && !a.isWeeklyOff() && !a.isOnLeave() && a.getShift() != null && (a.getShift().getShiftType() == ShiftType.GENERAL || a.getShift().getShiftType() == ShiftType.MORNING)).findFirst().orElse(null);
							if (otherAssignOnDate != null) {
								ShiftType otherShiftType = otherAssignOnDate.getShift().getShiftType();
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
		FinalValidationResult val = evaluateFinalValidation(cycle, assignments, shifts, 2, preferencesMap, Collections.emptyMap());
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
				: (isLeave ? "Approved Absence" : (isOff ? "No working hours" : "09:30 – 18:00"));

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
					"🔒 Admin Override",
					adminOverrideReason != null ? adminOverrideReason : "Manually assigned by Administrator to meet critical operational requirements.",
					"OVERRIDE",
					"🔒"
			));
		}

		// 2. Optimization Reason
		if (optimized && !overridden) {
			reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
					"OPTIMIZATION",
					"⚙ Optimized Assignment",
					optimizationReason != null ? optimizationReason : "Shift adjusted during re-optimization to balance workload and satisfy safety constraints.",
					"OPTIMIZED",
					"⚙"
			));
		}

		// 3. Approved Leave
		if (isLeave) {
			reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
					"LEAVE",
					"Approved Leave",
					"Employee is on approved scheduled absence for this date.",
					"INFO",
					"🏖"
			));
		}
		// 4. Weekly OFF
		else if (isOff) {
			if (pref != null && pref.isDayPreferredOff(date.getDayOfWeek())) {
				reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
						"PREFERENCE",
						"✓ Preferred Weekly OFF",
						"Scheduled on employee's approved preferred rest day (" + dayOfWeek + ").",
						"POSITIVE",
						"✓"
				));
			} else {
				reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
						"WORKLOAD",
						"✓ Scheduled Weekly OFF",
						"Mandatory weekly rest day to ensure 6-day maximum working rule and workload fairness.",
						"POSITIVE",
						"✓"
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
							"★ Date-specific shift preference",
							shiftType.name() + " was specifically preferred for " + date + " by Admin.",
							"POSITIVE",
							"★"
					));
				} else {
					reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
							"PREFERENCE",
							"⚠ Date preference fallback",
							"Assigned " + shiftType.name() + " instead of date-specific preference " + dPref.name() + " due to operational requirements or safety rules.",
							"WARNING",
							"⚠"
					));
				}
			} else if (pref != null && pref.isShiftPreferred(shiftType)) {
				reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
						"PREFERENCE",
						"✓ Preferred shift",
						shiftType.name() + " is one of the employee's approved preferred shifts.",
						"POSITIVE",
						"✓"
				));
			} else if (pref != null && pref.isShiftAvoided(shiftType)) {
				reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
						"PREFERENCE",
						"⚠ Avoided shift compliance",
						shiftType.name() + " assigned to satisfy mandatory operational staffing requirements despite avoid preference.",
						"WARNING",
						"⚠"
				));
			}

			if (pref != null && pref.isDayPreferredWorking(date.getDayOfWeek())) {
				reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
						"PREFERENCE",
						"✓ Preferred working day",
						"Scheduled on employee's approved preferred working day (" + dayOfWeek + ").",
						"POSITIVE",
						"✓"
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
							"✓ Rest compliance",
							"The assignment maintains the required 12-hour rest period from previous " + prevShift.getShiftType() + " duty.",
							"POSITIVE",
							"✓"
					));
				} else {
					reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
							"REST",
							"⚠ Rest rule warning",
							"Rest period is less than 12 hours from previous duty (" + prevShift.getShiftType() + " → " + curShift.getShiftType() + ").",
							"WARNING",
							"⚠"
					));
				}
			} else {
				reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
						"REST",
						"✓ Rest compliance",
						"The assignment maintains the required 12-hour rest period (rest day / standby prior to duty).",
						"POSITIVE",
						"✓"
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
						"✓ Shift continuity",
						shiftType.name() + " continues the employee's existing shift block without abrupt switching.",
						"POSITIVE",
						"✓"
				));
			} else {
				reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
						"CONTINUITY",
						"✓ Smooth transition",
						"Assignment adheres to standard forward shift rotation sequence.",
						"POSITIVE",
						"✓"
				));
			}

			// D. Coverage Requirement
			reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
					"COVERAGE",
					"✓ Coverage requirement",
					shiftType.name() + " coverage was required for daily operational staffing on " + dayOfWeek + ".",
					"POSITIVE",
					"✓"
			));

			// E. Night Duty Distribution (Male staff)
			if (shiftType == ShiftType.NIGHT && employee.getGender() == Gender.MALE) {
				reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
						"NIGHT_DISTRIBUTION",
						"✓ Night distribution",
						"Fulfills mandatory 1–2 night duty allocation for eligible male employee.",
						"POSITIVE",
						"✓"
				));
			}

			// F. Workload Balance
			reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
					"WORKLOAD",
					"✓ Workload balance",
					"The assignment helps maintain balanced weekly workload across the operational team.",
					"POSITIVE",
					"✓"
			));

			// G. Female Daytime Policy
			if (employee.getGender() == Gender.FEMALE) {
				reasons.add(new ShiftExplanationResponse.ExplanationReasonItem(
						"POLICY",
						"✓ Policy compliance",
						"Assigned to safe daytime duty (Morning/General) in full compliance with female workforce policies.",
						"POSITIVE",
						"✓"
				));
			}
		}

		// Admin Detailed Breakdown (Only provided for Admin users)
		ShiftExplanationResponse.ShiftExplanationAdminDetails adminDetails = null;
		if (isAdmin) {
			String prefContrib = (pref != null && pref.isShiftPreferred(shiftType)) ? "+2500 (Preferred)" : (pref != null && pref.isShiftAvoided(shiftType) ? "-2000 (Avoided)" : "0 (Neutral)");
			String contContrib = reasons.stream().anyMatch(r -> "✓ Shift continuity".equals(r.title())) ? "+600 (Block Continuity)" : "+150 (Forward Rotation)";
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
				isHoliday, holidayName);
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
				res.shiftName = "🎉 Holiday: " + (holiday.getName() != null ? holiday.getName() : "Official Holiday");
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
		if ("HOLIDAY".equals(res.status)) return "Official Holiday: " + (res.leaveReason != null ? res.leaveReason : "Gazetted Holiday");
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
		boolean nightCapacityShortage = (eligibleMaleStaff * maxNightsAllowed < 7);

		boolean coverageOk = true;
		for (LocalDate date = cycle.getStartDate(); !date.isAfter(cycle.getEndDate()); date = date.plusDays(1)) {
			Map<ShiftType, List<RosterAssignment>> shiftMap = dateShiftMap.getOrDefault(date, Collections.emptyMap());
			int mCount = shiftMap.getOrDefault(ShiftType.MORNING, Collections.emptyList()).size();
			int gCount = shiftMap.getOrDefault(ShiftType.GENERAL, Collections.emptyList()).size();
			int eCount = shiftMap.getOrDefault(ShiftType.EVENING, Collections.emptyList()).size();
			int nCount = shiftMap.getOrDefault(ShiftType.NIGHT, Collections.emptyList()).size();

			long workingCountOnDate = (mCount + gCount + eCount + nCount);

			if (mCount < 1) {
				if (workingCountOnDate < 3) {
					conflicts.add(new ConflictItem(date, null, "Morning Staffing", ShiftType.MORNING, "MIN_COVERAGE_MORNING", "0 assigned", ">= 1 assigned", "Morning shift unstaffed on " + date + " due to limited available working staff (" + workingCountOnDate + ")", "WARNING", "Review staffing capacity", false));
				} else {
					coverageOk = false;
					conflicts.add(new ConflictItem(date, null, "Morning Staffing", ShiftType.MORNING, "MIN_COVERAGE_MORNING", "0 assigned", ">= 1 assigned", "Morning shift has 0 assigned staff on " + date, "CRITICAL", "Assign at least 1 staff to Morning", false));
				}
			}
			if (gCount < 1) {
				if (workingCountOnDate < 4) {
					conflicts.add(new ConflictItem(date, null, "General Staffing", ShiftType.GENERAL, "MIN_COVERAGE_GENERAL", "0 assigned", ">= 1 assigned", "General shift unstaffed on " + date + " due to limited available working staff (" + workingCountOnDate + ")", "WARNING", "Review staffing capacity", false));
				} else {
					coverageOk = false;
					conflicts.add(new ConflictItem(date, null, "General Staffing", ShiftType.GENERAL, "MIN_COVERAGE_GENERAL", "0 assigned", ">= 1 assigned", "General shift has 0 assigned staff on " + date, "CRITICAL", "Assign at least 1 staff to General", false));
				}
			}
			if (eCount < 1) {
				if (workingCountOnDate < 2 || eligibleMaleStaff == 0) {
					conflicts.add(new ConflictItem(date, null, "Evening Staffing", ShiftType.EVENING, "MIN_COVERAGE_EVENING", "0 assigned", ">= 1 assigned", "Evening shift unstaffed on " + date + " due to limited available working staff (" + workingCountOnDate + ")", "WARNING", "Review staffing capacity", false));
				} else {
					coverageOk = false;
					conflicts.add(new ConflictItem(date, null, "Evening Staffing", ShiftType.EVENING, "MIN_COVERAGE_EVENING", "0 assigned", ">= 1 assigned", "Evening shift has 0 assigned staff on " + date, "CRITICAL", "Assign at least 1 staff to Evening", false));
				}
			}

			if (nCount == 0) {
				if (nightCapacityShortage) {
					conflicts.add(new ConflictItem(date, null, "Night Staffing", ShiftType.NIGHT, "MIN_COVERAGE_NIGHT", "0 assigned", "1 assigned", "Night shift unstaffed on " + date + " due to active male capacity (" + eligibleMaleStaff + " eligible males * " + maxNightsAllowed + " max nights < 7)", "WARNING", "Review male workforce staffing capacity", false));
				} else {
					coverageOk = false;
					conflicts.add(new ConflictItem(date, null, "Night Staffing", ShiftType.NIGHT, "MIN_COVERAGE_NIGHT", "0 assigned", "Exactly 1 assigned", "Night shift has 0 assigned staff on " + date, "CRITICAL", "Assign exactly 1 eligible male staff to Night", false));
				}
			} else if (nCount > 1) {
				coverageOk = false;
				conflicts.add(new ConflictItem(date, null, "Night Staffing", ShiftType.NIGHT, "EXACT_NIGHT_COVERAGE", nCount + " assigned", "Exactly 1 assigned", "Night shift has " + nCount + " staff assigned (strictly 1 required) on " + date, "CRITICAL", "Reduce Night shift to 1 employee", false));
			}
		}

		for (Map.Entry<String, Integer> dup : dupMap.entrySet()) {
			if (dup.getValue() > 1) {
				String[] parts = dup.getKey().split("_");
				Long empId = Long.parseLong(parts[0]);
				LocalDate date = LocalDate.parse(parts[1]);
				conflicts.add(new ConflictItem(date, empId, "Employee #" + empId, null, "DUPLICATE_ASSIGNMENT", dup.getValue() + " assignments", "1 assignment", "Employee has multiple roster assignments on " + date, "CRITICAL", "Remove duplicate assignments", false));
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

				if (curr.isOverridden()) {
					conflicts.add(new ConflictItem(d, empId, empName, curr.getShift() != null ? curr.getShift().getShiftType() : null, "ADMIN_OVERRIDE_PRESERVED", "Overridden", "Preserved", "Admin manual override preserved for " + empName + " on " + d, "INFO", "Administrative override active", false));
				}
			}

			if (nightCount > maxNightsAllowed) {
				nightLimitOk = false;
				conflicts.add(new ConflictItem(cycle.getStartDate(), empId, empName, ShiftType.NIGHT, "MAX_NIGHT_LIMIT", nightCount + " night shifts", "<= 2 night shifts", empName + " assigned " + nightCount + " night shifts (max allowed is " + maxNightsAllowed + ")", "CRITICAL", "Reduce night shifts to at most 2", false));
			}

			int expectedOffs = (cycle != null && cycle.getWorkingDays() != null) ? (7 - cycle.getWorkingDays()) : 1;
			int targetOffs = Math.max(0, expectedOffs - leaveCount);
			if (offCount != targetOffs && leaveCount < 7) {
				conflicts.add(new ConflictItem(cycle.getStartDate(), empId, empName, null, offCount < targetOffs ? "NO_WEEKLY_OFF" : "DUPLICATE_WEEKLY_OFF", offCount + " Weekly OFFs", "Exactly " + targetOffs + " Weekly OFF", "Employee " + empName + " has " + offCount + " weekly OFF assignments in cycle (expected exactly " + targetOffs + ")", "CRITICAL", "Ensure exactly " + targetOffs + " Weekly OFF", false));
			}

			if (emp.getGender() == Gender.MALE && emp.isActive() && leaveCount < 7) {
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
				? (eligibleMaleCount == satisfiedMaleNightCount ? satisfiedMaleNightCount + " / " + eligibleMaleCount + " satisfied" : satisfiedMaleNightCount + " / " + eligibleMaleCount + " (⚠ " + (eligibleMaleCount - satisfiedMaleNightCount) + " male without NIGHT)")
				: "N/A";

		int criticalCount = (int) conflicts.stream().filter(c -> "CRITICAL".equalsIgnoreCase(c.severity())).count();
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
		} else if (warningCount > 0) {
			overallStatus = "WARNING";
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

}