package com.weeklyroster.repository;

import com.weeklyroster.entity.RosterVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface RosterVersionRepository extends JpaRepository<RosterVersion, Long> {
    List<RosterVersion> findByCycleIdOrderByVersionNumberDesc(Long cycleId);
    Optional<RosterVersion> findByCycleIdAndVersionNumber(Long cycleId, int versionNumber);
    int countByCycleId(Long cycleId);
}
