package com.weeklyroster.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.weeklyroster.entity.HandoverPriority;
import com.weeklyroster.entity.HandoverStatus;

@JsonDeserialize(builder = UpdateHandoverRequest.Builder.class)
public record UpdateHandoverRequest(
        Long toEmployeeId,
        String summary,
        String pendingTasks,
        String completedTasks,
        String importantNotes,
        HandoverPriority priority,
        HandoverStatus status
) {
    @JsonPOJOBuilder(withPrefix = "")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Builder {
        private Long toEmployeeId;
        private String summary;
        private String pendingTasks;
        private String completedTasks;
        private String importantNotes;
        private HandoverPriority priority;
        private HandoverStatus status;

        @JsonProperty("toEmployeeId")
        public Builder toEmployeeId(Long toEmployeeId) {
            this.toEmployeeId = toEmployeeId;
            return this;
        }

        @JsonProperty("summary")
        public Builder summary(String summary) {
            if (summary != null && !summary.isBlank()) {
                this.summary = summary;
            }
            return this;
        }

        @JsonProperty("shiftSummary")
        public Builder shiftSummary(String shiftSummary) {
            if ((this.summary == null || this.summary.isBlank()) && shiftSummary != null) {
                this.summary = shiftSummary;
            }
            return this;
        }

        @JsonProperty("pendingTasks")
        public Builder pendingTasks(String pendingTasks) {
            this.pendingTasks = pendingTasks;
            return this;
        }

        @JsonProperty("completedTasks")
        public Builder completedTasks(String completedTasks) {
            this.completedTasks = completedTasks;
            return this;
        }

        @JsonProperty("importantNotes")
        public Builder importantNotes(String importantNotes) {
            if (importantNotes != null && !importantNotes.isBlank()) {
                this.importantNotes = importantNotes;
            }
            return this;
        }

        @JsonProperty("notes")
        public Builder notes(String notes) {
            if ((this.importantNotes == null || this.importantNotes.isBlank()) && notes != null) {
                this.importantNotes = notes;
            }
            return this;
        }

        @JsonProperty("priority")
        public Builder priority(HandoverPriority priority) {
            this.priority = priority;
            return this;
        }

        @JsonProperty("status")
        public Builder status(HandoverStatus status) {
            this.status = status;
            return this;
        }

        public UpdateHandoverRequest build() {
            return new UpdateHandoverRequest(toEmployeeId, summary, pendingTasks, completedTasks, importantNotes, priority, status);
        }
    }
}
