package com.weeklyroster.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.weeklyroster.entity.HandoverPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

@JsonDeserialize(builder = CreateHandoverRequest.Builder.class)
public record CreateHandoverRequest(
        @NotNull(message = "Handover date is required") LocalDate handoverDate,
        @NotNull(message = "Shift ID is required") Long shiftId,
        Long fromEmployeeId,
        Long toEmployeeId,
        @NotBlank(message = "Summary is required") String summary,
        String pendingTasks,
        String completedTasks,
        String importantNotes,
        HandoverPriority priority
) {
    public CreateHandoverRequest(LocalDate handoverDate, Long shiftId, Long toEmployeeId,
                                 String summary, String pendingTasks, String completedTasks,
                                 String importantNotes, HandoverPriority priority) {
        this(handoverDate, shiftId, null, toEmployeeId, summary, pendingTasks, completedTasks, importantNotes, priority);
    }

    @JsonPOJOBuilder(withPrefix = "")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Builder {
        private LocalDate handoverDate;
        private Long shiftId;
        private Long fromEmployeeId;
        private Long toEmployeeId;
        private String summary;
        private String pendingTasks;
        private String completedTasks;
        private String importantNotes;
        private HandoverPriority priority = HandoverPriority.MEDIUM;

        @JsonProperty("handoverDate")
        public Builder handoverDate(LocalDate handoverDate) {
            this.handoverDate = handoverDate;
            return this;
        }

        @JsonProperty("shiftId")
        public Builder shiftId(Long shiftId) {
            this.shiftId = shiftId;
            return this;
        }

        @JsonProperty("fromEmployeeId")
        public Builder fromEmployeeId(Long fromEmployeeId) {
            this.fromEmployeeId = fromEmployeeId;
            return this;
        }

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
            if (priority != null) {
                this.priority = priority;
            }
            return this;
        }

        public CreateHandoverRequest build() {
            return new CreateHandoverRequest(
                    handoverDate, shiftId, fromEmployeeId, toEmployeeId,
                    summary, pendingTasks, completedTasks, importantNotes, priority
            );
        }
    }
}
