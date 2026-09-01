package com.weeklyroster.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Safe database and runtime startup diagnostic logger.
 * Explicitly logs host, port, database, and user WITHOUT exposing passwords or secrets.
 */
@Component
public class DatabaseStartupDiagnosticLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseStartupDiagnosticLogger.class);

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    @Value("${spring.datasource.username:}")
    private String datasourceUsername;

    @Value("${server.port:8080}")
    private String serverPort;

    @Value("${spring.datasource.hikari.maximum-pool-size:10}")
    private int maxPoolSize;

    @Value("${spring.datasource.hikari.connection-timeout:20000}")
    private long connectionTimeout;

    @Override
    public void run(ApplicationArguments args) {
        String sanitizedTarget = extractSanitizedTarget(datasourceUrl);
        log.info("================================================================================");
        log.info("  [WRMS Database Startup Diagnostics]");
        log.info("  Target Database   : {}", sanitizedTarget);
        log.info("  Database Username : {}", datasourceUsername != null && !datasourceUsername.isBlank() ? datasourceUsername : "N/A");
        log.info("  Server HTTP Port  : {}", serverPort);
        log.info("  HikariCP Settings : max-pool-size={}, conn-timeout={}ms", maxPoolSize, connectionTimeout);
        log.info("================================================================================");
    }

    private String extractSanitizedTarget(String url) {
        if (url == null || url.isBlank()) return "UNSET";
        // Remove query parameters
        int queryIdx = url.indexOf('?');
        String cleanUrl = queryIdx != -1 ? url.substring(0, queryIdx) : url;
        // Strip jdbc:mysql:// prefix
        if (cleanUrl.startsWith("jdbc:mysql://")) {
            cleanUrl = cleanUrl.substring("jdbc:mysql://".length());
        }
        // Mask any inline credentials if present (user:pass@host)
        if (cleanUrl.contains("@")) {
            String[] parts = cleanUrl.split("@", 2);
            cleanUrl = "***@" + parts[1];
        }
        return cleanUrl;
    }
}