package com.weeklyroster.entity;

import jakarta.persistence.*;
import java.time.LocalTime;

@Entity
@Table(name = "shifts")
public class Shift {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 30)
    private ShiftType shiftType;

    @Column(nullable = false)
    private int capacity;

    @Column(nullable = false)
    private boolean active = true;

    @Column
    private LocalTime startTime;

    @Column
    private LocalTime endTime;

    @Column(nullable = false)
    private boolean overnight = false;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public ShiftType getShiftType() { return shiftType; }
    public void setShiftType(ShiftType shiftType) { this.shiftType = shiftType; }
    public int getCapacity() { return capacity; }
    public void setCapacity(int capacity) { this.capacity = capacity; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public LocalTime getStartTime() { return startTime; }
    public void setStartTime(LocalTime startTime) { this.startTime = startTime; }
    public LocalTime getEndTime() { return endTime; }
    public void setEndTime(LocalTime endTime) { this.endTime = endTime; }
    public boolean isOvernight() { return overnight; }
    public void setOvernight(boolean overnight) { this.overnight = overnight; }

    public String getTimingDisplay() {
        if (shiftType == ShiftType.OFF || startTime == null || endTime == null) {
            return "No working hours";
        }
        String startStr = startTime.toString();
        String endStr = endTime.toString();
        if (overnight) {
            return startStr + " - " + endStr + " next day";
        }
        return startStr + " - " + endStr;
    }
}
