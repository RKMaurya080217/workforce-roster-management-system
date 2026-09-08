package com.weeklyroster.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@DiscriminatorValue("SKILL")
public class Skill extends MasterReferenceItem {

    @Column(name = "skill_name", length = 100)
    private String name;

    @Column(name = "skill_category", length = 100)
    private String category;

    @Column(name = "skill_description", length = 500)
    private String description;

    @Column(name = "skill_active")
    private Boolean active = true;

    @Column(name = "skill_created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public Skill() {}

    public Skill(String name, String category, String description) {
        this.name = name;
        this.category = category;
        this.description = description;
        this.active = true;
        this.createdAt = LocalDateTime.now();
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isActive() { return active != null && active; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
