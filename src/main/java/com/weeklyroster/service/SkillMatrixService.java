package com.weeklyroster.service;

import com.weeklyroster.dto.request.AssignEmployeeSkillRequest;
import com.weeklyroster.dto.request.SkillRequest;
import com.weeklyroster.dto.request.UpdateEmployeeSkillRequest;
import com.weeklyroster.dto.response.EmployeeSkillResponse;
import com.weeklyroster.dto.response.SkillResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.EmployeeSkillRepository;
import com.weeklyroster.repository.SkillRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional
public class SkillMatrixService {

    private final SkillRepository skillRepository;
    private final EmployeeSkillRepository employeeSkillRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeActivityLogService activityLogService;
    private final AuditService auditService;

    public SkillMatrixService(SkillRepository skillRepository,
                              EmployeeSkillRepository employeeSkillRepository,
                              EmployeeRepository employeeRepository,
                              EmployeeActivityLogService activityLogService,
                              AuditService auditService) {
        this.skillRepository = skillRepository;
        this.employeeSkillRepository = employeeSkillRepository;
        this.employeeRepository = employeeRepository;
        this.activityLogService = activityLogService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<SkillResponse> getActiveSkills() {
        return skillRepository.findByActiveTrueOrderByNameAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SkillResponse> getAllSkills() {
        return skillRepository.findAllByOrderByNameAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    public SkillResponse createSkill(SkillRequest req, String adminUsername) {
        if (skillRepository.existsByNameIgnoreCase(req.name().trim())) {
            throw new BusinessException("Skill with name '" + req.name().trim() + "' already exists.");
        }

        Skill skill = new Skill(
                req.name().trim(),
                req.category() != null ? req.category().trim().toUpperCase() : "GENERAL",
                req.description() != null ? req.description().trim() : null
        );
        if (req.active() != null) {
            skill.setActive(req.active());
        }
        Skill saved = skillRepository.save(skill);

        auditService.log(AuditAction.SKILL_ASSIGNED, "SKILL", saved.getId(), null,
                null, null, null, saved.getName(), "Created skill: " + saved.getName(), "MANUAL");

        return toResponse(saved);
    }

    public SkillResponse updateSkill(Long id, SkillRequest req, String adminUsername) {
        Skill skill = skillRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Skill not found with id: " + id));

        if (skillRepository.existsByNameIgnoreCaseAndIdNot(req.name().trim(), id)) {
            throw new BusinessException("Another skill with name '" + req.name().trim() + "' already exists.");
        }

        skill.setName(req.name().trim());
        if (req.category() != null) {
            skill.setCategory(req.category().trim().toUpperCase());
        }
        skill.setDescription(req.description() != null ? req.description().trim() : null);
        if (req.active() != null) {
            skill.setActive(req.active());
        }
        Skill saved = skillRepository.save(skill);
        return toResponse(saved);
    }

    public void deleteSkill(Long id, String adminUsername) {
        Skill skill = skillRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Skill not found with id: " + id));
        skillRepository.delete(skill);

        auditService.log(AuditAction.SKILL_REMOVED, "SKILL", id, null,
                null, null, skill.getName(), "DELETED", "Deleted skill: " + skill.getName(), "MANUAL");
    }

    @Transactional(readOnly = true)
    public List<EmployeeSkillResponse> getMySkills(Long employeeId) {
        return employeeSkillRepository.findByEmployeeIdAndActiveTrueOrderBySkillNameAsc(employeeId).stream()
                .map(this::toEmployeeSkillResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EmployeeSkillResponse> getAllEmployeeSkills() {
        return employeeSkillRepository.findAllByOrderByEmployeeFirstNameAsc().stream()
                .map(this::toEmployeeSkillResponse)
                .toList();
    }

    public EmployeeSkillResponse assignSkillToEmployee(AssignEmployeeSkillRequest req, String adminUsername) {
        Employee emp = employeeRepository.findById(req.employeeId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + req.employeeId()));

        Skill skill = skillRepository.findById(req.skillId())
                .orElseThrow(() -> new ResourceNotFoundException("Skill not found with id: " + req.skillId()));

        if (employeeSkillRepository.existsByEmployeeIdAndSkillId(emp.getId(), skill.getId())) {
            throw new BusinessException("Employee " + emp.getFirstName() + " already has skill " + skill.getName() + " assigned.");
        }

        EmployeeSkill es = new EmployeeSkill();
        es.setEmployee(emp);
        es.setSkill(skill);
        if (req.proficiencyLevel() != null) {
            es.setProficiencyLevel(req.proficiencyLevel());
        }
        es.setCertificationName(req.certificationName() != null ? req.certificationName().trim() : null);
        es.setCertificationExpiryDate(req.certificationExpiryDate());
        if (req.certified() != null) {
            es.setCertified(req.certified());
        }
        es.setActive(true);
        es.setCreatedAt(LocalDateTime.now());
        es.setUpdatedAt(LocalDateTime.now());

        EmployeeSkill saved = employeeSkillRepository.save(es);

        activityLogService.logActivity(emp.getId(), emp.getEmployeeCode().toLowerCase(), ActivityCategory.SKILL,
                "SKILL_ASSIGNED", ActivityStatus.SUCCESS,
                "Assigned skill " + skill.getName() + " (" + es.getProficiencyLevel().name() + ")");

        auditService.log(AuditAction.SKILL_ASSIGNED, "EMPLOYEE_SKILL", saved.getId(), null,
                emp.getId(), emp.getFirstName() + " " + emp.getLastName(),
                null, skill.getName() + " [" + es.getProficiencyLevel() + "]",
                "Assigned skill to employee", "MANUAL");

        return toEmployeeSkillResponse(saved);
    }

    public EmployeeSkillResponse updateEmployeeSkill(Long id, UpdateEmployeeSkillRequest req, String adminUsername) {
        EmployeeSkill es = employeeSkillRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Employee skill assignment not found with id: " + id));

        if (req.proficiencyLevel() != null) {
            es.setProficiencyLevel(req.proficiencyLevel());
        }
        if (req.certificationName() != null) {
            es.setCertificationName(req.certificationName().trim());
        }
        if (req.certificationExpiryDate() != null) {
            es.setCertificationExpiryDate(req.certificationExpiryDate());
        }
        if (req.certified() != null) {
            es.setCertified(req.certified());
        }
        if (req.active() != null) {
            es.setActive(req.active());
        }
        es.setUpdatedAt(LocalDateTime.now());

        EmployeeSkill saved = employeeSkillRepository.save(es);
        return toEmployeeSkillResponse(saved);
    }

    public void removeSkillFromEmployee(Long id, String adminUsername) {
        EmployeeSkill es = employeeSkillRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Employee skill assignment not found with id: " + id));

        Employee emp = es.getEmployee();
        Skill skill = es.getSkill();
        employeeSkillRepository.delete(es);

        auditService.log(AuditAction.SKILL_REMOVED, "EMPLOYEE_SKILL", id, null,
                emp.getId(), emp.getFirstName() + " " + emp.getLastName(),
                skill.getName(), "REMOVED", "Removed skill from employee", "MANUAL");
    }

    private SkillResponse toResponse(Skill s) {
        return new SkillResponse(
                s.getId(),
                s.getName(),
                s.getCategory(),
                s.getDescription(),
                s.isActive(),
                s.getCreatedAt()
        );
    }

    private EmployeeSkillResponse toEmployeeSkillResponse(EmployeeSkill es) {
        Employee emp = es.getEmployee();
        Skill s = es.getSkill();
        return new EmployeeSkillResponse(
                es.getId(),
                emp != null ? emp.getId() : null,
                emp != null ? emp.getEmployeeCode() : null,
                emp != null ? (emp.getFirstName() + " " + (emp.getLastName() != null ? emp.getLastName() : "")).trim() : null,
                s != null ? s.getId() : null,
                s != null ? s.getName() : null,
                s != null ? s.getCategory() : null,
                es.getProficiencyLevel(),
                es.getCertificationName(),
                es.getCertificationExpiryDate(),
                es.isCertified(),
                es.isActive(),
                es.getCreatedAt(),
                es.getUpdatedAt()
        );
    }
}
