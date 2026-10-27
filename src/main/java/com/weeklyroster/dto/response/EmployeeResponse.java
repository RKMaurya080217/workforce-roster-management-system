package com.weeklyroster.dto.response;

import com.weeklyroster.entity.Gender;
import java.time.LocalDate;

public record EmployeeResponse(
        Long id,
        String employeeCode,
        String firstName,
        String lastName,
        String email,
        Gender gender,
        boolean active,
        String username,
        String contactNumber,
        String shiftPreference,
        LocalDate joiningDate,
        LocalDate resignationDate
) {
    public EmployeeResponse(Long id, String employeeCode, String firstName, String lastName, String email, Gender gender, boolean active, String username) {
        this(id, employeeCode, firstName, lastName, email, gender, active, username, null, null, null, null);
    }

    public EmployeeResponse(Long id, String employeeCode, String firstName, String lastName, String email, Gender gender, boolean active, String username, String contactNumber) {
        this(id, employeeCode, firstName, lastName, email, gender, active, username, contactNumber, null, null, null);
    }

    public EmployeeResponse(Long id, String employeeCode, String firstName, String lastName, String email, Gender gender, boolean active, String username, String contactNumber, String shiftPreference) {
        this(id, employeeCode, firstName, lastName, email, gender, active, username, contactNumber, shiftPreference, null, null);
    }
}
