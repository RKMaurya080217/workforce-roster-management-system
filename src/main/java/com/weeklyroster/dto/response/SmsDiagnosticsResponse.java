package com.weeklyroster.dto.response;

public record SmsDiagnosticsResponse(
        boolean enabled,
        String provider,
        boolean configured,
        boolean realTelecomConfigured,
        boolean apiKeyConfigured,
        boolean apiSecretConfigured,
        String senderId,
        boolean endpointUrlConfigured,
        boolean dltConfigured,
        String dltEntityId,
        int activeEmployeesCount,
        int employeesWithValidMobileCount,
        int employeesMissingMobileCount,
        String operatingMode,
        String notice
) {
}
