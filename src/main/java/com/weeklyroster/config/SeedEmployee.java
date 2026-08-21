package com.weeklyroster.config;

import com.weeklyroster.entity.Gender;

public class SeedEmployee {

	private String employeeCode;
	private String firstName;
	private String lastName;
	private String email;
	private Gender gender;
	private String contactNumber;

	public SeedEmployee() {
	}

	public SeedEmployee(String employeeCode, String firstName, String lastName, String email, Gender gender) {
		this(employeeCode, firstName, lastName, email, gender, null);
	}

	public SeedEmployee(String employeeCode, String firstName, String lastName, String email, Gender gender, String contactNumber) {
		this.employeeCode = employeeCode;
		this.firstName = firstName;
		this.lastName = lastName;
		this.email = email;
		this.gender = gender;
		this.contactNumber = contactNumber;
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

	public String getContactNumber() {
		return contactNumber;
	}

	public void setContactNumber(String contactNumber) {
		this.contactNumber = contactNumber;
	}
}