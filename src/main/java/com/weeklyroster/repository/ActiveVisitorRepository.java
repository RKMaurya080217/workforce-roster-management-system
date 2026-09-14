package com.weeklyroster.repository;

import com.weeklyroster.entity.ActiveVisitor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface ActiveVisitorRepository extends JpaRepository<ActiveVisitor, Long> {

    Optional<ActiveVisitor> findByVisitorId(String visitorId);

    @Transactional
    @Modifying
    @Query("UPDATE ActiveVisitor a SET a.lastSeenAt = :now WHERE a.visitorId = :visitorId")
    int updateLastSeen(@Param("visitorId") String visitorId, @Param("now") LocalDateTime now);

    @Query("SELECT COUNT(a) FROM ActiveVisitor a WHERE a.lastSeenAt >= :threshold")
    long countActiveVisitorsSince(@Param("threshold") LocalDateTime threshold);

    @Transactional
    @Modifying
    @Query("DELETE FROM ActiveVisitor a WHERE a.visitorId = :visitorId")
    int deleteByVisitorId(@Param("visitorId") String visitorId);

    @Transactional
    @Modifying
    @Query("DELETE FROM ActiveVisitor a WHERE a.lastSeenAt < :cutoff")
    int deleteExpiredVisitorsBefore(@Param("cutoff") LocalDateTime cutoff);
}