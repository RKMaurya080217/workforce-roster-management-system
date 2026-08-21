package com.weeklyroster.repository;

import com.weeklyroster.entity.EmailDeliveryLog;
import com.weeklyroster.entity.EmailDeliveryStatus;
import com.weeklyroster.entity.RosterCycle;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailDeliveryLogRepository extends JpaRepository<EmailDeliveryLog, Long> {

    @Query("SELECT l FROM EmailDeliveryLog l JOIN FETCH l.employee JOIN FETCH l.cycle WHERE l.cycle = :cycle ORDER BY l.sentAt DESC, l.employee.id ASC")
    List<EmailDeliveryLog> findByCycleOrderBySentAtDesc(@Param("cycle") RosterCycle cycle);

    @Query("SELECT l FROM EmailDeliveryLog l JOIN FETCH l.employee JOIN FETCH l.cycle WHERE l.cycle = :cycle AND l.status = :status")
    List<EmailDeliveryLog> findByCycleAndStatus(@Param("cycle") RosterCycle cycle, @Param("status") EmailDeliveryStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM EmailDeliveryLog l WHERE l.cycle.id = :cycleId")
    void deleteByCycleId(@Param("cycleId") Long cycleId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM EmailDeliveryLog l WHERE l.cycle = :cycle")
    void deleteByCycle(@Param("cycle") RosterCycle cycle);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM EmailDeliveryLog l WHERE l.cycle.id IN (SELECT c.id FROM RosterCycle c WHERE c.startDate <= :endDate AND c.endDate >= :startDate)")
    void deleteByCycleDateRange(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM email_delivery_logs WHERE cycle_id = :cycleId", nativeQuery = true)
    void deleteByCycleIdNative(@Param("cycleId") Long cycleId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE el FROM email_delivery_logs el JOIN roster_cycles rc ON el.cycle_id = rc.id WHERE rc.start_date <= :endDate AND rc.end_date >= :startDate", nativeQuery = true)
    void deleteByDateRangeNative(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);
}
