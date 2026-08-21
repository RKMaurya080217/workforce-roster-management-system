package com.weeklyroster.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateMyProfileRequest(
        @NotBlank(message = "First name is required")
        @Size(max = 80, message = "First name must not exceed 80 characters")
        String firstName,

        @Size(max = 80, message = "Last name must not exceed 80 characters")
        String lastName,

        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        @Size(max = 160, message = "Email must not exceed 160 characters")
        String email,

        @Size(max = 30, message = "Contact number must not exceed 30 characters")
        String contactNumber
) {
}
