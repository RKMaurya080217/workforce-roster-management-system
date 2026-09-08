package com.weeklyroster.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@DiscriminatorValue("HOLIDAY")
public class Holiday extends MasterReferenceItem {

    @Column(name = "holiday_name", length = 150)
    private String name;

    @Column(name = "holiday_date")
    private LocalDate holidayDate;

    @Column(name = "holiday_description", length = 500)
    private String description;

    @Column(name = "holiday_active")
    private Boolean active = true;

    @Column(name = "holiday_created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "holiday_updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Holiday() {}

    public Holiday(String name, LocalDate holidayDate, String description) {
        this.name = name;
        this.holidayDate = holidayDate;
        this.description = description;
        this.active = true;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public LocalDate getHolidayDate() { return holidayDate; }
    public void setHolidayDate(LocalDate holidayDate) { this.holidayDate = holidayDate; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isActive() { return active != null && active; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
