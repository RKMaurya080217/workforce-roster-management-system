package com.weeklyroster.repository;

import com.weeklyroster.entity.ActivityCategory;
import com.weeklyroster.entity.EmployeeActivityLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EmployeeActivityLogRepository extends JpaRepository<EmployeeActivityLog, Long> {

    Page<EmployeeActivityLog> findByUsernameOrderByCreatedAtDesc(String username, Pageable pageable);

    Page<EmployeeActivityLog> findByUsernameAndCategoryOrderByCreatedAtDesc(String username, ActivityCategory category, Pageable pageable);

    Page<EmployeeActivityLog> findByEmployeeIdOrderByCreatedAtDesc(Long employeeId, Pageable pageable);

    Page<EmployeeActivityLog> findByEmployeeIdAndCategoryOrderByCreatedAtDesc(Long employeeId, ActivityCategory category, Pageable pageable);
}
