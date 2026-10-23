package com.weeklyroster.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Startup sanitizer to ensure full backward compatibility with legacy database records.
 * Fixes any historical NULL values in manual_assignment or assignment_source columns
 * without dropping tables or altering existing assignments.
 */
@Component
public class RosterDataSanitizer {

    private static final Logger log = LoggerFactory.getLogger(RosterDataSanitizer.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    private DatabaseWriteCoordinator databaseWriteCoordinator;

    @EventListener(ApplicationReadyEvent.class)
    public void sanitizeLegacyAssignments() {
        Runnable sanitizeAction = () -> {
            try {
                int manualUpdated = jdbcTemplate.update(
                        "UPDATE roster_assignments SET manual_assignment = 0 WHERE manual_assignment IS NULL");
                int sourceUpdated = jdbcTemplate.update(
                        "UPDATE roster_assignments SET assignment_source = 'AUTOMATIC' WHERE assignment_source IS NULL");
                if (manualUpdated > 0 || sourceUpdated > 0) {
                    log.info("[RosterDataSanitizer] Migrated legacy records: {} null manual_assignment rows, {} null assignment_source rows updated",
                            manualUpdated, sourceUpdated);
                }
            } catch (Exception e) {
                log.warn("[RosterDataSanitizer] Optional legacy sanitization check skipped: {}", e.getMessage());
            }
        };

        if (databaseWriteCoordinator != null) {
            databaseWriteCoordinator.executeTransactionalWriteVoid(sanitizeAction);
        } else {
            sanitizeAction.run();
        }
    }
}
