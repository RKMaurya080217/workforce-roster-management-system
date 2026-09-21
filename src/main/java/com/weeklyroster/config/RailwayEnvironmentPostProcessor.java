package com.weeklyroster.config;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
 * 2. MySQL Database Connection with complete credential unification:
 *    - Priority resolution across full connection URLs ($MYSQL_URL, $MYSQL_PUBLIC_URL, $DATABASE_URL)
 *      and individual variables ($MYSQLHOST, $MYSQLPORT, $MYSQLUSER, $MYSQLPASSWORD, $MYSQLDATABASE)
 *    - Seamless credential fallback: If $MYSQLHOST is set but $MYSQLPASSWORD is in $MYSQL_URL,
 *      credentials from $MYSQL_URL are extracted and applied so the app never falls back to root/root
 *    - Non-root user preservation: If the database user is 'railway' or custom, it is correctly preserved
 * 3. Hibernate MySQL Dialect enforcement to eliminate "Unable to determine Dialect without JDBC metadata" failures
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

        // Enforce Hibernate MySQL Dialect across all profiles and deployments
        overrides.put("spring.jpa.database-platform", "org.hibernate.dialect.MySQLDialect");
        overrides.put("spring.jpa.properties.hibernate.dialect", "org.hibernate.dialect.MySQLDialect");

        // 1. Port mapping for Railway ($PORT)
        String railwayPort = cleanValue(environment.getProperty("PORT"));
        if (railwayPort != null && !railwayPort.isBlank()) {
            overrides.put("server.port", railwayPort.trim());
        }

        // 2. Database configuration resolution with unified credential synthesis
        // A. Parse full connection URL if available
        String rawUrl = cleanValue(getFirstNonBlank(environment,
                "MYSQL_URL",
                "MYSQL_PUBLIC_URL",
                "MYSQL_PRIVATE_URL",
                "DATABASE_URL",
                "DATABASE_PUBLIC_URL",
                "SPRING_DATASOURCE_URL",
                "DB_URL",
                "JDBC_DATABASE_URL"));

        ParsedUrl parsedUrl = (rawUrl != null && !rawUrl.isBlank() && !rawUrl.contains("localhost:3306"))
                ? parseMysqlUrl(rawUrl)
                : null;

        // B. Check individual environment variables
        String envHost = cleanValue(getFirstNonBlank(environment, "MYSQLHOST", "MYSQL_HOST", "DB_HOST", "DATABASE_HOST"));
        String envPort = cleanValue(getFirstNonBlank(environment, "MYSQLPORT", "MYSQL_PORT", "DB_PORT", "DATABASE_PORT"));
        String envDb   = cleanValue(getFirstNonBlank(environment, "MYSQLDATABASE", "MYSQL_DATABASE", "DB_NAME", "DATABASE_NAME"));
        String envUser = cleanValue(getFirstNonBlank(environment,
                "MYSQLUSER", "MYSQL_USER", "MYSQL_USERNAME",
                "DB_USERNAME", "DB_USER",
                "DATABASE_USERNAME", "DATABASE_USER",
                "SPRING_DATASOURCE_USERNAME"));
        String envPass = cleanPassword(getFirstNonBlank(environment,
                "MYSQLPASSWORD", "MYSQL_PASSWORD", "MYSQL_ROOT_PASSWORD",
                "DB_PASSWORD", "DB_PASS",
                "DATABASE_PASSWORD",
                "SPRING_DATASOURCE_PASSWORD"));

        // C. Check if running in Railway / remote MySQL environment
        boolean isRailwayOrRemote = (envHost != null && !envHost.isBlank() && !envHost.equalsIgnoreCase("localhost"))
                || (parsedUrl != null && parsedUrl.host != null && !parsedUrl.host.equalsIgnoreCase("localhost"));

        if (isRailwayOrRemote) {
            String effectiveHost = (envHost != null && !envHost.isBlank() && !envHost.equalsIgnoreCase("localhost"))
                    ? envHost
                    : (parsedUrl != null ? parsedUrl.host : null);

            int effectivePort = 3306;
            if (envPort != null && !envPort.isBlank()) {
                try {
                    effectivePort = Integer.parseInt(envPort);
                } catch (NumberFormatException ignored) {}
            } else if (parsedUrl != null && parsedUrl.port > 0) {
                effectivePort = parsedUrl.port;
            }

            String effectiveDatabase = (envDb != null && !envDb.isBlank())
                    ? envDb
                    : (parsedUrl != null && parsedUrl.database != null && !parsedUrl.database.isBlank())
                        ? parsedUrl.database
                        : (effectiveHost != null && effectiveHost.contains("railway") ? "railway" : "weekly_roster_db");

            // Unified credential resolution: prefer individual var, fall back to URL credentials
            String effectiveUsername = (envUser != null && !envUser.isBlank())
                    ? envUser
                    : (parsedUrl != null && parsedUrl.username != null && !parsedUrl.username.isBlank())
                        ? parsedUrl.username
                        : null;

            String effectivePassword = (envPass != null && !envPass.isBlank())
                    ? envPass
                    : (parsedUrl != null && parsedUrl.password != null && !parsedUrl.password.isBlank())
                        ? parsedUrl.password
                        : null;

            // Direct JDBC URL without credentials pass-through support
            String jdbcUrl;
            if (rawUrl != null && rawUrl.startsWith("jdbc:mysql://") && !rawUrl.contains("@")) {
                jdbcUrl = rawUrl;
            } else {
                jdbcUrl = "jdbc:mysql://" + effectiveHost + ":" + effectivePort + "/" + effectiveDatabase
                        + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Kolkata";
            }

            overrides.put("spring.datasource.url", jdbcUrl);

            if (effectiveUsername != null && !effectiveUsername.isBlank()) {
                overrides.put("spring.datasource.username", effectiveUsername);
            }
            if (effectivePassword != null) {
                overrides.put("spring.datasource.password", effectivePassword);
            }

            boolean hasPassword = (effectivePassword != null && !effectivePassword.isBlank());
            String msg = String.format("[WRMS Production Config] Detected Railway MySQL environment. Configured host %s:%d, database %s, username %s, passwordConfigured=%b",
                    effectiveHost, effectivePort, effectiveDatabase,
                    (effectiveUsername != null ? effectiveUsername : "DEFAULT"),
                    hasPassword);
            System.out.println(msg);
            log.info(msg);

            if (!hasPassword) {
                String warnMsg = "[WRMS Production Config] WARNING: No MySQL password found in environment variables (MYSQLPASSWORD, MYSQL_URL, DATABASE_URL, etc.)! If connection fails with Access Denied (Error 1045), please ensure MYSQLPASSWORD or MYSQL_URL is added to Railway Web Service variables.";
                System.err.println(warnMsg);
                log.warn(warnMsg);
            }
        } else {
            String msg = "[WRMS Production Config] No remote Railway MySQL variables detected. Using default datasource fallback.";
            System.out.println(msg);
            log.info(msg);
        }

        // 3. Mail environment variables mapping for Railway (MAIL_USERNAME, MAIL_APP_PASSWORD, etc.)
        String mailHost = cleanValue(getFirstNonBlank(environment, "SPRING_MAIL_HOST", "MAIL_HOST", "SMTP_HOST"));
        if (mailHost != null && !mailHost.isBlank()) {
            overrides.put("spring.mail.host", mailHost.trim());
        }

        String mailPort = cleanValue(getFirstNonBlank(environment, "SPRING_MAIL_PORT", "MAIL_PORT", "SMTP_PORT"));
        if (mailPort != null && !mailPort.isBlank()) {
            overrides.put("spring.mail.port", mailPort.trim());
        }

        String mailUser = cleanValue(getFirstNonBlank(environment, "MAIL_USERNAME", "SPRING_MAIL_USERNAME", "SMTP_USERNAME", "SPRING_MAIL_USER"));
        if (mailUser != null && !mailUser.isBlank()) {
            overrides.put("spring.mail.username", mailUser.replace("\"", "").replace("'", "").trim());
        }

        String mailPass = cleanValue(getFirstNonBlank(environment, "MAIL_APP_PASSWORD", "SPRING_MAIL_PASSWORD", "MAIL_PASSWORD", "SMTP_PASSWORD", "SPRING_MAIL_APP_PASSWORD"));
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

    private static String cleanValue(String val) {
        if (val == null) return null;
        String trimmed = val.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private static String cleanPassword(String pass) {
        if (pass == null) return null;
        String p = pass;
        if ((p.startsWith("\"") && p.endsWith("\"")) || (p.startsWith("'") && p.endsWith("'"))) {
            p = p.substring(1, p.length() - 1);
        }
        p = p.replaceAll("[\\r\\n]+$", "");
        return p;
    }

    private static class ParsedUrl {
        String host;
        int port = 3306;
        String database;
        String username;
        String password;
    }

    private ParsedUrl parseMysqlUrl(String rawUrl) {
        try {
            String url = cleanValue(rawUrl);
            if (url == null || url.isBlank()) return null;

            ParsedUrl result = new ParsedUrl();

            // Extract query parameters if present (e.g. ?user=...&password=...)
            if (url.contains("?")) {
                String query = url.substring(url.indexOf('?') + 1);
                for (String param : query.split("&")) {
                    int eq = param.indexOf('=');
                    if (eq != -1) {
                        String k = param.substring(0, eq).trim();
                        String v = param.substring(eq + 1).trim();
                        if ("user".equalsIgnoreCase(k)) {
                            try { result.username = URLDecoder.decode(v, StandardCharsets.UTF_8); } catch (Exception e) { result.username = v; }
                        } else if ("password".equalsIgnoreCase(k)) {
                            try { result.password = URLDecoder.decode(v, StandardCharsets.UTF_8); } catch (Exception e) { result.password = v; }
                        }
                    }
                }
            }

            // Strip protocol prefixes
            if (url.startsWith("jdbc:mysql://")) {
                url = url.substring("jdbc:mysql://".length());
            } else if (url.startsWith("mysql://")) {
                url = url.substring("mysql://".length());
            }

            // Strip query string for path segment parsing
            int qIdx = url.indexOf('?');
            if (qIdx != -1) {
                url = url.substring(0, qIdx);
            }

            // Extract embedded user:pass@host:port/database
            int atIndex = url.lastIndexOf('@');
            if (atIndex != -1) {
                String userInfo = url.substring(0, atIndex);
                url = url.substring(atIndex + 1);
                int colonIndex = userInfo.indexOf(':');
                if (colonIndex != -1) {
                    String u = userInfo.substring(0, colonIndex);
                    String p = userInfo.substring(colonIndex + 1);
                    try { result.username = URLDecoder.decode(u, StandardCharsets.UTF_8); } catch (Exception e) { result.username = u; }
                    try { result.password = URLDecoder.decode(p, StandardCharsets.UTF_8); } catch (Exception e) { result.password = p; }
                } else {
                    try { result.username = URLDecoder.decode(userInfo, StandardCharsets.UTF_8); } catch (Exception e) { result.username = userInfo; }
                }
            }

            // Parse host:port/database
            int slashIndex = url.indexOf('/');
            String hostPort = slashIndex != -1 ? url.substring(0, slashIndex) : url;
            String db = slashIndex != -1 ? url.substring(slashIndex + 1) : "";
            if (!db.isBlank()) {
                result.database = db.trim();
            }

            int portColon = hostPort.indexOf(':');
            if (portColon != -1) {
                result.host = hostPort.substring(0, portColon).trim();
                try {
                    result.port = Integer.parseInt(hostPort.substring(portColon + 1).trim());
                } catch (NumberFormatException ignored) {}
            } else {
                result.host = hostPort.trim();
            }

            if (result.username != null) {
                result.username = cleanValue(result.username);
            }
            if (result.password != null) {
                result.password = cleanPassword(result.password);
            }

            return result;
        } catch (Exception e) {
            log.warn("[WRMS Production Config] Could not parse MySQL URL format: {}", e.getMessage());
            return null;
        }
    }
}