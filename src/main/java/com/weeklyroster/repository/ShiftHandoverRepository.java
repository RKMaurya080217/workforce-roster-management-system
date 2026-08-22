package com.weeklyroster.repository;

import com.weeklyroster.entity.ShiftHandover;
import com.weeklyroster.entity.HandoverStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface ShiftHandoverRepository extends JpaRepository<ShiftHandover, Long> {
    List<ShiftHandover> findByFromEmployeeIdOrderByHandoverDateDescCreatedAtDesc(Long fromEmployeeId);
    List<ShiftHandover> findByToEmployeeIdOrderByHandoverDateDescCreatedAtDesc(Long toEmployeeId);
    List<ShiftHandover> findByHandoverDateOrderByCreatedAtDesc(LocalDate handoverDate);
    List<ShiftHandover> findByHandoverDateBetweenOrderByHandoverDateDescCreatedAtDesc(LocalDate startDate, LocalDate endDate);
    List<ShiftHandover> findByStatusOrderByHandoverDateDesc(HandoverStatus status);
    List<ShiftHandover> findTop20ByOrderByHandoverDateDescCreatedAtDesc();
}
