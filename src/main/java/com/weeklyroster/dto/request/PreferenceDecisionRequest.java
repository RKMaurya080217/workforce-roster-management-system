package com.weeklyroster.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.weeklyroster.entity.PreferenceStatus;
import jakarta.validation.constraints.NotNull;

@JsonDeserialize(builder = PreferenceDecisionRequest.Builder.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record PreferenceDecisionRequest(
        @NotNull(message = "Decision status is required") PreferenceStatus status,
        String adminRemarks
) {
    public static Builder builder() {
        return new Builder();
    }

    @JsonPOJOBuilder(withPrefix = "")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Builder {
        private PreferenceStatus status;
        private String adminRemarks;

        @JsonProperty("status")
        public Builder status(Object raw) {
            PreferenceStatus parsed = parseStatus(raw);
            if (parsed != null) {
                this.status = parsed;
            }
            return this;
        }

        @JsonProperty("decision")
        public Builder decision(Object raw) {
            if (this.status == null) {
                PreferenceStatus parsed = parseStatus(raw);
                if (parsed != null) {
                    this.status = parsed;
                }
            }
            return this;
        }

        @JsonProperty("action")
        public Builder action(Object raw) {
            return decision(raw);
        }

        @JsonProperty("adminRemarks")
        public Builder adminRemarks(String adminRemarks) {
            if (adminRemarks != null && !adminRemarks.isBlank()) {
                this.adminRemarks = adminRemarks;
            }
            return this;
        }

        @JsonProperty("reviewNote")
        public Builder reviewNote(String reviewNote) {
            if ((this.adminRemarks == null || this.adminRemarks.isBlank()) && reviewNote != null) {
                this.adminRemarks = reviewNote;
            }
            return this;
        }

        @JsonProperty("remarks")
        public Builder remarks(String val) {
            return reviewNote(val);
        }

        @JsonProperty("decisionReason")
        public Builder decisionReason(String val) {
            return reviewNote(val);
        }

        @JsonProperty("reason")
        public Builder reason(String val) {
            return reviewNote(val);
        }

        @JsonProperty("admin_remarks")
        public Builder admin_remarks(String val) {
            return adminRemarks(val);
        }

        @JsonProperty("note")
        public Builder note(String val) {
            return reviewNote(val);
        }

        private PreferenceStatus parseStatus(Object raw) {
            if (raw instanceof PreferenceStatus ps) return ps;
            if (raw == null) return null;
            String str = raw.toString().trim().toUpperCase();
            if (str.equals("APPROVE") || str.equals("APPROVED")) {
                return PreferenceStatus.APPROVED;
            }
            if (str.equals("REJECT") || str.equals("REJECTED")) {
                return PreferenceStatus.REJECTED;
            }
            try {
                return PreferenceStatus.valueOf(str);
            } catch (Exception ignored) {
                return null;
            }
        }

        public PreferenceDecisionRequest build() {
            return new PreferenceDecisionRequest(status, adminRemarks);
        }
    }
}
