package com.weeklyroster.repository;

import com.weeklyroster.entity.ManualRosterOperation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ManualRosterOperationRepository extends JpaRepository<ManualRosterOperation, Long> {
    List<ManualRosterOperation> findByCycleIdOrderByCreatedAtDesc(Long cycleId);
    Optional<ManualRosterOperation> findFirstByCycleIdAndUndoneFalseOrderByCreatedAtDesc(Long cycleId);
    long countByCycleIdAndUndoneFalse(Long cycleId);
}
