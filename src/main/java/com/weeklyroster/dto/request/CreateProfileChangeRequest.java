package com.weeklyroster.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateProfileChangeRequest(
        @JsonAlias({"field", "targetField", "name", "columnName", "field_name"})
        @NotBlank(message = "Field name is required")
        @Size(max = 60, message = "Field name must not exceed 60 characters")
        String fieldName,

        @JsonAlias({"value", "newValue", "new_value", "requested_value", "targetValue"})
        @NotBlank(message = "Requested value is required")
        @Size(max = 255, message = "Requested value must not exceed 255 characters")
        String requestedValue
) {
}

