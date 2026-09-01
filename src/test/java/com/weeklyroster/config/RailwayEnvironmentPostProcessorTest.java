package com.weeklyroster.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

public class RailwayEnvironmentPostProcessorTest {

    @Test
    @DisplayName("1. Railway Port ($PORT) is mapped to server.port")
    void testRailwayPortMapping() {
        ConfigurableEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("testRailwayPort", Map.of("PORT", "9090")));

        RailwayEnvironmentPostProcessor processor = new RailwayEnvironmentPostProcessor();
        processor.postProcessEnvironment(env, new SpringApplication());

        assertEquals("9090", env.getProperty("server.port"));
    }

    @Test
    @DisplayName("2. Individual Railway MySQL variables (MYSQLHOST, MYSQLPORT, etc.) construct valid JDBC URL")
    void testRailwayIndividualVariables() {
        ConfigurableEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("testRailwayVars", Map.of(
                "MYSQLHOST", "roundhouse.proxy.rlwy.net",
                "MYSQLPORT", "58932",
                "MYSQLDATABASE", "railway",
                "MYSQLUSER", "root",
                "MYSQLPASSWORD", "mypassword123"
        )));

        RailwayEnvironmentPostProcessor processor = new RailwayEnvironmentPostProcessor();
        processor.postProcessEnvironment(env, new SpringApplication());

        String jdbcUrl = env.getProperty("spring.datasource.url");
        assertNotNull(jdbcUrl);
        assertTrue(jdbcUrl.startsWith("jdbc:mysql://roundhouse.proxy.rlwy.net:58932/railway"));
        assertEquals("root", env.getProperty("spring.datasource.username"));
        assertEquals("mypassword123", env.getProperty("spring.datasource.password"));
    }

    @Test
    @DisplayName("3. Full Railway MYSQL_URL (mysql://user:pass@host:port/db) is converted to standard JDBC URL")
    void testRailwayMysqlUrlParsing() {
        ConfigurableEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("testRailwayUrl", Map.of(
                "MYSQL_URL", "mysql://admin_user:secret_pass@mysql.railway.internal:3306/production_wrms"
        )));

        RailwayEnvironmentPostProcessor processor = new RailwayEnvironmentPostProcessor();
        processor.postProcessEnvironment(env, new SpringApplication());

        String jdbcUrl = env.getProperty("spring.datasource.url");
        assertNotNull(jdbcUrl);
        assertTrue(jdbcUrl.startsWith("jdbc:mysql://mysql.railway.internal:3306/production_wrms"));
        assertEquals("admin_user", env.getProperty("spring.datasource.username"));
        assertEquals("secret_pass", env.getProperty("spring.datasource.password"));
    }
}