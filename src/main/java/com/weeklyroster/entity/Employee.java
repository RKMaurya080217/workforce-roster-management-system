package com.weeklyroster.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;

@Entity
@Table(name = "employees")
public class Employee {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, length = 40)
	private String employeeCode;

	@Column(nullable = false, length = 80)
	private String firstName;

	@Column(nullable = false, length = 80)
	private String lastName;

	@Column(nullable = false, unique = true, length = 160)
	private String email;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Gender gender;

	@Column(nullable = false)
	private boolean active = true;

	@Column(length = 30)
	private String contactNumber;

	@Column(name = "joining_date")
	private LocalDate joiningDate;

	@Column(name = "resignation_date")
	private LocalDate resignationDate;

	@Column(length = 80)
	private String department = "OPERATIONS";

	@Column(name = "development_team", columnDefinition = "BOOLEAN DEFAULT 0")
	private boolean developmentTeam = false;

	@Column(name = "max_nights_per_week")
	private Integer maxNightsPerWeek;

	@Column(name = "paired_employee_id")
	private Long pairedEmployeeId;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id")
	private User user;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getEmployeeCode() {
		return employeeCode;
	}

	public void setEmployeeCode(String employeeCode) {
		this.employeeCode = employeeCode;
	}

	public String getFirstName() {
		return firstName;
	}

	public void setFirstName(String firstName) {
		this.firstName = firstName;
	}

	public String getLastName() {
		return lastName;
	}

	public void setLastName(String lastName) {
		this.lastName = lastName;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public Gender getGender() {
		return gender;
	}

	public void setGender(Gender gender) {
		this.gender = gender;
	}

	public boolean isActive() {
		return active;
	}

	public void setActive(boolean active) {
		this.active = active;
	}

	public String getContactNumber() {
		return contactNumber;
	}

	public void setContactNumber(String contactNumber) {
		this.contactNumber = contactNumber;
	}

	public LocalDate getJoiningDate() {
		return joiningDate;
	}

	public void setJoiningDate(LocalDate joiningDate) {
		this.joiningDate = joiningDate;
	}

	public LocalDate getResignationDate() {
		return resignationDate;
	}

	public void setResignationDate(LocalDate resignationDate) {
		this.resignationDate = resignationDate;
	}

	public String getDepartment() {
		return department;
	}

	public void setDepartment(String department) {
		this.department = department;
	}

	public boolean isDevelopmentTeam() {
		return developmentTeam || "DEVELOPMENT".equalsIgnoreCase(department);
	}

	public void setDevelopmentTeam(boolean developmentTeam) {
		this.developmentTeam = developmentTeam;
	}

	public Integer getMaxNightsPerWeek() {
		return maxNightsPerWeek;
	}

	public void setMaxNightsPerWeek(Integer maxNightsPerWeek) {
		this.maxNightsPerWeek = maxNightsPerWeek;
	}

	public Long getPairedEmployeeId() {
		return pairedEmployeeId;
	}

	public void setPairedEmployeeId(Long pairedEmployeeId) {
		this.pairedEmployeeId = pairedEmployeeId;
	}

	public User getUser() {
		return user;
	}

	public void setUser(User user) {
		this.user = user;
	}

	public boolean isEligibleForDate(LocalDate date) {
		if (!active) return false;
		if (date == null) return true;
		if (joiningDate != null && date.isBefore(joiningDate)) return false;
		if (resignationDate != null && date.isAfter(resignationDate)) return false;
		return true;
	}
}
