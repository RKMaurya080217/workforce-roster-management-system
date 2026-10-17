package com.weeklyroster.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "employee_requests", indexes = {
    @Index(name = "idx_er_emp", columnList = "employee_id"),
    @Index(name = "idx_er_type", columnList = "request_type"),
    @Index(name = "idx_er_cycle", columnList = "cycle_id"),
    @Index(name = "idx_er_emp_status", columnList = "employee_id, preference_status"),
    @Index(name = "idx_er_pcr_status", columnList = "profile_status")
})
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "request_type", discriminatorType = DiscriminatorType.STRING, length = 30)
public abstract class EmployeeWorkflowRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "version")
    private Long version;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
