package com.weeklyroster.config;

import java.net.URI;
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
        Map<String, Object> overrides = new HashMap<>();

        // 1. Port mapping for Railway ($PORT)
        String railwayPort = environment.getProperty("PORT");
        if (railwayPort != null && !railwayPort.isBlank()) {
            overrides.put("server.port", railwayPort.trim());
        }

        // 2. Full MySQL URL parsing (e.g. MYSQL_URL or DATABASE_URL: mysql://user:pass@host:port/dbname)
        String mysqlUrl = environment.getProperty("MYSQL_URL");
        if (mysqlUrl == null || mysqlUrl.isBlank()) {
            mysqlUrl = environment.getProperty("DATABASE_URL");
        }

        if (mysqlUrl != null && !mysqlUrl.isBlank() && mysqlUrl.startsWith("mysql://")) {
            try {
                // Convert mysql:// URI to standard JDBC components
                URI uri = new URI(mysqlUrl.replace("mysql://", "http://"));
                String host = uri.getHost();
                int port = uri.getPort() != -1 ? uri.getPort() : 3306;
                String path = uri.getPath();
                String database = (path != null && path.length() > 1) ? path.substring(1) : "weekly_roster_db";

                String userInfo = uri.getUserInfo();
                String username = null;
                String password = null;
                if (userInfo != null && userInfo.contains(":")) {
                    String[] parts = userInfo.split(":", 2);
                    username = parts[0];
                    password = parts[1];
                } else if (userInfo != null) {
                    username = userInfo;
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
                log.info("[WRMS Production Config] Detected Railway MYSQL_URL. Configured host {}:{} and database {}", host, port, database);
            } catch (Exception e) {
                log.warn("[WRMS Production Config] Could not parse MYSQL_URL format: {}", e.getMessage());
            }
        } else {
            // 3. Check individual Railway MySQL variables (MYSQLHOST, MYSQLPORT, MYSQLDATABASE, MYSQLUSER, MYSQLPASSWORD)
            String host = getFirstNonBlank(environment, "MYSQLHOST", "MYSQL_HOST", "DB_HOST");
            String port = getFirstNonBlank(environment, "MYSQLPORT", "MYSQL_PORT", "DB_PORT");
            String database = getFirstNonBlank(environment, "MYSQLDATABASE", "MYSQL_DATABASE", "DB_NAME");
            String username = getFirstNonBlank(environment, "MYSQLUSER", "MYSQL_USER", "DB_USERNAME");
            String password = getFirstNonBlank(environment, "MYSQLPASSWORD", "MYSQL_PASSWORD", "DB_PASSWORD");

            if (host != null && !host.isBlank() && !host.equals("localhost")) {
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
                log.info("[WRMS Production Config] Detected Railway MySQL environment. Configured host {}:{} and database {}", host, portNum, dbName);
            }
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
}