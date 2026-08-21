package com.weeklyroster.dto.response;

import java.util.List;

public record EmployeeActivityPageResponse(
    List<EmployeeActivityResponse> content,
    int page,
    int size,
    long totalElements,
    int totalPages,
    boolean hasMore
) {}
