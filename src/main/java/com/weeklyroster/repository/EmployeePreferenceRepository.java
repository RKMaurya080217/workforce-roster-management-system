package com.weeklyroster.repository;

import com.weeklyroster.entity.EmployeePreference;
import com.weeklyroster.entity.PreferenceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmployeePreferenceRepository extends JpaRepository<EmployeePreference, Long> {
    List<EmployeePreference> findByEmployeeIdOrderByCreatedAtDesc(Long employeeId);
    Optional<EmployeePreference> findTopByEmployeeIdAndStatusOrderByCreatedAtDesc(Long employeeId, PreferenceStatus status);
    List<EmployeePreference> findByStatusOrderByCreatedAtDesc(PreferenceStatus status);
    List<EmployeePreference> findAllByOrderByCreatedAtDesc();
}
