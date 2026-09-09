package com.weeklyroster.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "system_audit_logs", indexes = {
    @Index(name = "idx_sal_type", columnList = "log_type"),
    @Index(name = "idx_sal_cycle", columnList = "cycle_id"),
    @Index(name = "idx_sal_emp", columnList = "employee_id"),
    @Index(name = "idx_sal_actor", columnList = "actor"),
    @Index(name = "idx_sal_audit_act", columnList = "audit_action"),
    @Index(name = "idx_sal_act_act", columnList = "activity_action"),
    @Index(name = "idx_sal_type_cycle", columnList = "log_type, cycle_id"),
    @Index(name = "idx_sal_type_emp", columnList = "log_type, employee_id"),
    @Index(name = "idx_sal_assignment", columnList = "assignment_id")
})
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "log_type", discriminatorType = DiscriminatorType.STRING, length = 30)
public abstract class SystemAuditLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
}
