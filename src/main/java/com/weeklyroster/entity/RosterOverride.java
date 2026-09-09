package com.weeklyroster.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

@Entity
@DiscriminatorValue("ROSTER_OVERRIDE")
public class RosterOverride extends SystemAuditLogEntry {

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "assignment_id", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private RosterAssignment assignment;

	@Enumerated(EnumType.STRING)
	@Column(name = "previous_shift_type", length = 30)
	private ShiftType previousShiftType;

	@Enumerated(EnumType.STRING)
	@Column(name = "new_shift_type", length = 30)
	private ShiftType newShiftType;

	@Column(name = "weekly_off")
	private Boolean weeklyOff = false;

	@Column(name = "reason", length = 500)
	private String reason;

	@Column(name = "created_at")
	private LocalDateTime createdAt = LocalDateTime.now();

	public RosterOverride() {}

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
		return weeklyOff != null && weeklyOff;
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
