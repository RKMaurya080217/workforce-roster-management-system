package com.weeklyroster.repository;

import com.weeklyroster.entity.ShiftHandover;
import com.weeklyroster.entity.HandoverStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface ShiftHandoverRepository extends JpaRepository<ShiftHandover, Long> {

    @Query("SELECT h FROM ShiftHandover h JOIN FETCH h.shift JOIN FETCH h.fromEmployee LEFT JOIN FETCH h.toEmployee WHERE h.fromEmployee.id = :fromEmployeeId ORDER BY h.handoverDate DESC, h.createdAt DESC")
    List<ShiftHandover> findByFromEmployeeIdOrderByHandoverDateDescCreatedAtDesc(@Param("fromEmployeeId") Long fromEmployeeId);

    @Query("SELECT h FROM ShiftHandover h JOIN FETCH h.shift JOIN FETCH h.fromEmployee LEFT JOIN FETCH h.toEmployee WHERE h.toEmployee.id = :toEmployeeId ORDER BY h.handoverDate DESC, h.createdAt DESC")
    List<ShiftHandover> findByToEmployeeIdOrderByHandoverDateDescCreatedAtDesc(@Param("toEmployeeId") Long toEmployeeId);

    @Query("SELECT h FROM ShiftHandover h JOIN FETCH h.shift JOIN FETCH h.fromEmployee LEFT JOIN FETCH h.toEmployee WHERE h.handoverDate = :handoverDate ORDER BY h.createdAt DESC")
    List<ShiftHandover> findByHandoverDateOrderByCreatedAtDesc(@Param("handoverDate") LocalDate handoverDate);

    @Query("SELECT h FROM ShiftHandover h JOIN FETCH h.shift JOIN FETCH h.fromEmployee LEFT JOIN FETCH h.toEmployee WHERE h.handoverDate BETWEEN :startDate AND :endDate ORDER BY h.handoverDate DESC, h.createdAt DESC")
    List<ShiftHandover> findByHandoverDateBetweenOrderByHandoverDateDescCreatedAtDesc(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT h FROM ShiftHandover h JOIN FETCH h.shift JOIN FETCH h.fromEmployee LEFT JOIN FETCH h.toEmployee WHERE h.status = :status ORDER BY h.handoverDate DESC")
    List<ShiftHandover> findByStatusOrderByHandoverDateDesc(@Param("status") HandoverStatus status);

    @Query("SELECT h FROM ShiftHandover h JOIN FETCH h.shift JOIN FETCH h.fromEmployee LEFT JOIN FETCH h.toEmployee ORDER BY h.handoverDate DESC, h.createdAt DESC")
    List<ShiftHandover> findTop20ByOrderByHandoverDateDescCreatedAtDesc();
}
