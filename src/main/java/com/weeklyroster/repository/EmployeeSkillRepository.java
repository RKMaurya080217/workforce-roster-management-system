package com.weeklyroster.repository;

import com.weeklyroster.entity.EmployeeSkill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmployeeSkillRepository extends JpaRepository<EmployeeSkill, Long> {
    List<EmployeeSkill> findByEmployeeIdAndActiveTrueOrderBySkillNameAsc(Long employeeId);
    List<EmployeeSkill> findByEmployeeIdOrderBySkillNameAsc(Long employeeId);
    Optional<EmployeeSkill> findByEmployeeIdAndSkillId(Long employeeId, Long skillId);
    boolean existsByEmployeeIdAndSkillId(Long employeeId, Long skillId);
    List<EmployeeSkill> findBySkillIdAndActiveTrue(Long skillId);
    List<EmployeeSkill> findAllByOrderByEmployeeFirstNameAsc();
}
