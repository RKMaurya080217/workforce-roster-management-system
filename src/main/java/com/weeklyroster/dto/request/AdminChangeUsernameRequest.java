package com.weeklyroster.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminChangeUsernameRequest(
        @NotBlank(message = "Current password is required for security verification")
        String currentPassword,

        @NotBlank(message = "New username is required")
        @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
        @Pattern(regexp = "^[a-zA-Z0-9_.-]+$", message = "Username may only contain letters, digits, dots, hyphens, and underscores")
        String newUsername
) {}
