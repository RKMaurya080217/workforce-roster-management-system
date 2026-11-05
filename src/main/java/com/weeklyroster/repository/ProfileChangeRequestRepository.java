package com.weeklyroster.repository;

import com.weeklyroster.entity.ProfileChangeRequest;
import com.weeklyroster.entity.ProfileChangeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProfileChangeRequestRepository extends JpaRepository<ProfileChangeRequest, Long> {

    @Query("SELECT r FROM ProfileChangeRequest r JOIN FETCH r.employee WHERE r.employee.id = :employeeId ORDER BY r.requestedAt DESC")
    List<ProfileChangeRequest> findByEmployeeIdOrderByRequestedAtDesc(@Param("employeeId") Long employeeId);

    @Query("SELECT r FROM ProfileChangeRequest r JOIN FETCH r.employee WHERE r.employee.id = :employeeId AND r.status = :status")
    List<ProfileChangeRequest> findByEmployeeIdAndStatus(@Param("employeeId") Long employeeId, @Param("status") ProfileChangeStatus status);

    @Query("SELECT r FROM ProfileChangeRequest r JOIN FETCH r.employee WHERE r.status = :status ORDER BY r.requestedAt ASC")
    List<ProfileChangeRequest> findByStatusOrderByRequestedAtAsc(@Param("status") ProfileChangeStatus status);

    @Query("SELECT r FROM ProfileChangeRequest r JOIN FETCH r.employee ORDER BY r.requestedAt DESC")
    List<ProfileChangeRequest> findAllOrderByRequestedAtDesc();

    @Query("SELECT r FROM ProfileChangeRequest r JOIN FETCH r.employee WHERE (:status IS NULL OR r.status = :status) AND (:employeeId IS NULL OR r.employee.id = :employeeId) ORDER BY r.requestedAt DESC")
    List<ProfileChangeRequest> findByFilters(@Param("status") ProfileChangeStatus status, @Param("employeeId") Long employeeId);

    boolean existsByEmployeeIdAndFieldNameAndStatus(Long employeeId, String fieldName, ProfileChangeStatus status);
}
