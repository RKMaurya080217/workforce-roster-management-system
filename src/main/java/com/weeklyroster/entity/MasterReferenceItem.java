package com.weeklyroster.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "master_reference_data", indexes = {
    @Index(name = "idx_mrd_type", columnList = "item_type")
})
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "item_type", discriminatorType = DiscriminatorType.STRING, length = 30)
public abstract class MasterReferenceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
}
