package com.weeklyroster.dto.response;

import com.weeklyroster.entity.Role;

public record UserProfileResponse(
        Long id,
        String username,
        Role role,
        Long employeeId,
        String employeeName
) {
}
