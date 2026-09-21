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

    @Test
    @DisplayName("4. Railway MYSQL_URL with special characters in password is parsed correctly")
    void testRailwayMysqlUrlWithSpecialCharsInPassword() {
        ConfigurableEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("testRailwayUrlSpecial", Map.of(
                "MYSQL_URL", "mysql://root:p@ss#word!123@mysql.railway.internal:3306/railway"
        )));

        RailwayEnvironmentPostProcessor processor = new RailwayEnvironmentPostProcessor();
        processor.postProcessEnvironment(env, new SpringApplication());

        String jdbcUrl = env.getProperty("spring.datasource.url");
        assertNotNull(jdbcUrl);
        assertTrue(jdbcUrl.startsWith("jdbc:mysql://mysql.railway.internal:3306/railway"));
        assertEquals("root", env.getProperty("spring.datasource.username"));
        assertEquals("p@ss#word!123", env.getProperty("spring.datasource.password"));
    }

    @Test
    @DisplayName("5. Direct JDBC URL (jdbc:mysql://...) is passed through without modification")
    void testDirectJdbcUrl() {
        ConfigurableEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("testDirectJdbc", Map.of(
                "MYSQL_URL", "jdbc:mysql://mysql.railway.internal:3306/railway?useSSL=false"
        )));

        RailwayEnvironmentPostProcessor processor = new RailwayEnvironmentPostProcessor();
        processor.postProcessEnvironment(env, new SpringApplication());

        assertEquals("jdbc:mysql://mysql.railway.internal:3306/railway?useSSL=false", env.getProperty("spring.datasource.url"));
    }

    @Test
    @DisplayName("6. Railway MYSQL_PUBLIC_URL is detected and parsed into JDBC URL")
    void testRailwayMysqlPublicUrl() {
        ConfigurableEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("testPublicUrl", Map.of(
                "MYSQL_PUBLIC_URL", "mysql://root:secure_proxy_pass@roundhouse.proxy.rlwy.net:45821/railway"
        )));

        RailwayEnvironmentPostProcessor processor = new RailwayEnvironmentPostProcessor();
        processor.postProcessEnvironment(env, new SpringApplication());

        String jdbcUrl = env.getProperty("spring.datasource.url");
        assertNotNull(jdbcUrl);
        assertTrue(jdbcUrl.startsWith("jdbc:mysql://roundhouse.proxy.rlwy.net:45821/railway"));
        assertEquals("root", env.getProperty("spring.datasource.username"));
        assertEquals("secure_proxy_pass", env.getProperty("spring.datasource.password"));
    }

    @Test
    @DisplayName("7. Quoted environment variable values are cleanly unquoted")
    void testQuotedEnvironmentValues() {
        ConfigurableEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("testQuoted", Map.of(
                "MYSQL_URL", "\"mysql://root:secret@mysql.railway.internal:3306/railway\"",
                "PORT", "\"9000\""
        )));

        RailwayEnvironmentPostProcessor processor = new RailwayEnvironmentPostProcessor();
        processor.postProcessEnvironment(env, new SpringApplication());

        assertEquals("9000", env.getProperty("server.port"));
        String jdbcUrl = env.getProperty("spring.datasource.url");
        assertNotNull(jdbcUrl);
        assertTrue(jdbcUrl.startsWith("jdbc:mysql://mysql.railway.internal:3306/railway"));
    }

    @Test
    @DisplayName("8. Percent-encoded credentials in MySQL URL are URL-decoded")
    void testPercentEncodedCredentials() {
        ConfigurableEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("testEncoded", Map.of(
                "MYSQL_URL", "mysql://admin%40corp:pass%23123@roundhouse.proxy.rlwy.net:3306/railway"
        )));

        RailwayEnvironmentPostProcessor processor = new RailwayEnvironmentPostProcessor();
        processor.postProcessEnvironment(env, new SpringApplication());

        assertEquals("admin@corp", env.getProperty("spring.datasource.username"));
        assertEquals("pass#123", env.getProperty("spring.datasource.password"));
    }

    @Test
    @DisplayName("9. Explicit Hibernate MySQL Dialect is configured in environment overrides")
    void testHibernateDialectConfigured() {
        ConfigurableEnvironment env = new StandardEnvironment();

        RailwayEnvironmentPostProcessor processor = new RailwayEnvironmentPostProcessor();
        processor.postProcessEnvironment(env, new SpringApplication());

        assertEquals("org.hibernate.dialect.MySQLDialect", env.getProperty("spring.jpa.database-platform"));
        assertEquals("org.hibernate.dialect.MySQLDialect", env.getProperty("spring.jpa.properties.hibernate.dialect"));
    }

    @Test
    @DisplayName("10. Seamless fallback: MYSQLHOST set but password extracted from MYSQL_URL")
    void testHostSetWithPasswordFromUrl() {
        ConfigurableEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("testMixedAuth", Map.of(
                "MYSQLHOST", "mysql.railway.internal",
                "MYSQLPORT", "3306",
                "MYSQLDATABASE", "railway",
                "MYSQL_URL", "mysql://root:real_generated_secret_456@mysql.railway.internal:3306/railway"
        )));

        RailwayEnvironmentPostProcessor processor = new RailwayEnvironmentPostProcessor();
        processor.postProcessEnvironment(env, new SpringApplication());

        String jdbcUrl = env.getProperty("spring.datasource.url");
        assertNotNull(jdbcUrl);
        assertTrue(jdbcUrl.startsWith("jdbc:mysql://mysql.railway.internal:3306/railway"));
        assertEquals("root", env.getProperty("spring.datasource.username"));
        assertEquals("real_generated_secret_456", env.getProperty("spring.datasource.password"));
    }

    @Test
    @DisplayName("11. Non-root user in MYSQL_URL ('railway') is preserved when MYSQLHOST is present")
    void testNonRootUserInUrlPreservedWithHost() {
        ConfigurableEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("testNonRootUser", Map.of(
                "MYSQLHOST", "mysql.railway.internal",
                "MYSQLPORT", "3306",
                "MYSQLDATABASE", "railway",
                "MYSQL_URL", "mysql://railway:super_custom_pass_789@mysql.railway.internal:3306/railway"
        )));

        RailwayEnvironmentPostProcessor processor = new RailwayEnvironmentPostProcessor();
        processor.postProcessEnvironment(env, new SpringApplication());

        assertEquals("railway", env.getProperty("spring.datasource.username"));
        assertEquals("super_custom_pass_789", env.getProperty("spring.datasource.password"));
    }
}