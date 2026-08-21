package com.weeklyroster.dto.request;

import com.weeklyroster.entity.Gender;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record EmployeeRequest(
        @NotBlank @Size(max = 40) String employeeCode,
        @NotBlank @Size(max = 80) String firstName,
        @Size(max = 80) String lastName,
        @NotBlank @Email @Size(max = 160) String email,
        @NotNull Gender gender,
        String username,
        String password,
        String contactNumber
) {
    public EmployeeRequest(String employeeCode, String firstName, String lastName, String email, Gender gender, String username, String password) {
        this(employeeCode, firstName, lastName, email, gender, username, password, null);
    }
}
