package com.weeklyroster.dto.external.v1;

public record ExternalEmployeeResponse(
        Long id,
        String employeeCode,
        String firstName,
        String lastName,
        String email,
        String gender,
        boolean active
) {}
