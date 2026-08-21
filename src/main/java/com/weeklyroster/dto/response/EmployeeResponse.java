package com.weeklyroster.dto.response;

import com.weeklyroster.entity.Gender;

public record EmployeeResponse(
        Long id,
        String employeeCode,
        String firstName,
        String lastName,
        String email,
        Gender gender,
        boolean active,
        String username,
        String contactNumber
) {
    public EmployeeResponse(Long id, String employeeCode, String firstName, String lastName, String email, Gender gender, boolean active, String username) {
        this(id, employeeCode, firstName, lastName, email, gender, active, username, null);
    }
}
