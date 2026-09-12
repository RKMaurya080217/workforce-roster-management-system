package com.weeklyroster.config;

import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Automatically detects and configures Railway production environment variables for:
 * 1. Server Port ($PORT)
 * 2. MySQL Database Connection ($MYSQLHOST, $MYSQLPORT, $MYSQLDATABASE, $MYSQLUSER, $MYSQLPASSWORD, or $MYSQL_URL)
 *
 * Retains safe local development fallbacks (localhost:3306 / 8080) when Railway variables are not present.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RailwayEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(RailwayEnvironmentPostProcessor.class);
    private static final String PROPERTY_SOURCE_NAME = "railwayEnvironmentOverrides";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        System.setProperty("java.awt.headless", "true");
        if (application != null) {
            application.setHeadless(true);
        }
        Map<String, Object> overrides = new HashMap<>();
        overrides.put("spring.main.headless", "true");

        // 1. Port mapping for Railway ($PORT)
        String railwayPort = environment.getProperty("PORT");
        if (railwayPort != null && !railwayPort.isBlank()) {
            overrides.put("server.port", railwayPort.trim());
        }

        // 2. Database configuration resolution
        // Priority 1: Individual Railway MySQL variables (cleanest, no URL escaping issues)
        String host = getFirstNonBlank(environment, "MYSQLHOST", "MYSQL_HOST", "DB_HOST");
        String port = getFirstNonBlank(environment, "MYSQLPORT", "MYSQL_PORT", "DB_PORT");
        String database = getFirstNonBlank(environment, "MYSQLDATABASE", "MYSQL_DATABASE", "DB_NAME");
        String username = getFirstNonBlank(environment, "MYSQLUSER", "MYSQL_USER", "DB_USERNAME");
        String password = getFirstNonBlank(environment, "MYSQLPASSWORD", "MYSQL_PASSWORD", "DB_PASSWORD");

        boolean hasIndividualVars = (host != null && !host.isBlank() && !host.equalsIgnoreCase("localhost"));

        if (hasIndividualVars) {
            int portNum = 3306;
            if (port != null && !port.isBlank()) {
                try {
                    portNum = Integer.parseInt(port.trim());
                } catch (NumberFormatException ignored) {}
            }
            String dbName = (database != null && !database.isBlank()) ? database.trim() : "weekly_roster_db";
            String jdbcUrl = "jdbc:mysql://" + host.trim() + ":" + portNum + "/" + dbName
                    + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Kolkata";

            overrides.put("spring.datasource.url", jdbcUrl);
            if (username != null && !username.isBlank()) {
                overrides.put("spring.datasource.username", username.trim());
            }
            if (password != null) {
                overrides.put("spring.datasource.password", password);
            }
            log.info("[WRMS Production Config] Detected Railway MySQL environment. Configured host {}:{} and database {}", host.trim(), portNum, dbName);
        } else {
            // Priority 2: Full MySQL URL (MYSQL_URL or DATABASE_URL)
            String mysqlUrl = environment.getProperty("MYSQL_URL");
            if (mysqlUrl == null || mysqlUrl.isBlank()) {
                mysqlUrl = environment.getProperty("DATABASE_URL");
            }

            if (mysqlUrl != null && !mysqlUrl.isBlank()) {
                parseAndApplyMysqlUrl(mysqlUrl.trim(), overrides);
            } else {
                log.info("[WRMS Production Config] No remote Railway MySQL variables detected. Using default datasource fallback.");
            }
        }

                // 4. Mail environment variables mapping for Railway (MAIL_USERNAME, MAIL_APP_PASSWORD, etc.)
        String mailHost = getFirstNonBlank(environment, "SPRING_MAIL_HOST", "MAIL_HOST", "SMTP_HOST");
        if (mailHost != null && !mailHost.isBlank()) {
            overrides.put("spring.mail.host", mailHost.trim());
        }

        String mailPort = getFirstNonBlank(environment, "SPRING_MAIL_PORT", "MAIL_PORT", "SMTP_PORT");
        if (mailPort != null && !mailPort.isBlank()) {
            overrides.put("spring.mail.port", mailPort.trim());
        }

        String mailUser = getFirstNonBlank(environment, "MAIL_USERNAME", "SPRING_MAIL_USERNAME", "SMTP_USERNAME", "SPRING_MAIL_USER");
        if (mailUser != null && !mailUser.isBlank()) {
            overrides.put("spring.mail.username", mailUser.replace("\"", "").replace("'", "").trim());
        }

        String mailPass = getFirstNonBlank(environment, "MAIL_APP_PASSWORD", "SPRING_MAIL_PASSWORD", "MAIL_PASSWORD", "SMTP_PASSWORD", "SPRING_MAIL_APP_PASSWORD");
        if (mailPass != null && !mailPass.isBlank()) {
            overrides.put("spring.mail.password", mailPass.replace("\"", "").replace("'", "").replace(" ", "").trim());
        }

        if (!overrides.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, overrides));
        }
    }

    private static String getFirstNonBlank(ConfigurableEnvironment env, String... keys) {
        for (String key : keys) {
            String val = env.getProperty(key);
            if (val != null && !val.isBlank()) {
                return val;
            }
        }
        return null;
    }

    private void parseAndApplyMysqlUrl(String mysqlUrl, Map<String, Object> overrides) {
        try {
            String url = mysqlUrl;
            if (url.startsWith("jdbc:mysql://")) {
                overrides.put("spring.datasource.url", url);
                log.info("[WRMS Production Config] Detected JDBC MySQL URL directly.");
                return;
            }
            if (url.startsWith("mysql://")) {
                url = url.substring(8);
            }

            String username = null;
            String password = null;
            int atIndex = url.lastIndexOf('@');
            if (atIndex != -1) {
                String userInfo = url.substring(0, atIndex);
                url = url.substring(atIndex + 1);
                int colonIndex = userInfo.indexOf(':');
                if (colonIndex != -1) {
                    username = userInfo.substring(0, colonIndex);
                    password = userInfo.substring(colonIndex + 1);
                } else {
                    username = userInfo;
                }
            }

            int slashIndex = url.indexOf('/');
            String hostPort = slashIndex != -1 ? url.substring(0, slashIndex) : url;
            String database = slashIndex != -1 ? url.substring(slashIndex + 1) : "weekly_roster_db";
            if (database.contains("?")) {
                database = database.substring(0, database.indexOf('?'));
            }
            if (database.isBlank()) {
                database = "weekly_roster_db";
            }

            String host = hostPort;
            int port = 3306;
            int portColon = hostPort.indexOf(':');
            if (portColon != -1) {
                host = hostPort.substring(0, portColon);
                try {
                    port = Integer.parseInt(hostPort.substring(portColon + 1));
                } catch (NumberFormatException ignored) {}
            }

            String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Kolkata";

            overrides.put("spring.datasource.url", jdbcUrl);
            if (username != null && !username.isBlank()) {
                overrides.put("spring.datasource.username", username);
            }
            if (password != null) {
                overrides.put("spring.datasource.password", password);
            }
            log.info("[WRMS Production Config] Successfully parsed MYSQL_URL. Configured host {}:{} and database {}", host, port, database);
        } catch (Exception e) {
            log.warn("[WRMS Production Config] Could not parse MYSQL_URL format: {}", e.getMessage());
        }
    }
}