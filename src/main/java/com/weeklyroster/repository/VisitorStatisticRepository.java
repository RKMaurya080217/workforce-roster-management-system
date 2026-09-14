package com.weeklyroster.repository;

import com.weeklyroster.entity.VisitorStatistic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface VisitorStatisticRepository extends JpaRepository<VisitorStatistic, Long> {

    @Transactional
    @Modifying
    @Query("UPDATE VisitorStatistic v SET v.totalVisits = v.totalVisits + 1, v.lastVisitAt = :now WHERE v.id = :id")
    int incrementTotalVisits(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Query("SELECT v.totalVisits FROM VisitorStatistic v WHERE v.id = :id")
    Optional<Long> findTotalVisitsById(@Param("id") Long id);

    @Query("SELECT v FROM VisitorStatistic v ORDER BY v.id ASC")
    Optional<VisitorStatistic> findFirstStat();
}