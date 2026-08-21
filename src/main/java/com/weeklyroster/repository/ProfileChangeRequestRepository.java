package com.weeklyroster.repository;

import com.weeklyroster.entity.ProfileChangeRequest;
import com.weeklyroster.entity.ProfileChangeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProfileChangeRequestRepository extends JpaRepository<ProfileChangeRequest, Long> {

    List<ProfileChangeRequest> findByEmployeeIdOrderByRequestedAtDesc(Long employeeId);

    List<ProfileChangeRequest> findByEmployeeIdAndStatus(Long employeeId, ProfileChangeStatus status);

    List<ProfileChangeRequest> findByStatusOrderByRequestedAtAsc(ProfileChangeStatus status);

    boolean existsByEmployeeIdAndFieldNameAndStatus(Long employeeId, String fieldName, ProfileChangeStatus status);
}
