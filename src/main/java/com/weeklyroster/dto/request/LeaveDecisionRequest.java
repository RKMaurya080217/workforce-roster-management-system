package com.weeklyroster.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import jakarta.validation.constraints.Size;

@JsonDeserialize(builder = LeaveDecisionRequest.Builder.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record LeaveDecisionRequest(
        @Size(max = 500) String remarks
) {
    public static Builder builder() {
        return new Builder();
    }

    @JsonPOJOBuilder(withPrefix = "")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Builder {
        private String remarks;

        @JsonProperty("remarks")
        public Builder remarks(String remarks) {
            if (remarks != null && !remarks.isBlank()) {
                this.remarks = remarks;
            }
            return this;
        }

        @JsonProperty("adminRemarks")
        public Builder adminRemarks(String adminRemarks) {
            if ((this.remarks == null || this.remarks.isBlank()) && adminRemarks != null) {
                this.remarks = adminRemarks;
            }
            return this;
        }

        @JsonProperty("decisionReason")
        public Builder decisionReason(String val) {
            return adminRemarks(val);
        }

        @JsonProperty("reason")
        public Builder reason(String val) {
            return adminRemarks(val);
        }

        @JsonProperty("note")
        public Builder note(String val) {
            return adminRemarks(val);
        }

        @JsonProperty("admin_remarks")
        public Builder admin_remarks(String val) {
            return adminRemarks(val);
        }

        public LeaveDecisionRequest build() {
            return new LeaveDecisionRequest(remarks);
        }
    }
}
