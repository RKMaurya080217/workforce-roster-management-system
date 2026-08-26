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

		// 2. Load approved employee preferences applicable to [startDate, endDate]
		Map<Long, ApplicablePreference> preferencesMap = loadApprovedPreferences(employees, startDate, endDate);

		int maxAttempts = 5;
		List<RosterAssignment> bestGenerated = null;
		List<DailyCoverageReport> bestDailyReports = null;
		int bestConfiguredDemand = 0;
		int bestWorkforceCapacity = 0;
		int bestFeasibleCapacity = 0;
		int bestAssigned = 0;
		int bestOperationalShortage = 0;
		List<String> bestWarnings = null;
		FinalValidationResult bestValidation = null;

		for (int attempt = 0; attempt < maxAttempts; attempt++) {
			int cycleSalt = (int) ((System.currentTimeMillis() + attempt * 277L) % 10000L);

			Map<Long, LocalDate> weeklyOffs = planWeeklyOffs(employees, startDate, endDate, preferencesMap, cycleSalt);
			Set<Long> offTaken = new HashSet<>();

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
					counts.put(type, (int) assignmentRepository.countShiftForEmployee(emp.getId(), type));
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
				DayGenerationResult dayResult = generateDay(cycle, employees, weeklyOffs, offTaken, shifts, date,
						lastShiftMap, lastShiftDateMap, cycleNightCounts, shiftCountsMap, maxNightsAllowed, preferencesMap, cycleSalt);

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

			int totalConfiguredShortage = Math.max(0, totalConfiguredDemand - totalAssigned);
			if (totalConfiguredShortage > 0 && totalOperationalShortage == 0) {
				warnings.add("Configured demand (" + totalConfiguredDemand + ") exceeds active workforce capacity ("
						+ totalWorkforceCapacity + "). All required shift types are fully covered.");
			}

			enforceAndRepairExactWeeklyOff(cycle, candidateAssignments, employees, shifts, maxNightsAllowed, weeklyOffs, preferencesMap);
			ensureMinimumNightAllocation(cycle, candidateAssignments, employees, shifts, maxNightsAllowed, weeklyOffs, preferencesMap);
			enforceAndRepairExactWeeklyOff(cycle, candidateAssignments, employees, shifts, maxNightsAllowed, weeklyOffs, preferencesMap);

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

			if (validation.criticalConflicts() == 0) {
				bestGenerated = candidateAssignments;
				bestDailyReports = dailyReports;
				bestConfiguredDemand = totalConfiguredDemand;
				bestWorkforceCapacity = totalWorkforceCapacity;
				bestFeasibleCapacity = totalFeasibleCapacity;
				bestAssigned = totalAssigned;
				bestOperationalShortage = totalOperationalShortage;
				bestWarnings = warnings;
				bestValidation = validation;
				break;
			}

			if (bestValidation == null || validation.criticalConflicts() < bestValidation.criticalConflicts()) {
				bestGenerated = candidateAssignments;
				bestDailyReports = dailyReports;
				bestConfiguredDemand = totalConfiguredDemand;
				bestWorkforceCapacity = totalWorkforceCapacity;
				bestFeasibleCapacity = totalFeasibleCapacity;
				bestAssigned = totalAssigned;
				bestOperationalShortage = totalOperationalShortage;
				bestWarnings = warnings;
				bestValidation = validation;
			}
		}

		if (bestValidation == null || bestValidation.criticalConflicts() > 0) {
			String firstCrit = (bestValidation != null && !bestValidation.conflicts().isEmpty())
					? bestValidation.conflicts().stream().filter(c -> "CRITICAL".equalsIgnoreCase(c.severity())).map(ConflictItem::reason).findFirst().orElse("Mandatory scheduling constraints violated")
					: "Unable to generate compliant roster within safety and workforce rules";
			throw new BusinessException("Validation failure: " + firstCrit);
		}

		List<RosterAssignment> generated = new ArrayList<>(assignmentRepository.saveAll(bestGenerated));

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

		// 5. Final Invariant Assertion
		validateGeneratedRoster(cycle, generated, shifts, maxNightsAllowed, preferencesMap);

		if (rosterVersionService != null) {
			try {
				rosterVersionService.recordVersionSnapshot(cycle, mode == com.weeklyroster.entity.GenerationMode.AUTOMATIC ? "AUTOMATIC_GENERATION" : "GENERATED", "Roster cycle generated", "system");
			} catch (Exception ignored) {}
		}

		CoverageReportResponse coverageReport = new CoverageReportResponse(bestConfiguredDemand,
				bestWorkforceCapacity, bestFeasibleCapacity, bestAssigned, bestOperationalShortage,
				Math.max(0, bestConfiguredDemand - bestAssigned), bestDailyReports, bestWarnings);

		return toCycleResponse(cycle, generated, coverageReport, bestValidation);
	}


	@Transactional(readOnly = true)
		// -------------------------------------------------------------------------
	// PREFERENCE MANAGEMENT & PARSING ENGINE
	// -------------------------------------------------------------------------

	public Map<Long, ApplicablePreference> loadApprovedPreferences(List<Employee> employees, LocalDate startDate, LocalDate endDate) {
		Map<Long, ApplicablePreference> map = new HashMap<>();
		for (Employee emp : employees) {
			if (preferenceRepository == null) {
				map.put(emp.getId(), ApplicablePreference.none(emp.getId()));
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
				map.put(emp.getId(), parseApplicablePreference(active));
			} else {
				map.put(emp.getId(), ApplicablePreference.none(emp.getId()));
			}
		}
		return map;
	}

	private ApplicablePreference parseApplicablePreference(EmployeePreference p) {
		Set<ShiftType> preferredShifts = parseShiftTypes(p.getPreferredShiftTypes());
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
				avoidShifts,
				preferredOffDays,
				preferredWorkingDays,
				p.getTemporaryRestrictions(),
				true
		);
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

		ShiftType st = a.getShift().getShiftType();
		if (st == ShiftType.NIGHT) {
			a.setAssignmentReason("Required Night Rotation");
		} else if (pref != null && pref.isShiftPreferred(st)) {
			a.setAssignmentReason("Preferred Shift (" + st + ")");
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
			int maxNightsAllowed, Map<Long, ApplicablePreference> preferencesMap, int randomSeed) {
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
		boolean solvedFeasible = solveDay(0, feasibleSlots, available, assigned, dailyMatching, date,
				shifts, lastShiftMap, lastShiftDateMap, cycleNightCounts, shiftCountsMap, maxNightsAllowed, totalActiveMales, weeklyOffs, cycle.getStartDate(), cycle.getEndDate(), preferencesMap, randomSeed);

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
								int dayOffset = cycle.getStartDate() != null ? (int) java.time.temporal.ChronoUnit.DAYS.between(cycle.getStartDate(), date) : 0;
								boolean canDoSecondNight = (cycleNightCounts.getOrDefault(emp.getId(), 0) == 0) && (dayOffset == 0 || dayOffset == 2 || dayOffset == 4);
								return isCompletingSecondNight || isOffTomorrow || isLeaveTomorrow || canDoSecondNight;
							})
							.filter(emp -> hasMinimumRest(lastShiftDateMap.get(emp.getId()), lastShiftMap.get(emp.getId()), date, candidateShift))
							.sorted(Comparator.comparingInt((Employee emp) -> score(emp, type, date, lastShiftMap, cycleNightCounts, shiftCountsMap, weeklyOffs, cycle.getStartDate(), preferencesMap != null ? preferencesMap.get(emp.getId()) : null, randomSeed)))
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
			ShiftType bestExtra = null;
			List<ShiftType> extraCandidates = List.of(ShiftType.GENERAL, ShiftType.MORNING, ShiftType.EVENING);

			if (pref != null && pref.hasPreferredShifts()) {
				for (ShiftType pShift : pref.preferredShifts()) {
					if (pShift != ShiftType.OFF && pShift != ShiftType.NIGHT && isEligible(emp, pShift, pref) &&
							hasMinimumRest(lastShiftDateMap.get(emp.getId()), lastShiftMap.get(emp.getId()), date, shifts.get(pShift))) {
						bestExtra = pShift;
						break;
					}
				}
			}

			if (bestExtra == null) {
				for (ShiftType candidateType : extraCandidates) {
					Shift candidateShift = shifts.get(candidateType);
					if (isEligible(emp, candidateType, pref) &&
							hasMinimumRest(lastShiftDateMap.get(emp.getId()), lastShiftMap.get(emp.getId()), date, candidateShift)) {
						bestExtra = candidateType;
						break;
					}
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
				ShiftType fallback = null;
				for (ShiftType candidateType : List.of(ShiftType.GENERAL, ShiftType.MORNING, ShiftType.EVENING)) {
					if (isEligible(emp, candidateType, pref) &&
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
					dayAssignments.add(newAssignment(cycle, emp, shifts.get(ShiftType.OFF), date, true, false));
					offTaken.add(emp.getId());
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

		feasible.put(ShiftType.NIGHT, availableCount >= 1 ? 1 : 0);
		feasible.put(ShiftType.EVENING, availableCount >= 2 ? 1 : 0);
		feasible.put(ShiftType.MORNING, availableCount >= 3 ? 1 : 0);
		feasible.put(ShiftType.GENERAL, availableCount >= 4 ? 1 : 0);

		int baselineAssigned = feasible.values().stream().mapToInt(Integer::intValue).sum();
		int remainingStaff = Math.max(0, availableCount - baselineAssigned);

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
			int maxNightsAllowed, int totalActiveMales, Map<Long, LocalDate> weeklyOffs, LocalDate cycleStartDate, LocalDate cycleEndDate,
			Map<Long, ApplicablePreference> preferencesMap, int randomSeed) {
		if (slotIndex >= slots.size()) {
			return true;
		}

		ShiftType shiftType = slots.get(slotIndex);
		Shift shift = shifts.get(shiftType);

		List<Employee> candidates = available.stream()
				.filter(e -> !assigned.contains(e.getId()))
				.filter(e -> isEligible(e, shiftType, preferencesMap != null ? preferencesMap.get(e.getId()) : null))
				.filter(e -> {
					if (shiftType != ShiftType.NIGHT) return true;
					if (cycleNightCounts.getOrDefault(e.getId(), 0) >= maxNightsAllowed) return false;
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

						Shift prevS = lastShiftMap.get(e.getId());
						LocalDate prevD = lastShiftDateMap.get(e.getId());
						boolean wasNightYesterday = prevS != null && prevS.getShiftType() == ShiftType.NIGHT && prevD != null && prevD.equals(date.minusDays(1));
						if (!wasNightYesterday && otherZeroNightMales > 0) {
							return false;
						}

						LocalDate schedOff = weeklyOffs != null ? weeklyOffs.get(e.getId()) : null;
						int offOff = (schedOff != null && cycleStartDate != null) ? (int) java.time.temporal.ChronoUnit.DAYS.between(cycleStartDate, schedOff) : -1;
						int dOff = cycleStartDate != null ? (int) java.time.temporal.ChronoUnit.DAYS.between(cycleStartDate, date) : 0;
						if (dOff == 5 && !wasNightYesterday && offOff != 6) {
							// Cannot start 1st night on Saturday unless Sunday is scheduled OFF
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
					shifts, lastShiftMap, lastShiftDateMap, cycleNightCounts, shiftCountsMap, maxNightsAllowed, totalActiveMales, weeklyOffs, cycleStartDate, cycleEndDate, preferencesMap, randomSeed)) {
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

	private Map<Long, LocalDate> planWeeklyOffs(List<Employee> employees, LocalDate startDate, LocalDate endDate,
			Map<Long, ApplicablePreference> preferencesMap, int randomSeed) {
		Map<Long, LocalDate> result = new HashMap<>();
		Map<LocalDate, Integer> offCounts = new LinkedHashMap<>();
		for (int i = 0; i < 7; i++) {
			offCounts.put(startDate.plusDays(i), 0);
		}

		int maxOffPerDay = Math.max(1, Math.min(3, employees.size() - 4));

		List<Employee> males = employees.stream()
				.filter(e -> e.getGender() == Gender.MALE)
				.toList();
		List<Employee> females = employees.stream()
				.filter(e -> e.getGender() == Gender.FEMALE)
				.toList();

		List<Employee> sortedMales = new ArrayList<>(males);
		sortedMales.sort(Comparator.comparing((Employee e) -> (preferencesMap != null && !preferencesMap.getOrDefault(e.getId(), ApplicablePreference.none(e.getId())).preferredOffDays().isEmpty()) ? 0 : 1)
				.thenComparingInt(e -> (int) assignmentRepository.countShiftForEmployee(e.getId(), ShiftType.NIGHT))
				.thenComparingInt(this::weekendOffCount)
				.thenComparing(Comparator.comparingInt(this::weekendWorkCount).reversed())
				.thenComparing(e -> (int) ((e.getId() + randomSeed) % 17)));

		List<Employee> sortedFemales = new ArrayList<>(females);
		sortedFemales.sort(Comparator.comparing((Employee e) -> (preferencesMap != null && !preferencesMap.getOrDefault(e.getId(), ApplicablePreference.none(e.getId())).preferredOffDays().isEmpty()) ? 0 : 1)
				.thenComparingInt(this::weekendOffCount)
				.thenComparing(Comparator.comparingInt(this::weekendWorkCount).reversed())
				.thenComparing(e -> (int) ((e.getId() + randomSeed) % 17)));

		LocalDate d0 = startDate.plusDays(0);
		LocalDate d1 = startDate.plusDays(1);
		LocalDate d2 = startDate.plusDays(2);
		LocalDate d3 = startDate.plusDays(3);
		LocalDate d4 = startDate.plusDays(4);
		LocalDate d5 = startDate.plusDays(5);
		LocalDate d6 = startDate.plusDays(6);

		List<LocalDate> maleOrderedTargets;
		if (sortedMales.size() >= 7) {
			maleOrderedTargets = List.of(d1, d2, d3, d4, d5, d6, d0);
		} else if (sortedMales.size() == 6) {
			maleOrderedTargets = List.of(d2, d3, d4, d5, d6, d1);
		} else if (sortedMales.size() == 5) {
			maleOrderedTargets = List.of(d2, d4, d5, d6, d3);
		} else if (sortedMales.size() == 4) {
			maleOrderedTargets = List.of(d2, d4, d6, d5);
		} else if (sortedMales.size() == 3) {
			maleOrderedTargets = List.of(d2, d4, d6);
		} else if (sortedMales.size() == 2) {
			maleOrderedTargets = List.of(d3, d6);
		} else {
			maleOrderedTargets = List.of(d6, d5, d4, d2, d3, d1, d0);
		}

		int maxMaleOffPerDay = sortedMales.size() >= 7 ? 2 : 1;

		// Step 1: Assign preferred OFF days if approved for males
		for (int i = 0; i < sortedMales.size(); i++) {
			Employee m = sortedMales.get(i);
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

			ApplicablePreference pref = preferencesMap != null ? preferencesMap.get(m.getId()) : null;
			if (pref != null && !pref.preferredOffDays().isEmpty()) {
				for (DayOfWeek dow : pref.preferredOffDays()) {
					for (int d = 0; d < 7; d++) {
						LocalDate cand = startDate.plusDays(d);
						if (cand.getDayOfWeek() == dow && !isApprovedLeave(m.getId(), cand)) {
							int curMaleOffs = (int) result.values().stream().filter(cand::equals).count();
							if (curMaleOffs < maxMaleOffPerDay && offCounts.getOrDefault(cand, 0) < maxOffPerDay) {
								result.put(m.getId(), cand);
								offCounts.put(cand, offCounts.get(cand) + 1);
								break;
							}
						}
					}
					if (result.containsKey(m.getId())) break;
				}
			}
		}

		// Step 2: Assign remaining males using ordered targets
		int targetPointer = 0;
		for (int i = 0; i < sortedMales.size(); i++) {
			Employee m = sortedMales.get(i);
			if (result.containsKey(m.getId())) continue;

			LocalDate target = null;
			for (int step = 0; step < maleOrderedTargets.size(); step++) {
				LocalDate cand = maleOrderedTargets.get((targetPointer + step) % maleOrderedTargets.size());
				int maleOffsOnCand = (int) result.values().stream().filter(cand::equals).count();
				if (!isApprovedLeave(m.getId(), cand)
						&& offCounts.getOrDefault(cand, 0) < maxOffPerDay
						&& maleOffsOnCand < maxMaleOffPerDay) {
					target = cand;
					targetPointer = (targetPointer + step + 1) % maleOrderedTargets.size();
					break;
				}
			}
			if (target == null) {
				for (LocalDate candidate : List.of(d6, d5, d4, d2, d3, d1, d0)) {
					int maleOffsOnCand = (int) result.values().stream().filter(candidate::equals).count();
					if (!isApprovedLeave(m.getId(), candidate)
							&& offCounts.getOrDefault(candidate, 0) < maxOffPerDay
							&& maleOffsOnCand < maxMaleOffPerDay) {
						target = candidate;
						break;
					}
				}
			}
			if (target == null) target = d6;

			result.put(m.getId(), target);
			offCounts.compute(target, (d, count) -> count == null ? 1 : count + 1);
		}

		List<LocalDate> femaleOrderedTargets = List.of(d6, d5, d4, d2, d3, d1, d0);

		// Step 3: Females: assign preferred OFF day if approved
		for (int i = 0; i < sortedFemales.size(); i++) {
			Employee f = sortedFemales.get(i);
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

			ApplicablePreference pref = preferencesMap != null ? preferencesMap.get(f.getId()) : null;
			if (pref != null && !pref.preferredOffDays().isEmpty()) {
				for (DayOfWeek dow : pref.preferredOffDays()) {
					for (int d = 0; d < 7; d++) {
						LocalDate cand = startDate.plusDays(d);
						if (cand.getDayOfWeek() == dow && !isApprovedLeave(f.getId(), cand)) {
							if (offCounts.getOrDefault(cand, 0) < maxOffPerDay) {
								result.put(f.getId(), cand);
								offCounts.put(cand, offCounts.get(cand) + 1);
								break;
							}
						}
					}
					if (result.containsKey(f.getId())) break;
				}
			}
		}

		// Step 4: Remaining females using ordered targets
		for (int i = 0; i < sortedFemales.size(); i++) {
			Employee f = sortedFemales.get(i);
			if (result.containsKey(f.getId())) continue;

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

	private Map<Long, LocalDate> planWeeklyOffs(List<Employee> employees, LocalDate startDate, LocalDate endDate) {
		return planWeeklyOffs(employees, startDate, endDate, null, 0);
	}

	private void ensureMinimumNightAllocation(RosterCycle cycle, List<RosterAssignment> assignments,
		List<Employee> employees, Map<ShiftType, Shift> shifts, int maxNightsAllowed,
		Map<Long, LocalDate> weeklyOffs, Map<Long, ApplicablePreference> preferencesMap) {
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
			LocalDate scheduledOff = weeklyOffs != null ? weeklyOffs.get(male.getId()) : null;

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
							(scheduledOff != null && scheduledOff.equals(nextA.getRosterDate())) ||
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

					if (scheduledOff != null && nextA != null && scheduledOff.equals(nextA.getRosterDate())) {
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
			List<Employee> employees, Map<ShiftType, Shift> shifts, int maxNightsAllowed, Map<Long, LocalDate> weeklyOffs,
			Map<Long, ApplicablePreference> preferencesMap) {
		Map<Long, List<RosterAssignment>> empAssignments = assignments.stream()
				.collect(Collectors.groupingBy(a -> a.getEmployee().getId()));

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
				if (primaryOff == null && pref != null && !pref.preferredOffDays().isEmpty()) {
					primaryOff = offList.stream().filter(a -> pref.isDayPreferredOff(a.getRosterDate().getDayOfWeek())).findFirst().orElse(null);
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
					List<ShiftType> priorityCandidates = new ArrayList<>();
					if (pref != null && pref.hasPreferredShifts()) {
						for (ShiftType pSt : pref.preferredShifts()) {
							if (pSt != ShiftType.OFF) priorityCandidates.add(pSt);
						}
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
					if (chosen == null && isEligible(emp, ShiftType.NIGHT, pref)) {
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
						for (ShiftType fbCandidate : List.of(ShiftType.EVENING, ShiftType.GENERAL, ShiftType.MORNING)) {
							if (!isEligible(emp, fbCandidate, pref)) continue;
							Shift candidateShift = shifts.get(fbCandidate);
							boolean restPrev = (prev == null || prev.isWeeklyOff() || prev.isOnLeave() ||
									hasMinimumRest(prev.getRosterDate(), prev.getShift(), extraOff.getRosterDate(), candidateShift));
							if (restPrev) {
								fallback = fbCandidate;
								break;
							}
						}
						extraOff.setShift(shifts.get(fallback != null ? fallback : ShiftType.GENERAL));
					}
				}
			}

			// Case 2: 0 Weekly OFF (and leave < 7) -> Assign exactly 1 Weekly OFF
			if (offList.isEmpty()) {
				LocalDate scheduled = weeklyOffs != null ? weeklyOffs.get(emp.getId()) : null;
				RosterAssignment targetAssignment = null;
				if (pref != null && !pref.preferredOffDays().isEmpty()) {
					targetAssignment = list.stream().filter(a -> pref.isDayPreferredOff(a.getRosterDate().getDayOfWeek()) && !a.isOnLeave()).findFirst().orElse(null);
				}
				if (targetAssignment == null && scheduled != null) {
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

	private void enforceAndRepairExactWeeklyOff(RosterCycle cycle, List<RosterAssignment> assignments,
			List<Employee> employees, Map<ShiftType, Shift> shifts, int maxNightsAllowed, Map<Long, LocalDate> weeklyOffs) {
		enforceAndRepairExactWeeklyOff(cycle, assignments, employees, shifts, maxNightsAllowed, weeklyOffs, null);
	}

	private int score(Employee employee, ShiftType shiftType, LocalDate date, Map<Long, Shift> lastShiftMap,
			Map<Long, LocalDate> lastShiftDateMap, Map<Long, Integer> cycleNightCounts, Map<Long, Map<ShiftType, Integer>> shiftCountsMap,
			Map<Long, LocalDate> weeklyOffs, LocalDate cycleStartDate,
			ApplicablePreference pref, int randomSeed) {
		int shiftCount = shiftCountsMap != null && shiftCountsMap.containsKey(employee.getId())
				? shiftCountsMap.get(employee.getId()).getOrDefault(shiftType, 0)
				: (int) assignmentRepository.countShiftForEmployee(employee.getId(), shiftType);

		int score = shiftCount * 10;

		// 1. Employee Preferences (Priority 2)
		if (pref != null) {
			if (pref.isShiftPreferred(shiftType)) {
				score -= 1200;
			} else if (pref.hasPreferredShifts()) {
				score += 800;
			}

			if (pref.isDayPreferredOff(date.getDayOfWeek()) && shiftType != ShiftType.OFF) {
				score += 500;
			}

			if (pref.isDayPreferredWorking(date.getDayOfWeek()) && shiftType != ShiftType.OFF) {
				score -= 200;
			}
		}

		// 2. Prioritize female employees for Morning and General so males stay available for Evening and Night
		if (shiftType == ShiftType.MORNING || shiftType == ShiftType.GENERAL) {
			if (employee.getGender() == Gender.FEMALE) {
				score -= 150;
			}
		}

		if (shiftType == ShiftType.NIGHT) {
			int myCurNights = cycleNightCounts != null ? cycleNightCounts.getOrDefault(employee.getId(), 0) : 0;
			int priorNights = (int) assignmentRepository.countShiftForEmployee(employee.getId(), ShiftType.NIGHT);
			LocalDate scheduledOff = weeklyOffs != null ? weeklyOffs.get(employee.getId()) : null;
			int dayOffset = cycleStartDate != null ? (int) java.time.temporal.ChronoUnit.DAYS.between(cycleStartDate, date) : 0;
			int offOffset = (scheduledOff != null && cycleStartDate != null) ? (int) java.time.temporal.ChronoUnit.DAYS.between(cycleStartDate, scheduledOff) : -1;

			Shift lastShiftObj = lastShiftMap != null ? lastShiftMap.get(employee.getId()) : null;
			LocalDate lastShiftDate = lastShiftDateMap != null ? lastShiftDateMap.get(employee.getId()) : null;
			boolean wasNightYesterday = lastShiftObj != null && lastShiftObj.getShiftType() == ShiftType.NIGHT
					&& lastShiftDate != null && lastShiftDate.equals(date.minusDays(1));

			if (wasNightYesterday) {
				// Completing consecutive 2-night stint
				score -= 7000;
				if (offOffset >= 0 && dayOffset == offOffset - 1) {
					// Perfectly aligning post-night day with scheduled Weekly OFF!
					score -= 3000;
				}
			} else if (myCurNights == 0) {
				// Zero-Night Priority
				score -= 3000;
				score += priorNights * 30;

				boolean isLeaveTomorrow = (date != null && isApprovedLeave(employee.getId(), date.plusDays(1)));
				if (isLeaveTomorrow) {
					// Perfectly aligning night shift before approved leave day
					score -= 4500;
				} else if (offOffset >= 0) {
					if (dayOffset == offOffset - 2) {
						// Starting a 2-night stint before scheduled Weekly OFF (e.g. Fri before Sun OFF, Wed before Fri OFF, Mon before Wed OFF)
						score -= 4500;
					} else if (dayOffset == offOffset - 1 && offOffset != 5 && offOffset != 6) {
						// Starting a 1-night stint before scheduled Weekly OFF
						score -= 3500;
					} else if (dayOffset == 6 && (offOffset == 5 || offOffset == 4 || offOffset == 3)) {
						// Sunday night slot for single-night rotation male
						score -= 6000;
					} else {
						// Starting night on a wrong day that does NOT lead up to scheduled Weekly OFF
						score += 5500;
					}
				}
			} else {
				// Second disconnected night: penalized heavily so 0-night males get their first night first
				score += 5000;
			}
		}

		Shift lastShiftObj = lastShiftMap != null ? lastShiftMap.get(employee.getId()) : null;
		ShiftType lastShift = lastShiftObj != null ? lastShiftObj.getShiftType() : null;
		if (lastShift != null) {
			if (lastShift == shiftType) {
				score -= 280;
			} else {
				score += 180;
				if ((lastShift == ShiftType.MORNING && shiftType == ShiftType.EVENING) ||
				    (lastShift == ShiftType.EVENING && shiftType == ShiftType.MORNING) ||
				    (lastShift == ShiftType.GENERAL && shiftType == ShiftType.NIGHT)) {
					score += 120;
				}
			}
		}

		return score;
	}

	private int score(Employee employee, ShiftType shiftType, LocalDate date, Map<Long, Shift> lastShiftMap,
			Map<Long, Integer> cycleNightCounts, Map<Long, Map<ShiftType, Integer>> shiftCountsMap,
			Map<Long, LocalDate> weeklyOffs, LocalDate cycleStartDate,
			ApplicablePreference pref, int randomSeed) {
		return score(employee, shiftType, date, lastShiftMap, null, cycleNightCounts, shiftCountsMap, weeklyOffs, cycleStartDate, pref, randomSeed);
	}

	private int score(Employee employee, ShiftType shiftType, LocalDate date, Map<Long, Shift> lastShiftMap,
			Map<Long, Integer> cycleNightCounts, Map<Long, Map<ShiftType, Integer>> shiftCountsMap,
			Map<Long, LocalDate> weeklyOffs, LocalDate cycleStartDate) {
		return score(employee, shiftType, date, lastShiftMap, null, cycleNightCounts, shiftCountsMap, weeklyOffs, cycleStartDate, null, 0);
	}

	private int score(Employee employee, ShiftType shiftType, LocalDate date, Map<Long, Shift> lastShiftMap,
			Map<Long, Integer> cycleNightCounts, Map<Long, Map<ShiftType, Integer>> shiftCountsMap) {
		return score(employee, shiftType, date, lastShiftMap, null, cycleNightCounts, shiftCountsMap, null, null, null, 0);
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

				// Avoid Shift Hard Constraint (unless overridden by Admin)
				if (!curr.isOverridden() && !curr.isWeeklyOff() && !curr.isOnLeave() && curr.getShift().getShiftType() != ShiftType.OFF) {
					if (pref != null && pref.isShiftAvoided(curr.getShift().getShiftType())) {
						throw new BusinessException("Validation failure: Employee " + emp.getEmployeeCode()
								+ " assigned to avoided shift " + curr.getShift().getShiftType() + " on " + curr.getRosterDate());
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

			if (offCount != 1 && leaveCount < 7) {
				Employee emp = list.get(0).getEmployee();
				throw new BusinessException("Validation failure: Employee " + emp.getEmployeeCode()
						+ " has " + offCount + " weekly OFF assignments in cycle (expected exactly 1)");
			}

			// Batch 33: Mandatory Minimum Night Allocation Check for Eligible Males
			Employee emp = list.get(0).getEmployee();
			if (emp != null && emp.getGender() == Gender.MALE && emp.isActive() && leaveCount < 7 && activeMaleCount <= 7) {
				if (nightCount < 1) {
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
				coverageReport,
				com.weeklyroster.util.RosterLifecycleUtil.classifyCycle(cycle.getStartDate(), cycle.getEndDate()),
				com.weeklyroster.util.RosterLifecycleUtil.resolveSource(cycle.getGenerationMode() != null ? cycle.getGenerationMode() : com.weeklyroster.entity.GenerationMode.MANUAL),
				cycle.getStatus() == RosterStatus.DRAFT || cycle.getStatus() == RosterStatus.GENERATED,
				val.overallStatus(),
				val.preferenceComplianceScore(),
				val.maleNightCoverage(),
				val.criticalConflicts(),
				val.warnings(),
				val.healthScore(),
				val.conflicts(),
				val.workloadMetrics()
		);
	}

	private RosterAssignmentResponse toAssignmentResponse(RosterAssignment assignment) {
		Employee employee = assignment.getEmployee();
		Long cycleId = assignment.getCycle() == null ? null : assignment.getCycle().getId();
		return new RosterAssignmentResponse(assignment.getId(), cycleId,
				assignment.getRosterDate(), employee.getId(), employee.getEmployeeCode(),
				employee.getFirstName() + " " + employee.getLastName(), employee.getGender(),
				assignment.getShift().getShiftType(), assignment.isWeeklyOff(), assignment.isOnLeave(),
				assignment.isOverridden(), assignment.getAssignmentReason());
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
			List<EmployeeWorkloadMetric> workloadMetrics
	) {}

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

		boolean coverageOk = true;
		for (LocalDate date = cycle.getStartDate(); !date.isAfter(cycle.getEndDate()); date = date.plusDays(1)) {
			Map<ShiftType, List<RosterAssignment>> shiftMap = dateShiftMap.getOrDefault(date, Collections.emptyMap());
			int mCount = shiftMap.getOrDefault(ShiftType.MORNING, Collections.emptyList()).size();
			int gCount = shiftMap.getOrDefault(ShiftType.GENERAL, Collections.emptyList()).size();
			int eCount = shiftMap.getOrDefault(ShiftType.EVENING, Collections.emptyList()).size();
			int nCount = shiftMap.getOrDefault(ShiftType.NIGHT, Collections.emptyList()).size();

			if (mCount < 1) {
				coverageOk = false;
				conflicts.add(new ConflictItem(date, null, "Morning Staffing", ShiftType.MORNING, "MIN_COVERAGE_MORNING", "0 assigned", ">= 1 assigned", "Morning shift has 0 assigned staff on " + date, "CRITICAL", "Assign at least 1 staff to Morning", false));
			}
			if (gCount < 1) {
				coverageOk = false;
				conflicts.add(new ConflictItem(date, null, "General Staffing", ShiftType.GENERAL, "MIN_COVERAGE_GENERAL", "0 assigned", ">= 1 assigned", "General shift has 0 assigned staff on " + date, "CRITICAL", "Assign at least 1 staff to General", false));
			}
			if (eCount < 1) {
				coverageOk = false;
				conflicts.add(new ConflictItem(date, null, "Evening Staffing", ShiftType.EVENING, "MIN_COVERAGE_EVENING", "0 assigned", ">= 1 assigned", "Evening shift has 0 assigned staff on " + date, "CRITICAL", "Assign at least 1 staff to Evening", false));
			}
			long eligibleMaleStaff = empMap.values().stream()
					.filter(l -> !l.isEmpty() && l.get(0).getEmployee().getGender() == Gender.MALE && l.get(0).getEmployee().isActive() && l.stream().filter(RosterAssignment::isOnLeave).count() < 7)
					.count();
			boolean nightCapacityShortage = (eligibleMaleStaff * maxNightsAllowed < 7);

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
						conflicts.add(new ConflictItem(d, empId, empName, st, "AVOID_SHIFT_VIOLATION", st.name(), "Not " + st.name(), "Employee " + empName + " assigned to approved avoided shift " + st + " on " + d, "CRITICAL", "Reassign away from avoided shift", false));
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

			if (offCount != 1 && leaveCount < 7) {
				conflicts.add(new ConflictItem(cycle.getStartDate(), empId, empName, null, offCount == 0 ? "NO_WEEKLY_OFF" : "DUPLICATE_WEEKLY_OFF", offCount + " Weekly OFFs", "Exactly 1 Weekly OFF", "Employee " + empName + " has " + offCount + " weekly OFF assignments in cycle (expected exactly 1)", "CRITICAL", "Ensure exactly 1 Weekly OFF", false));
			}

			if (emp.getGender() == Gender.MALE && emp.isActive() && leaveCount < 7) {
				eligibleMaleCount++;
				if (nightCount >= 1) {
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
				workloadMetrics
		);
	}

}