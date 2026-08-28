package com.weeklyroster.repository;

import com.weeklyroster.entity.RosterVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface RosterVersionRepository extends JpaRepository<RosterVersion, Long> {
    List<RosterVersion> findByCycleIdOrderByVersionNumberDesc(Long cycleId);
    Optional<RosterVersion> findByCycleIdAndVersionNumber(Long cycleId, int versionNumber);
    Optional<RosterVersion> findTopByCycleIdOrderByVersionNumberDesc(Long cycleId);
    int countByCycleId(Long cycleId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM roster_versions WHERE cycle_id = :cycleId", nativeQuery = true)
    void deleteByCycleIdNative(@Param("cycleId") Long cycleId);
}