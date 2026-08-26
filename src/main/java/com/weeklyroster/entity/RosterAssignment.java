package com.weeklyroster.entity;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "roster_assignments",
		uniqueConstraints = @UniqueConstraint(columnNames = { "employee_id", "roster_date" }),
		indexes = {
				@Index(name = "idx_assignment_roster_date", columnList = "rosterDate"),
				@Index(name = "idx_assignment_cycle_id", columnList = "cycle_id"),
				@Index(name = "idx_assignment_date_shift", columnList = "rosterDate, shift_id")
		})
public class RosterAssignment {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	private RosterCycle cycle;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	private Employee employee;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	private Shift shift;

	@Column(nullable = false)
	private LocalDate rosterDate;

	@Column(nullable = false)
	private boolean weeklyOff;

	@Column(nullable = false)
	private boolean onLeave;

	@Column(nullable = false)
	private boolean overridden;

	@Column(name = "assignment_reason", length = 255)
	private String assignmentReason;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public RosterCycle getCycle() {
		return cycle;
	}

	public void setCycle(RosterCycle cycle) {
		this.cycle = cycle;
	}

	public Employee getEmployee() {
		return employee;
	}

	public void setEmployee(Employee employee) {
		this.employee = employee;
	}

	public Shift getShift() {
		return shift;
	}

	public void setShift(Shift shift) {
		this.shift = shift;
	}

	public LocalDate getRosterDate() {
		return rosterDate;
	}

	public void setRosterDate(LocalDate rosterDate) {
		this.rosterDate = rosterDate;
	}

	public boolean isWeeklyOff() {
		return weeklyOff;
	}

	public void setWeeklyOff(boolean weeklyOff) {
		this.weeklyOff = weeklyOff;
	}

	public boolean isOnLeave() {
		return onLeave;
	}

	public void setOnLeave(boolean onLeave) {
		this.onLeave = onLeave;
	}

	public boolean isOverridden() {
		return overridden;
	}

	public void setOverridden(boolean overridden) {
		this.overridden = overridden;
	}

	public String getAssignmentReason() {
		return assignmentReason;
	}

	public void setAssignmentReason(String assignmentReason) {
		this.assignmentReason = assignmentReason;
	}
}
