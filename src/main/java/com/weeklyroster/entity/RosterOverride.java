package com.weeklyroster.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "roster_overrides")
public class RosterOverride {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	private RosterAssignment assignment;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private ShiftType previousShiftType;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private ShiftType newShiftType;

	@Column(nullable = false)
	private boolean weeklyOff;

	@Column(length = 500)
	private String reason;

	@Column(nullable = false)
	private LocalDateTime createdAt;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public RosterAssignment getAssignment() {
		return assignment;
	}

	public void setAssignment(RosterAssignment assignment) {
		this.assignment = assignment;
	}

	public ShiftType getPreviousShiftType() {
		return previousShiftType;
	}

	public void setPreviousShiftType(ShiftType previousShiftType) {
		this.previousShiftType = previousShiftType;
	}

	public ShiftType getNewShiftType() {
		return newShiftType;
	}

	public void setNewShiftType(ShiftType newShiftType) {
		this.newShiftType = newShiftType;
	}

	public boolean isWeeklyOff() {
		return weeklyOff;
	}

	public void setWeeklyOff(boolean weeklyOff) {
		this.weeklyOff = weeklyOff;
	}

	public String getReason() {
		return reason;
	}

	public void setReason(String reason) {
		this.reason = reason;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}
}
