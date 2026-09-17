package com.weeklyroster.repository;

import com.weeklyroster.entity.SmsDeliveryLog;
import com.weeklyroster.entity.SmsDeliveryStatus;
import com.weeklyroster.entity.SmsMessageType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface SmsDeliveryLogRepository extends JpaRepository<SmsDeliveryLog, Long> {

    boolean existsByEmployeeIdAndRosterCycleIdAndMessageTypeAndStatusIn(
            Long employeeId,
            Long rosterCycleId,
            SmsMessageType messageType,
            Collection<SmsDeliveryStatus> statuses
    );

    List<SmsDeliveryLog> findByRosterCycleIdOrderByCreatedAtDesc(Long rosterCycleId);

    List<SmsDeliveryLog> findTop50ByOrderByCreatedAtDesc();

    long countByStatus(SmsDeliveryStatus status);
}
