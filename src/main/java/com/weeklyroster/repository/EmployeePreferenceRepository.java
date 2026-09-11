package com.weeklyroster.repository;

import com.weeklyroster.entity.EmployeePreference;
import com.weeklyroster.entity.PreferenceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmployeePreferenceRepository extends JpaRepository<EmployeePreference, Long> {

    @Query("SELECT p FROM EmployeePreference p JOIN FETCH p.employee WHERE p.employee.id = :employeeId ORDER BY p.createdAt DESC")
    List<EmployeePreference> findByEmployeeIdOrderByCreatedAtDesc(@Param("employeeId") Long employeeId);

    @Query("SELECT p FROM EmployeePreference p JOIN FETCH p.employee WHERE p.employee.id = :employeeId AND p.status = :status ORDER BY p.createdAt DESC")
    List<EmployeePreference> findByEmployeeIdAndStatusOrderByCreatedAtDesc(@Param("employeeId") Long employeeId, @Param("status") PreferenceStatus status);

    @Query("SELECT p FROM EmployeePreference p JOIN FETCH p.employee WHERE p.employee.id = :employeeId AND p.status = :status ORDER BY p.createdAt DESC")
    Optional<EmployeePreference> findTopByEmployeeIdAndStatusOrderByCreatedAtDesc(@Param("employeeId") Long employeeId, @Param("status") PreferenceStatus status);

    @Query("SELECT p FROM EmployeePreference p JOIN FETCH p.employee WHERE p.status = :status ORDER BY p.createdAt DESC")
    List<EmployeePreference> findByStatusOrderByCreatedAtDesc(@Param("status") PreferenceStatus status);

    @Query("SELECT p FROM EmployeePreference p JOIN FETCH p.employee ORDER BY p.createdAt DESC")
    List<EmployeePreference> findAllByOrderByCreatedAtDesc();
}
