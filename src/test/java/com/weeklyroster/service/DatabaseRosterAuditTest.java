package com.weeklyroster.service;

import com.weeklyroster.entity.RosterCycle;
import com.weeklyroster.repository.RosterAssignmentRepository;
import com.weeklyroster.repository.RosterCycleRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class DatabaseRosterAuditTest {

    @Autowired
    private RosterCycleRepository cycleRepository;

    @Autowired
    private RosterAssignmentRepository assignmentRepository;

    @Test
    void auditRosterCycles() {
        List<RosterCycle> cycles = cycleRepository.findAll();
        System.out.println("=== ROSTER CYCLES AUDIT IN DATABASE ===");
        System.out.println("Total cycles in DB: " + cycles.size());
        for (RosterCycle c : cycles) {
            int count = assignmentRepository.findByCycleIdOrderByRosterDateAsc(c.getId()).size();
            System.out.println(String.format("ID: #%-3d | %s to %s | Mode: %-9s | Status: %-9s | Assignments: %d",
                    c.getId(), c.getStartDate(), c.getEndDate(), c.getGenerationMode(), c.getStatus(), count));
        }
        System.out.println("=======================================");
    }
}