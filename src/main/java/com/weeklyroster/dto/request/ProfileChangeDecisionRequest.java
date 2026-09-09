package com.weeklyroster.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import jakarta.validation.constraints.Size;

@JsonDeserialize(builder = ProfileChangeDecisionRequest.Builder.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProfileChangeDecisionRequest(
        @Size(max = 500, message = "Admin remarks must not exceed 500 characters")
        String adminRemarks
) {
    public static Builder builder() {
        return new Builder();
    }

    @JsonPOJOBuilder(withPrefix = "")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Builder {
        private String adminRemarks;

        @JsonProperty("adminRemarks")
        public Builder adminRemarks(String adminRemarks) {
            if (adminRemarks != null && !adminRemarks.isBlank()) {
                this.adminRemarks = adminRemarks;
            }
            return this;
        }

        @JsonProperty("decisionReason")
        public Builder decisionReason(String decisionReason) {
            if ((this.adminRemarks == null || this.adminRemarks.isBlank()) && decisionReason != null) {
                this.adminRemarks = decisionReason;
            }
            return this;
        }

        @JsonProperty("decision_reason")
        public Builder decision_reason(String val) {
            return decisionReason(val);
        }

        @JsonProperty("remarks")
        public Builder remarks(String val) {
            return decisionReason(val);
        }

        @JsonProperty("reason")
        public Builder reason(String val) {
            return decisionReason(val);
        }

        @JsonProperty("admin_remarks")
        public Builder admin_remarks(String val) {
            return adminRemarks(val);
        }

        @JsonProperty("note")
        public Builder note(String val) {
            return decisionReason(val);
        }

        public ProfileChangeDecisionRequest build() {
            return new ProfileChangeDecisionRequest(adminRemarks);
        }
    }
}
